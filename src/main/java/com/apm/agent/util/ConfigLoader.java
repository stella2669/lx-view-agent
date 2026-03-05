package com.apm.agent.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigLoader {
    private static final Properties properties = new Properties();
    private static boolean isLoaded = false;

    /**
     * 지정된 경로의 프로퍼티 파일을 로드합니다.
     * 파일이 없거나 경로가 잘못된 경우 기본값을 유지합니다.
     *
     * @param configFilePath 프로퍼티 파일 절대 경로
     */
    public static void load(String configFilePath) {
        if (configFilePath == null || configFilePath.trim().isEmpty()) {
            Logger.warn("No config file path provided. Using default agent configurations.");
            return;
        }

        try (InputStream input = new FileInputStream(configFilePath)) {
            properties.load(input);
            isLoaded = true;
            Logger.info("Successfully loaded agent configurations from: " + configFilePath);

            // 프로퍼티에서 로그 레벨 설정이 있다면 덮어쓰기 (시스템 파라미터가 없다면)
            String logLevel = properties.getProperty("lx.agent.log.level");
            if (logLevel != null && System.getProperty("lx.agent.log.level") == null) {
                try {
                    Logger.setLevel(Logger.Level.valueOf(logLevel.toUpperCase()));
                    Logger.debug("Log level updated to " + logLevel + " from properties file");
                } catch (IllegalArgumentException e) {
                    Logger.warn("Invalid log level in properties: " + logLevel);
                }
            }

        } catch (IOException ex) {
            Logger.error("Failed to load generic config file:  " + configFilePath + " - " + ex.getMessage());
        }
    }

    /**
     * 프로퍼티 값을 조회합니다. 값이 없으면 defaultValue를 반환합니다.
     */
    public static String getProperty(String key, String defaultValue) {
        // 시스템 파라미터(-Dxxx=yyy)가 가장 높은 우선순위
        String sysProp = System.getProperty(key);
        if (sysProp != null)
            return sysProp;

        return properties.getProperty(key, defaultValue);
    }

    public static int getIntProperty(String key, int defaultValue) {
        String val = getProperty(key, null);
        if (val == null)
            return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            Logger.warn("Invalid integer format for key '" + key + "': " + val + ". Using default: " + defaultValue);
            return defaultValue;
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }
}
