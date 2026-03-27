package com.apm.agent.advice;

import net.bytebuddy.asm.Advice;
import com.apm.agent.LxAgent;
import com.apm.agent.util.HttpContext;
import com.apm.agent.util.Constants;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MethodInterceptor {

    @Advice.OnMethodEnter
    public static long onEnter() {
        return System.currentTimeMillis();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Enter long startTime,
            @Advice.Thrown Throwable throwable) {
        long duration = System.currentTimeMillis() - startTime;

        // 에러가 발생하지 않았고, 경과 시간이 임계치 미만이면 수집하지 않음 (노이즈 필터링)
        if (throwable == null && duration < LxAgent.minDurationMs) {
            return;
        }

        // HttpContext에서 txId 가져오기 (없으면 생성 - 비-HTTP 환경 대비)
        HttpContext.Context ctx = HttpContext.get();
        String txId;
        if (ctx != null) {
            txId = ctx.txId;
        } else {
            txId = HttpContext.generateId(Constants.PREFIX_NON_HTTP);
        }
        
        String serviceName = className + "." + methodName;
        boolean isError = throwable != null;
        int httpStatusCode = isError ? 500 : 200;

        // 1. TRANSACTION 메트릭 생성
        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"type\":\"").append(Constants.TYPE_TRANSACTION).append("\",")
                .append("\"txId\":\"").append(txId).append("\",")
                .append("\"timestamp\":").append(startTime).append(",")
                .append("\"responseTimeMs\":").append(duration).append(",")
                .append("\"serviceName\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(serviceName))
                .append("\",")
                .append("\"isError\":").append(isError).append(",")
                .append("\"httpStatusCode\":").append(httpStatusCode);

        if (isError) {
            String errorMsg = throwable.getMessage();
            if (errorMsg == null) {
                errorMsg = throwable.getClass().getName();
            }
            sb.append(",\"error\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(errorMsg)).append("\"");
        }
        sb.append("}");

        // 2. ERROR_DETAIL 메트릭 생성 (에러 발생 시에만)
        if (isError) {
            sendErrorDetail(txId, throwable, ctx);
        }

        // LxAgent의 전역 Sender를 통해 메트릭 전송 (큐에 삽입)
        if (LxAgent.dataSender != null) {
            LxAgent.dataSender.addMetric(sb.toString());
        }
    }

    public static void sendErrorDetail(String txId, Throwable throwable, HttpContext.Context ctx) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{")
                .append("\"type\":\"").append(Constants.TYPE_ERROR_DETAIL).append("\",")
                .append("\"txId\":\"").append(txId).append("\",")
                .append("\"exceptionName\":\"").append(throwable.getClass().getName()).append("\",")
                .append("\"errorMessage\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(throwable.getMessage())).append("\",")
                .append("\"stackTrace\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(stackTrace)).append("\",")
                .append("\"threadName\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(Thread.currentThread().getName())).append("\"");

        if (ctx != null) {
            sb.append(",\"requestUrl\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(ctx.url)).append("\",")
              .append("\"httpMethod\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(ctx.method)).append("\",")
              .append("\"requestParams\":\"").append(com.apm.agent.reporter.AgentDataSender.escapeJson(ctx.queryParams)).append("\"");
        }

        sb.append("}");

        if (LxAgent.dataSender != null) {
            LxAgent.dataSender.addMetric(sb.toString());
        }
    }
}
