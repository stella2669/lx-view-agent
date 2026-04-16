package com.apm.agent.util;

/**
 * 프로젝트 전반에서 사용하는 공통 상수를 관리합니다.
 */
public class Constants {

    // Metric Types
    public static final String TYPE_TRANSACTION = "TRANSACTION";
    public static final String TYPE_ERROR_DETAIL = "ERROR_DETAIL";
    public static final String TYPE_JVM = "JVM";
    public static final String TYPE_SQL_METRIC = "SQL";

    // ID Prefixes
    public static final String PREFIX_HTTP = "REQ";
    public static final String PREFIX_NON_HTTP = "TX";

    // Date Formats
    public static final String DATE_FORMAT_ID = "yyyyMMddHHmmss";
    public static final String DATE_FORMAT_LOG = "yyyy-MM-dd HH:mm:ss.SSS";
    public static final String DATE_FORMAT_FILE = "yyyy-MM-dd";

    // Network & Timeout
    public static final String DEFAULT_SERVER_URL = "http://localhost:8080/api/metrics";
    public static final int DEFAULT_CONNECT_TIMEOUT = 3000;
    public static final int DEFAULT_READ_TIMEOUT = 3000;
    public static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";

    // Agent Defaults
    public static final int DEFAULT_BATCH_SIZE = 10;
    public static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 5;
    public static final int DEFAULT_MAX_QUEUE_SIZE = 1000;
    public static final int DEFAULT_MIN_DURATION_MS = 0;
    public static final int DEFAULT_JVM_METRIC_INTERVAL_SECONDS = 10;
    
    // JDBC slow query
    public static final int DEFAULT_SLOW_QUERY_THRESHOLD_MS = 1000;

    // Security
    public static final String AGENT_KEY_HEADER = "X-LX-Agent-Key";
}
