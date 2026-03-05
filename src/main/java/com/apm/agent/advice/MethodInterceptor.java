package com.apm.agent.advice;

import net.bytebuddy.asm.Advice;
import com.apm.agent.LxAgent;

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

        // API 스펙에 맞게 식별자 및 결과 코드 생성
        String txId = "REQ-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String serviceName = className + "." + methodName;
        boolean isError = throwable != null;
        int httpStatusCode = isError ? 500 : 200;

        // 빠른 문자열 연결을 위해 StringBuilder 사용
        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"type\":\"TRANSACTION\",")
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

        // LxAgent의 전역 Sender를 통해 메트릭 전송 (큐에 삽입)
        if (LxAgent.dataSender != null) {
            LxAgent.dataSender.addMetric(sb.toString());
        }
    }
}
