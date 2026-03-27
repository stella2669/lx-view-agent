package com.apm.agent.util;

/**
 * ThreadLocal을 사용하여 하나의 HTTP 요청 스레드 내에서 정보를 공유합니다.
 * txId, requestUrl, httpMethod 등의 정보를 보관하며, 
 * MethodInterceptor에서 에러 발생 시 이 정보를 참조하여 ERROR_DETAIL을 생성합니다.
 */
public class HttpContext {

    private static final ThreadLocal<Context> threadLocal = new ThreadLocal<>();

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
        String timestamp = new java.text.SimpleDateFormat(Constants.DATE_FORMAT_ID).format(new java.util.Date());
        return prefix + "-" + timestamp + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
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
}
