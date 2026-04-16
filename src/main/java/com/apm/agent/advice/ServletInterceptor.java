package com.apm.agent.advice;

import net.bytebuddy.asm.Advice;
import com.apm.agent.util.HttpContext;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * javax.servlet.Filter.doFilter 메서드를 가로채서 HTTP 요청 정보를 HttpContext에 저장합니다.
 * 컴파일 타임에 servlet-api 의존성을 갖지 않기 위해 리플렉션을 사용합니다.
 * Method 객체는 클래스별로 캐싱하여 매 요청마다 발생하는 리플렉션 오버헤드를 제거합니다.
 */
public class ServletInterceptor {

    // 요청 클래스별 [getRequestURI, getMethod, getQueryString] Method 캐시
    // 대부분 단일 구현체만 사용되므로 실질적으로 단 한 번만 조회됨
    public static final ConcurrentHashMap<Class<?>, Method[]> METHOD_CACHE = new ConcurrentHashMap<>();

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object request) {
        try {
            Class<?> reqClass = request.getClass();
            Method[] methods = METHOD_CACHE.computeIfAbsent(reqClass, ServletInterceptor::resolveRequestMethods);

            Method getRequestURI = methods[0];
            Method getMethod = methods[1];
            Method getQueryString = methods[2];

            if (getRequestURI != null && getMethod != null) {
                String uri = (String) getRequestURI.invoke(request);
                String method = (String) getMethod.invoke(request);
                String queryString = (getQueryString != null) ? (String) getQueryString.invoke(request) : null;
                HttpContext.setHttpInfo(uri, method, queryString);
            }
        } catch (Exception e) {
            // 에러 발생 시 대상 앱 로직은 방해하지 않음
        }
    }

    @Advice.OnMethodExit
    public static void onExit() {
        // 요청 처리가 끝나면 ThreadLocal 정리
        HttpContext.remove();
    }

    public static Method[] resolveRequestMethods(Class<?> clazz) {
        return new Method[]{
                findMethod(clazz, "getRequestURI"),
                findMethod(clazz, "getMethod"),
                findMethod(clazz, "getQueryString")
        };
    }

    public static Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    return current.getDeclaredMethod(name);
                } catch (NoSuchMethodException ex) {
                    current = current.getSuperclass();
                }
            }
        }
        return null;
    }
}
