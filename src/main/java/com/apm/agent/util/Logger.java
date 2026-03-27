package com.apm.agent.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 간단한 내장 Console + File Logger 구현체.
 * 타겟 애플리케이션의 의존성 충돌을 피하기 위해 slf4j나 logback 등을 배제합니다.
 */
public class Logger {

    public enum Level {
        DEBUG(1), INFO(2), WARN(3), ERROR(4), NONE(5);

        private final int value;

        Level(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static Level currentLevel = Level.INFO;
    private static final String PREFIX = "[LxAgent]";

    // 파일 출력 관련 필드. null이면 콘솔 전용 모드.
    private static PrintWriter fileWriter = null;
    private static final Object FILE_LOCK = new Object();

    static {
        // -Dlx.agent.log.level=DEBUG 옵션을 읽어서 설정 초기화
        String levelStr = System.getProperty("lx.agent.log.level");
        if (levelStr != null) {
            try {
                currentLevel = Level.valueOf(levelStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println(PREFIX + " Invalid log level: " + levelStr + ". Using default: " + currentLevel);
            }
        }
    }

    public static void initFileLogging(String logDir, String agentName) {
        if (logDir == null || logDir.trim().isEmpty()) {
            return;
        }

        try {
            File dir = new File(logDir.trim());
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println(PREFIX + " [WARN] Failed to create log directory: " + logDir);
                return;
            }

            String dateStr = new SimpleDateFormat(Constants.DATE_FORMAT_FILE).format(new Date());
            String safeAgentName = (agentName != null ? agentName : "unknown").replaceAll("[^a-zA-Z0-9_\\-]", "_");
            File logFile = new File(dir, "lx-agent-" + safeAgentName + "-" + dateStr + ".log");

            synchronized (FILE_LOCK) {
                fileWriter = new PrintWriter(
                        new BufferedWriter(
                                new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)),
                        true);
            }

            info("File logging initialized. Path: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println(PREFIX + " [ERROR] Failed to initialize file logging: " + e.getMessage());
        }
    }

    public static void close() {
        synchronized (FILE_LOCK) {
            if (fileWriter != null) {
                fileWriter.flush();
                fileWriter.close();
                fileWriter = null;
            }
        }
    }

    public static void setLevel(Level level) {
        if (level != null) {
            currentLevel = level;
        }
    }

    public static boolean isDebugEnabled() {
        return currentLevel.getValue() <= Level.DEBUG.getValue();
    }

    public static void debug(String message) {
        if (isDebugEnabled()) {
            write(Level.DEBUG, message, null);
        }
    }

    public static void info(String message) {
        if (currentLevel.getValue() <= Level.INFO.getValue()) {
            write(Level.INFO, message, null);
        }
    }

    public static void warn(String message) {
        if (currentLevel.getValue() <= Level.WARN.getValue()) {
            write(Level.WARN, message, null);
        }
    }

    public static void error(String message) {
        if (currentLevel.getValue() <= Level.ERROR.getValue()) {
            write(Level.ERROR, message, null);
        }
    }

    public static void error(String message, Throwable t) {
        if (currentLevel.getValue() <= Level.ERROR.getValue()) {
            write(Level.ERROR, message, t);
        }
    }

    private static void write(Level level, String message, Throwable t) {
        String timestamp = new SimpleDateFormat(Constants.DATE_FORMAT_LOG).format(new Date());
        String formatted = timestamp + " " + PREFIX + " [" + level.name() + "] " + message;

        synchronized (FILE_LOCK) {
            if (fileWriter != null) {
                fileWriter.println(formatted);
                if (t != null) {
                    t.printStackTrace(fileWriter);
                }
            } else {
                if (level == Level.ERROR) {
                    System.err.println(formatted);
                    if (t != null) t.printStackTrace(System.err);
                } else {
                    System.out.println(formatted);
                }
            }
        }
    }
}
