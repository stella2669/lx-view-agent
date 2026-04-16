package com.apm.agent.advice;

import net.bytebuddy.asm.Advice;
import com.apm.agent.LxAgent;
import com.apm.agent.util.HttpContext;
import com.apm.agent.util.Constants;
import com.apm.agent.util.SqlContextMap;

import java.util.concurrent.ConcurrentHashMap;

public class JdbcInterceptor {

    // ──────────────────────────────────────────────────────────────────────────
    // L1 SQL 중복 전송 방지 캐시
    //
    // [목적]
    //   서버 측 중복 제거 로직이 있음에도 동일 쿼리가 짧은 시간 내 반복 실행될 경우
    //   불필요한 네트워크 트래픽이 발생함. 에이전트 내부에서 1차 필터링합니다.
    //
    // [동작 방식]
    //   - 키   : 정규화된 SQL 문자열 (앞뒤 공백 제거, 연속 공백 → 단일 공백)
    //   - 값   : 마지막 전송 시각 (System.currentTimeMillis())
    //   - 윈도우: CACHE_WINDOW_MS(10초) 내 동일 SQL은 1회만 전송
    //   - 예외 : 에러 쿼리(isError=true)는 항상 즉시 전송 (캐시 무시)
    //
    // [메모리 안전성]
    //   MAX_CACHE_SIZE 초과 시 만료된 항목을 지연 정리(Lazy Eviction)합니다.
    //   에이전트는 장시간 실행되므로 무한 증가를 방지합니다.
    // ──────────────────────────────────────────────────────────────────────────
    private static final long CACHE_WINDOW_MS = 10_000L;
    private static final int  MAX_CACHE_SIZE  = 500;

    /**
     * Key: 정규화된 SQL / Value: 마지막 서버 전송 시각(ms)
     */
    static final ConcurrentHashMap<String, Long> SQL_SEND_CACHE = new ConcurrentHashMap<>();

    /**
     * Connection.prepareStatement(String sql) 및 prepareCall(String sql) 가로채기
     * prepareCall의 반환 타입은 CallableStatement(PreparedStatement 서브타입)이므로
     * 타입 불일치를 피하기 위해 @Advice.Return을 Object로 받습니다.
     */
    public static class PrepareAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Argument(0) String sql, @Advice.Return Object ps) {
            if (ps != null && sql != null) {
                SqlContextMap.put(ps, sql);
            }
        }
    }

    /**
     * PreparedStatement 의 파라미터 없는 execute(), executeQuery(), executeUpdate() 가로채기
     */
    public static class PreparedStatementExecuteAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long onEnter() {
            return System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.This Object ps,
                                  @Advice.Enter long startTime,
                                  @Advice.Thrown Throwable throwable) {
            long duration = System.currentTimeMillis() - startTime;
            String sql = SqlContextMap.get(ps);

            checkAndSendSqlMetric(sql, duration, throwable);
        }
    }

    /**
     * Statement 의 파라미터 있는 execute(String), executeQuery(String), executeUpdate(String) 가로채기
     */
    public static class StatementExecuteAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long onEnter() {
            return System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Argument(0) String sql,
                                  @Advice.Enter long startTime,
                                  @Advice.Thrown Throwable throwable) {
            long duration = System.currentTimeMillis() - startTime;

            checkAndSendSqlMetric(sql, duration, throwable);
        }
    }

    public static void checkAndSendSqlMetric(String sql, long duration, Throwable throwable) {
        boolean isError = throwable != null;

        // SQL이 null/빈 문자열이면 에러도 함께 누락되므로, 에러 케이스는 "(unknown)"으로 대체하여 수집
        if (sql == null || sql.trim().isEmpty()) {
            if (!isError) {
                return;
            }
            sql = "(unknown)";
        }

        // 슬로우 쿼리(임계치 이상) 또는 에러 쿼리만 전송
        if (!isError && duration < LxAgent.slowQueryThresholdMs) {
            return;
        }

        // ── L1 캐시 필터링 (에러 쿼리는 항상 전송, 캐시 건너뜀) ──────────────────
        if (!isError) {
            // 정규화: 앞뒤 공백 제거 + 연속 공백 단일화로 표현식이 다른 동일 쿼리도 동일 키 처리
            String cacheKey = sql.trim().replaceAll("\\s+", " ");
            long now = System.currentTimeMillis();

            Long lastSent = SQL_SEND_CACHE.get(cacheKey);
            if (lastSent != null && (now - lastSent) < CACHE_WINDOW_MS) {
                // 윈도우 내 중복 전송 → 건너뜀
                return;
            }

            // 캐시 한도 초과 시 만료 항목 지연 정리
            if (SQL_SEND_CACHE.size() >= MAX_CACHE_SIZE) {
                evictExpiredEntries(now);
            }

            // 전송 시각 기록 (putIfAbsent로 경쟁 조건 최소화 — 극소량의 중복 허용)
            SQL_SEND_CACHE.put(cacheKey, now);
        }
        // ────────────────────────────────────────────────────────────────────────

        // txId 결정:
        //   HTTP  컨텍스트 : ctx.txId (MethodInterceptor와 동일)
        //   비-HTTP 컨텍스트 : getNonHttpTxId() (MethodInterceptor가 enterNonHttpCall로 생성한 공유 txId)
        //   완전 독립 실행  : 새 txId 생성 (fallback)
        // MethodInterceptor와 동일한 txId를 사용해야 SQL 에러 ↔ TRANSACTION 에러가 연결됩니다.
        HttpContext.Context ctx = HttpContext.get();
        String txId;
        if (ctx != null) {
            txId = ctx.txId;
        } else {
            String nonHttpId = HttpContext.getNonHttpTxId();
            txId = nonHttpId != null ? nonHttpId : HttpContext.generateId(Constants.PREFIX_NON_HTTP);
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"type\":\"").append(Constants.TYPE_SQL_METRIC).append("\",")
                .append("\"txId\":\"").append(txId).append("\",")
                .append("\"timestamp\":").append(System.currentTimeMillis() - duration).append(",")
                .append("\"responseTimeMs\":").append(duration).append(",")
                .append("\"sql\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(sql)).append("\",")
                .append("\"isError\":").append(isError);

        if (isError) {
            sb.append(",\"exceptionName\":\"").append(throwable.getClass().getName()).append("\"");
            String errorMsg = throwable.getMessage();
            if (errorMsg == null) {
                errorMsg = throwable.getClass().getName();
            }
            sb.append(",\"error\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(errorMsg)).append("\"");
        }
        sb.append("}");

        if (LxAgent.dataSender != null) {
            LxAgent.dataSender.addMetric(sb.toString());
        }
    }

    /**
     * 캐시 크기 한도 초과 시 CACHE_WINDOW_MS가 지난 만료 항목을 제거합니다.
     * 전체 캐시를 순회하므로 빈도가 낮도록 MAX_CACHE_SIZE를 충분히 크게 설정합니다.
     */
    private static void evictExpiredEntries(long now) {
        SQL_SEND_CACHE.entrySet().removeIf(e -> (now - e.getValue()) >= CACHE_WINDOW_MS);
    }
}
