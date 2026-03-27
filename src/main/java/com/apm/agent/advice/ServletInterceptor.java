package com.apm.agent.advice;

import net.bytebuddy.asm.Advice;
import com.apm.agent.util.HttpContext;

import java.lang.reflect.Method;

/**
 * javax.servlet.Filter.doFilter 메서드를 가로채서 HTTP 요청 정보를 HttpContext에 저장합니다.
 * 컴파일 타임에 servlet-api 의존성을 갖지 않기 위해 리플렉션을 사용합니다.
 */
public class ServletInterceptor {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object request) {
        try {
            // HttpServletRequest인지 확인 (리플렉션 사용)
            Class<?> httpReqClass = request.getClass();
            
            // getRequestURI, getMethod, getQueryString 메서드 추출
            Method getRequestURIMethod = findMethod(httpReqClass, "getRequestURI");
            Method getMethodMethod = findMethod(httpReqClass, "getMethod");
            Method getQueryStringMethod = findMethod(httpReqClass, "getQueryString");

            if (getRequestURIMethod != null && getMethodMethod != null) {
                String uri = (String) getRequestURIMethod.invoke(request);
                String method = (String) getMethodMethod.invoke(request);
                String queryString = (getQueryStringMethod != null) ? (String) getQueryStringMethod.invoke(request) : null;

                // HttpContext에 저장
                HttpContext.setHttpInfo(uri, method, queryString);
            }
        } catch (Exception e) {
            // 에러 발생 시 로그만 남기고 대상 앱 로직은 방해하지 않음
            // Logger.debug("Failed to capture Servlet info: " + e.getMessage());
        }
    }

    @Advice.OnMethodExit
    public static void onExit() {
        // 요청 처리가 끝나면 ThreadLocal 정리
        HttpContext.remove();
    }

    public static Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            // 부모 클래스/인터페이스에서 찾아야 할 수도 있음
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
