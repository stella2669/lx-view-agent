package com.apm.agent.advice;

import net.bytebuddy.asm.Advice;
import com.apm.agent.LxAgent;
import com.apm.agent.util.HttpContext;
import com.apm.agent.util.Constants;
import com.apm.agent.util.SqlContextMap;

import java.sql.PreparedStatement;
import java.sql.Statement;

public class JdbcInterceptor {

    /**
     * Connection.prepareStatement(String sql) 및 prepareCall(String sql) 가로채기
     */
    public static class PrepareAdvice {
        @Advice.OnMethodExit
        public static void onExit(@Advice.Argument(0) String sql, @Advice.Return PreparedStatement ps) {
            if (ps != null && sql != null) {
                SqlContextMap.put(ps, sql);
            }
        }
    }

    /**
     * PreparedStatement 의 파라미터 없는 execute(), executeQuery(), executeUpdate() 가로채기
     */
    public static class PreparedStatementExecuteAdvice {
        @Advice.OnMethodEnter
        public static long onEnter() {
            return System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
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
        @Advice.OnMethodEnter
        public static long onEnter() {
            return System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String sql,
                                  @Advice.Enter long startTime,
                                  @Advice.Thrown Throwable throwable) {
            long duration = System.currentTimeMillis() - startTime;
            
            checkAndSendSqlMetric(sql, duration, throwable);
        }
    }

    public static void checkAndSendSqlMetric(String sql, long duration, Throwable throwable) {
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }

        // 1초 이상 걸린 슬로우 쿼리이거나, 예외가 발생한(에러 쿼리) 경우만 전송
        boolean isError = throwable != null;
        if (!isError && duration < 1000) {
            return;
        }

        HttpContext.Context ctx = HttpContext.get();
        String txId = (ctx != null) ? ctx.txId : HttpContext.generateId(Constants.PREFIX_NON_HTTP);

        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"type\":\"").append(Constants.TYPE_SQL_METRIC).append("\",")
                .append("\"txId\":\"").append(txId).append("\",")
                .append("\"timestamp\":").append(System.currentTimeMillis() - duration).append(",")
                .append("\"responseTimeMs\":").append(duration).append(",")
                .append("\"sql\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(sql)).append("\",")
                .append("\"isError\":").append(isError);

        if (isError) {
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
}
