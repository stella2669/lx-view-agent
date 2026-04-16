package com.apm.agent.advice;

import com.apm.agent.LxAgent;
import com.apm.agent.reporter.AgentDataSender;
import com.apm.agent.util.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 슬로우 쿼리 메트릭이 lx-view로 올바르게 전송되는지 검증하는 통합 테스트.
 *
 * 구조:
 *   JdbcInterceptor.checkAndSendSqlMetric()
 *     → AgentDataSender 큐 적재
 *       → flush (배치 전송)
 *         → MockHttpServer (lx-view 역할) 수신 확인
 *
 * 테스트 케이스:
 *   1. 슬로우 쿼리 → 전송됨
 *   2. 정상(빠른) 쿼리 → 전송 안 됨
 *   3. SQL 에러 → 임계치 무관하게 항상 전송됨
 *   4. SQL null 에러 → "(unknown)"으로 대체하여 전송됨
 *   5. 비-HTTP 환경에서 txId가 MethodInterceptor와 일치함
 *   6. 슬로우 쿼리 페이로드 필드 검증 (type, sql, responseTimeMs, txId, isError)
 */
public class SlowQueryIntegrationTest {

    // lx-view 역할을 하는 로컬 HTTP 서버
    private HttpServer mockServer;

    // 수신된 요청 바디를 순서대로 기록
    private final List<String> receivedBodies = new ArrayList<>();

    // 테스트 전용 설정값
    private static final int  SLOW_QUERY_THRESHOLD_MS = 200;
    private static final int  FLUSH_INTERVAL_SECONDS  = 1;
    private static final long WAIT_FOR_FLUSH_MS        = 1500; // flush 완료 대기

    @Before
    public void setUp() throws IOException {
        receivedBodies.clear();

        // 사용 가능한 포트에 Mock HTTP 서버 기동
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/api/metrics", this::handleMetrics);
        mockServer.start();

        int port = mockServer.getAddress().getPort();
        String endpointUrl = "http://localhost:" + port + "/api/metrics";

        // AgentDataSender를 Mock 서버로 초기화
        LxAgent.dataSender = new AgentDataSender(
                endpointUrl,
                "test-agent",
                "test-key",
                50,                     // batchSize
                FLUSH_INTERVAL_SECONDS, // flushInterval
                1000                    // maxQueueSize
        );
        LxAgent.slowQueryThresholdMs = SLOW_QUERY_THRESHOLD_MS;
    }

    @After
    public void tearDown() {
        if (LxAgent.dataSender != null) {
            LxAgent.dataSender.shutdown();
            LxAgent.dataSender = null;
        }
        if (mockServer != null) {
            mockServer.stop(0);
        }
        HttpContext.remove();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 테스트 케이스
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void 슬로우_쿼리는_lx_view로_전송된다() throws InterruptedException {
        String sql = "SELECT * FROM orders WHERE created_at < '2024-01-01'";
        long slowDuration = SLOW_QUERY_THRESHOLD_MS + 100; // 임계치 초과

        JdbcInterceptor.checkAndSendSqlMetric(sql, slowDuration, null);
        waitForFlush();

        assertEquals("슬로우 쿼리 1건이 전송되어야 한다", 1, receivedBodies.size());

        String body = receivedBodies.get(0);
        assertContains(body, "\"type\":\"SQL\"");
        assertContains(body, "\"isError\":false");
        assertContains(body, "\"responseTimeMs\":" + slowDuration);
        assertContains(body, "SELECT * FROM orders");
        assertContains(body, "\"txId\":\"");
        assertContains(body, "\"agentName\":\"test-agent\"");
    }

    @Test
    public void 임계치_미만_정상_쿼리는_전송되지_않는다() throws InterruptedException {
        String sql = "SELECT 1";
        long fastDuration = SLOW_QUERY_THRESHOLD_MS - 50; // 임계치 미만

        JdbcInterceptor.checkAndSendSqlMetric(sql, fastDuration, null);
        waitForFlush();

        assertTrue("빠른 쿼리는 전송되지 않아야 한다", receivedBodies.isEmpty());
    }

    @Test
    public void SQL_에러는_임계치_무관하게_항상_전송된다() throws InterruptedException {
        String sql = "SELECT * FROM nonexistent_table";
        long fastDuration = 10; // 임계치보다 훨씬 짧아도
        Throwable error = new RuntimeException("Table 'db.nonexistent_table' doesn't exist");

        JdbcInterceptor.checkAndSendSqlMetric(sql, fastDuration, error);
        waitForFlush();

        assertEquals("SQL 에러 1건이 전송되어야 한다", 1, receivedBodies.size());

        String body = receivedBodies.get(0);
        assertContains(body, "\"isError\":true");
        assertContains(body, "\"exceptionName\":\"java.lang.RuntimeException\"");
        assertContains(body, "Table 'db.nonexistent_table' doesn't exist");
    }

    @Test
    public void SQL이_null인_에러는_unknown으로_대체하여_전송된다() throws InterruptedException {
        // PrepareAdvice가 실패하여 SqlContextMap에 SQL이 없는 경우
        Throwable error = new RuntimeException("Connection reset");

        JdbcInterceptor.checkAndSendSqlMetric(null, 50, error);
        waitForFlush();

        assertEquals("SQL null 에러도 전송되어야 한다", 1, receivedBodies.size());

        String body = receivedBodies.get(0);
        assertContains(body, "\"sql\":\"(unknown)\"");
        assertContains(body, "\"isError\":true");
    }

    @Test
    public void HTTP_컨텍스트에서_SQL_메트릭의_txId가_요청_txId와_일치한다() throws InterruptedException {
        // HTTP 요청 컨텍스트 시뮬레이션
        HttpContext.setHttpInfo("/api/orders", "GET", null);
        String expectedTxId = HttpContext.get().txId;

        String sql = "SELECT * FROM orders";
        JdbcInterceptor.checkAndSendSqlMetric(sql, SLOW_QUERY_THRESHOLD_MS + 50, null);
        waitForFlush();

        assertEquals(1, receivedBodies.size());
        assertContains(receivedBodies.get(0), "\"txId\":\"" + expectedTxId + "\"");
    }

    @Test
    public void 비HTTP_환경에서_SQL_txId가_MethodInterceptor_txId와_일치한다() throws InterruptedException {
        // 비-HTTP 환경: MethodInterceptor.onEnter()가 enterNonHttpCall()을 호출한 상태 시뮬레이션
        HttpContext.enterNonHttpCall();
        String sharedTxId = HttpContext.getNonHttpTxId();
        assertNotNull("비-HTTP txId가 생성되어야 한다", sharedTxId);

        String sql = "UPDATE batch_jobs SET status = 'done'";
        JdbcInterceptor.checkAndSendSqlMetric(sql, SLOW_QUERY_THRESHOLD_MS + 50, null);
        waitForFlush();

        assertEquals(1, receivedBodies.size());
        assertContains(
                receivedBodies.get(0),
                "\"txId\":\"" + sharedTxId + "\"",
                "비-HTTP SQL 메트릭의 txId가 MethodInterceptor 공유 txId와 일치해야 한다"
        );

        HttpContext.exitNonHttpCall();
    }

    @Test
    public void 슬로우_쿼리_페이로드에_필수_필드가_모두_포함된다() throws InterruptedException {
        String sql = "SELECT u.id, u.name FROM users u JOIN orders o ON u.id = o.user_id";
        long duration = SLOW_QUERY_THRESHOLD_MS + 300;

        JdbcInterceptor.checkAndSendSqlMetric(sql, duration, null);
        waitForFlush();

        assertEquals(1, receivedBodies.size());
        String body = receivedBodies.get(0);

        // 필수 필드 전체 검증
        assertContains(body, "\"type\":\"SQL\"");
        assertContains(body, "\"txId\":\"");
        assertContains(body, "\"timestamp\":");
        assertContains(body, "\"responseTimeMs\":" + duration);
        assertContains(body, "\"sql\":\"");
        assertContains(body, "\"isError\":false");
        assertContains(body, "\"agentName\":\"test-agent\"");

        // 에러 필드는 없어야 함
        assertFalse("슬로우 쿼리에 exceptionName 필드가 없어야 한다", body.contains("exceptionName"));
        assertFalse("슬로우 쿼리에 error 필드가 없어야 한다", body.contains("\"error\""));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    /** Mock 서버: POST /api/metrics 수신 핸들러 */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            synchronized (receivedBodies) {
                receivedBodies.add(body);
            }
        }
        exchange.sendResponseHeaders(200, 0);
        exchange.close();
    }

    /** AgentDataSender의 flush 주기(1초)를 기다립니다. */
    private void waitForFlush() throws InterruptedException {
        Thread.sleep(WAIT_FOR_FLUSH_MS);
    }

    private void assertContains(String body, String expected) {
        assertTrue("페이로드에 [" + expected + "] 가 포함되어야 합니다.\n실제 페이로드: " + body,
                body.contains(expected));
    }

    private void assertContains(String body, String expected, String message) {
        assertTrue(message + "\n실제 페이로드: " + body, body.contains(expected));
    }
}
