package com.apm.agent.reporter;

import com.apm.agent.util.Constants;
import com.apm.agent.util.Logger;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class AgentDataSender {

    private final String endpointUrl;
    private final String agentName;
    private final ArrayBlockingQueue<String> metricQueue;
    private final ScheduledExecutorService scheduler;
    private final int batchSize;

    public AgentDataSender(String endpointUrl, String agentName, int batchSize, int flushIntervalSeconds,
            int maxQueueSize) {
        this.endpointUrl = endpointUrl;
        this.agentName = agentName;
        this.batchSize = batchSize;
        this.metricQueue = new ArrayBlockingQueue<>(maxQueueSize);

        // 에이전트 전용 백그라운드 스레드 (데몬 스레드로 설정하여 타겟 애플리케이션 종료를 방해하지 않음)
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LxAgent-Sender-Thread");
                t.setDaemon(true);
                return t;
            }
        });

        // 주기적으로 큐를 비워 배치 전송
        this.scheduler.scheduleAtFixedRate(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 측정된 메트릭 데이터를 큐에 적재합니다. (Non-blocking)
     */
    public void addMetric(String metricJson) {
        // [Defensive] 무한정 메모리에 쌓이는 현상(OOM)을 방지하는 Backpressure 구현 (O(1) Enqueue)
        // ArrayBlockingQueue의 offer()는 가득 찼을 경우 false를 반환하고 즉시 드롭(Drop)하므로 성능에 영향을 주지
        // 않음
        metricQueue.offer(metricJson);
    }

    private void flush() {
        if (metricQueue.isEmpty()) {
            return;
        }

        List<String> batch = new ArrayList<>(batchSize);
        String metric;
        // 지정된 배치 사이즈만큼 큐에서 빼냄
        while ((metric = metricQueue.poll()) != null && batch.size() < batchSize) {
            batch.add(metric);
        }

        if (!batch.isEmpty()) {
            sendBatch(batch);
        }
    }

    private void sendBatch(List<String> batch) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", Constants.CONTENT_TYPE_JSON);
            conn.setRequestProperty(Constants.AGENT_KEY_HEADER, Constants.AGENT_KEY);
            conn.setDoOutput(true);

            // [Defensive] HTTP 통신 지연이 길어질 경우를 대비한 타임아웃 강제 설정
            conn.setConnectTimeout(Constants.DEFAULT_CONNECT_TIMEOUT);
            conn.setReadTimeout(Constants.DEFAULT_READ_TIMEOUT);

            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("[");
            for (int i = 0; i < batch.size(); i++) {
                // 기존 metricJson (예: {"type":"HTTP","responseTime":120}) 끝에 agentName 필드를 동적 삽입
                String metricJson = batch.get(i);

                // [Defensive] JSON 끝의 닫는 괄호 '}' 를 찾아서 그 앞에 agentName 필드 삽입
                int lastBraceIndex = metricJson.lastIndexOf('}');
                if (lastBraceIndex != -1) {
                    StringBuilder metricBuilder = new StringBuilder(metricJson);
                    // 메트릭 내부에 이미 데이터가 있으므로 ',' 로 연결
                    // 예: {"type":"HTTP" -> {"type":"HTTP","agentName":"Payment-Agent"}
                    metricBuilder.insert(lastBraceIndex, ",\"agentName\":\"" + escapeJson(this.agentName) + "\"");
                    jsonBuilder.append(metricBuilder.toString());
                } else {
                    // 비정상적인 JSON 형태라면 원형 그대로 전송 (방어)
                    jsonBuilder.append(metricJson);
                }

                if (i < batch.size() - 1) {
                    jsonBuilder.append(",");
                }
            }
            jsonBuilder.append("]");

            byte[] out = jsonBuilder.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", Integer.toString(out.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_CREATED) {
                Logger.error("Failed to send metrics. HTTP Code: " + responseCode);
            }

        } catch (Exception e) {
            // [Defensive] 통신 에러나 예외가 발생하더라도 어플리케이션(사용자 환경)으로 예외를 전파하지 않음(Swallow Exception)
            Logger.error("Error sending metrics", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 라이브러리 없이 문자열 데이터를 JSON 값으로 감쌀 때 안전하게 특수 문자를 이스케이프 처리
     */
    public static String escapeJson(String s) {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch >= '\u0000' && ch <= '\u001F') {
                        String ss = Integer.toHexString(ch);
                        sb.append("\\u");
                        for (int k = 0; k < 4 - ss.length(); k++) {
                            sb.append('0');
                        }
                        sb.append(ss.toUpperCase());
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    // JVM 셧다운 시 자원 정리
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
