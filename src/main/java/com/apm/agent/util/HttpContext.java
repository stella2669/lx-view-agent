package com.apm.agent.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * ThreadLocal을 사용하여 하나의 HTTP 요청 스레드 내에서 정보를 공유합니다.
 * txId, requestUrl, httpMethod 등의 정보를 보관하며,
 * MethodInterceptor에서 에러 발생 시 이 정보를 참조하여 ERROR_DETAIL을 생성합니다.
 */
public class HttpContext {

    // DateTimeFormatter는 thread-safe하여 static final로 캐싱 가능
    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern(Constants.DATE_FORMAT_ID);

    private static final ThreadLocal<Context> threadLocal = new ThreadLocal<>();

    // 비-HTTP 환경(배치, 스케줄러 등)에서 동일 스레드 내 메서드 호출을 하나의 트랜잭션으로 묶기 위한 상태
    private static final ThreadLocal<String> nonHttpTxId = new ThreadLocal<>();
    private static final ThreadLocal<Integer> nonHttpCallDepth = new ThreadLocal<>();

    public static class Context {
        public String txId;
        public String url;
        public String method;
        public String queryParams;
        public String threadName;
        public long startTime;

        public Context() {
            this.txId = generateId(Constants.PREFIX_HTTP);
            this.threadName = Thread.currentThread().getName();
            this.startTime = System.currentTimeMillis();
        }
    }

    /**
     * 접두어와 타임스탬프, UUID를 조합하여 고유 ID를 생성합니다. (Common Logic)
     */
    public static String generateId(String prefix) {
        String timestamp = LocalDateTime.now().format(ID_FORMATTER);
        return prefix + "-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static void init() {
        threadLocal.set(new Context());
    }

    public static Context get() {
        return threadLocal.get();
    }

    public static String getTxId() {
        Context ctx = threadLocal.get();
        return (ctx != null) ? ctx.txId : null;
    }

    public static void setHttpInfo(String url, String method, String queryParams) {
        Context ctx = threadLocal.get();
        if (ctx == null) {
            ctx = new Context();
            threadLocal.set(ctx);
        }
        ctx.url = url;
        ctx.method = method;
        ctx.queryParams = queryParams;
    }

    public static void remove() {
        threadLocal.remove();
    }

    // ---------------------------------------------------------------
    // 비-HTTP 트랜잭션 그룹핑: 동일 스레드 내 중첩 메서드 호출을 하나의 txId로 묶음
    // ---------------------------------------------------------------

    /**
     * 비-HTTP 메서드 진입 시 호출. HTTP 컨텍스트가 있으면 아무것도 하지 않음.
     * 루트 진입(depth==0)이면 새 txId를 생성, 중첩 호출이면 depth만 증가.
     */
    public static void enterNonHttpCall() {
        if (threadLocal.get() != null) {
            return; // HTTP 요청 처리 중이면 스킵
        }
        Integer depth = nonHttpCallDepth.get();
        if (depth == null || depth == 0) {
            nonHttpTxId.set(generateId(Constants.PREFIX_NON_HTTP));
            nonHttpCallDepth.set(1);
        } else {
            nonHttpCallDepth.set(depth + 1);
        }
    }

    /**
     * 비-HTTP 메서드 종료 시 호출. depth가 0이 되면 txId를 정리.
     */
    public static void exitNonHttpCall() {
        if (threadLocal.get() != null) {
            return;
        }
        Integer depth = nonHttpCallDepth.get();
        if (depth != null) {
            if (depth <= 1) {
                nonHttpTxId.remove();
                nonHttpCallDepth.remove();
            } else {
                nonHttpCallDepth.set(depth - 1);
            }
        }
    }

    /**
     * 현재 스레드의 비-HTTP txId를 반환. 없으면 null.
     */
    public static String getNonHttpTxId() {
        return nonHttpTxId.get();
    }
}
