package com.apm.agent.util;

/**
 * 간단한 내장 File/Console Logger 구현체.
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
            System.out.println(PREFIX + " [DEBUG] " + message);
        }
    }

    public static void info(String message) {
        if (currentLevel.getValue() <= Level.INFO.getValue()) {
            System.out.println(PREFIX + " [INFO] " + message);
        }
    }

    public static void warn(String message) {
        if (currentLevel.getValue() <= Level.WARN.getValue()) {
            System.out.println(PREFIX + " [WARN] " + message);
        }
    }

    public static void error(String message) {
        if (currentLevel.getValue() <= Level.ERROR.getValue()) {
            System.err.println(PREFIX + " [ERROR] " + message);
        }
    }

    public static void error(String message, Throwable t) {
        if (currentLevel.getValue() <= Level.ERROR.getValue()) {
            System.err.println(PREFIX + " [ERROR] " + message);
            t.printStackTrace(System.err);
        }
    }
}
