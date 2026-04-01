package com.apm.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;
import com.apm.agent.util.Logger;
import com.apm.agent.util.ConfigLoader;
import com.apm.agent.util.Constants;
import com.apm.agent.reporter.AgentDataSender;
import com.apm.agent.reporter.JvmMetricCollector;

import java.lang.instrument.Instrumentation;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.net.URL;

public class LxAgent {

    // 전역으로 접근 가능하도록 Sender 인스턴스 보관 (Advice 클래스 등에서 참고)
    public static AgentDataSender dataSender;

    // JVM 수집기 전역 보관
    public static JvmMetricCollector jvmCollector;

    // 최소 수집 시간 임계치 (Threshold)
    public static int minDurationMs = Constants.DEFAULT_MIN_DURATION_MS;

    /**
     * JVM 시작 시(Pre-main) 호출되는 에이전트 진입점
     * 
     * @param agentArgs 에이전트 인수
     * @param inst      Instrumentation 객체
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        String agentName = resolveAgentName(agentArgs);
        readLogConfigFromArgs(agentArgs); // agentArgs에서도 로깅 설정 처리 가능하도록 추가

        Logger.info("Starting APM Agent (" + agentName + ") ...");

        // 1. 프로퍼티 설정 파일 로드
        // -Dlx.agent.config=/path/to/lx-agent.properties 로 파일 경로 전달
        String configFilePath = getOptionValue(agentArgs, "config", System.getProperty("lx.agent.config"));
        ConfigLoader.load(configFilePath);

        // 1-1. 파일 로깅 초기화 (설정 파일 로드 이후에 실행해야 lx.agent.log.dir 값을 읽을 수 있음)
        // lx.agent.log.dir 미설정 시 콘솔 전용 모드 유지
        String logDir = ConfigLoader.getProperty("lx.agent.log.dir", null);
        Logger.initFileLogging(logDir, agentName);

        // 2. DataSender 초기화
        String endpointUrl = ConfigLoader.getProperty("lx.agent.server.url", Constants.DEFAULT_SERVER_URL);
        int batchSize = ConfigLoader.getIntProperty("lx.agent.batch.size", Constants.DEFAULT_BATCH_SIZE);
        int flushInterval = ConfigLoader.getIntProperty("lx.agent.flush.interval", Constants.DEFAULT_FLUSH_INTERVAL_SECONDS);
        int maxQueueSize = ConfigLoader.getIntProperty("lx.agent.queue.size", Constants.DEFAULT_MAX_QUEUE_SIZE);
        minDurationMs = ConfigLoader.getIntProperty("lx.agent.min.duration.ms", Constants.DEFAULT_MIN_DURATION_MS);

        dataSender = new AgentDataSender(endpointUrl, agentName, batchSize, flushInterval, maxQueueSize);
        Logger.info("AgentDataSender initialized. Endpoint: " + endpointUrl);

        // 2-1. JVM Metric Collector 초기화
        int jvmInterval = ConfigLoader.getIntProperty("lx.agent.jvm.interval", Constants.DEFAULT_JVM_METRIC_INTERVAL_SECONDS);
        jvmCollector = new JvmMetricCollector(dataSender, jvmInterval);
        Logger.info("JvmMetricCollector initialized. Interval: " + jvmInterval + "s");

        // 3. 대상 패키지 설정 (기본적으로 모두 잡되, 불필요한 시스템 패키지 제외)
        // properties 파일에서 특정 패키지만 모니터링할 수도 있도록 확장 가능 (lx.agent.target.package)
        String targetPackage = ConfigLoader.getProperty("lx.agent.target.package", "");

        new AgentBuilder.Default()
                // 5. 자기 참조 방지 및 시스템/프레임워크 내부 클래스 무시 (Defensive):
                // Bootstrap ClassLoader 대상(com.sun 등)을 잘못 참조하여 발생하는 NoClassDefFoundError 차단
                // 불필요한 프레임워크 타겟팅으로 인한 성능 저하(OOM, 기동 속도 지연) 원천 차단
                .ignore(buildIgnoreMatcher())
                // 타겟 패키지가 명시되어 있으면 해당 패키지만, 아니면 내장/자기자신을 제외한 모든 패키지(any) 적용
                .type(targetPackage.isEmpty() ? ElementMatchers.any() : ElementMatchers.nameStartsWith(targetPackage))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (Logger.isDebugEnabled()) {
                        Logger.debug("Transforming class: " + typeDescription.getName());
                    }
                    // 모든 메서드에 MethodInterceptor 부착하되, 의미 없는 Getter/Setter/Builder 등은 원천 차단
                    return builder.visit(net.bytebuddy.asm.Advice.to(com.apm.agent.advice.MethodInterceptor.class)
                            .on(ElementMatchers.isMethod()
                                    .and(ElementMatchers.not(ElementMatchers.isAbstract())) // 추상 메서드 제외
                                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("get")))
                                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("set")))
                                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("is")))
                                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("build")))
                                    .and(ElementMatchers.not(ElementMatchers.nameContains("$"))) // 람다/익명클래스 내부 메서드 노이즈
                                                                                                 // 제거
                    ));
                })
                .installOn(inst);

        // 4. HTTP 요청 정보를 캡처하기 위한 Servlet Filter 인터셉터 설치
        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("com.apm.agent")) // 에이전트 자신은 제외
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("javax.servlet.Filter"))
                        .or(ElementMatchers.hasSuperType(ElementMatchers.named("jakarta.servlet.Filter"))))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (Logger.isDebugEnabled()) {
                        Logger.debug("Installing ServletInterceptor on: " + typeDescription.getName());
                    }
                    return builder.visit(net.bytebuddy.asm.Advice.to(com.apm.agent.advice.ServletInterceptor.class)
                            .on(ElementMatchers.named("doFilter")));
                })
                .installOn(inst);

        // 4-1. JDBC 수집을 위한 인터셉터 설치
        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("com.apm.agent"))
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Connection")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (Logger.isDebugEnabled()) {
                        Logger.debug("Installing JdbcInterceptor (Prepare) on: " + typeDescription.getName());
                    }
                    return builder.visit(net.bytebuddy.asm.Advice.to(com.apm.agent.advice.JdbcInterceptor.PrepareAdvice.class)
                            .on(ElementMatchers.nameStartsWith("prepare")));
                })
                .installOn(inst);

        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("com.apm.agent"))
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.PreparedStatement")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (Logger.isDebugEnabled()) {
                        Logger.debug("Installing JdbcInterceptor (PreparedStatement Execute) on: " + typeDescription.getName());
                    }
                    return builder.visit(net.bytebuddy.asm.Advice.to(com.apm.agent.advice.JdbcInterceptor.PreparedStatementExecuteAdvice.class)
                            .on(ElementMatchers.nameStartsWith("execute")));
                })
                .installOn(inst);

        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("com.apm.agent"))
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Statement"))
                        .and(ElementMatchers.not(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.PreparedStatement")))))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (Logger.isDebugEnabled()) {
                        Logger.debug("Installing JdbcInterceptor (Statement Execute) on: " + typeDescription.getName());
                    }
                    return builder.visit(net.bytebuddy.asm.Advice.to(com.apm.agent.advice.JdbcInterceptor.StatementExecuteAdvice.class)
                            .on(ElementMatchers.nameStartsWith("execute")));
                })
                .installOn(inst);

        // JVM 종료 시 안전하게 버퍼 비우기 (Graceful Shutdown)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.info("Shutting down APM Agent...");
            if (jvmCollector != null) {
                jvmCollector.shutdown();
            }
            if (dataSender != null) {
                dataSender.shutdown();
            }
            // 파일 로거 핸들 안전 종료 (가장 마지막에 닫아야 위 shutdown 로그도 파일에 기록됨)
            Logger.close();
        }));
    }

    /**
     * agentArgs에서 특정 key의 값을 추출. (sysFallback 값이 존재하면 우선시함 등)
     */
    private static String getOptionValue(String agentArgs, String key, String sysFallback) {
        if (sysFallback != null && !sysFallback.trim().isEmpty()) {
            return sysFallback;
        }
        if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String argPair : agentArgs.split(",")) {
                String[] kv = argPair.split("=");
                if (kv.length == 2 && key.equalsIgnoreCase(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return null;
    }

    /**
     * agentArgs에서 mode=debug 등 로깅 레벨 관련 파라미터가 들어올 경우 처리
     */
    private static void readLogConfigFromArgs(String agentArgs) {
        if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String argPair : agentArgs.split(",")) {
                String[] kv = argPair.split("=");
                if (kv.length == 2 && "mode".equalsIgnoreCase(kv[0].trim())) {
                    if ("debug".equalsIgnoreCase(kv[1].trim())) {
                        Logger.setLevel(Logger.Level.DEBUG);
                        Logger.debug("Agent execution mode set to DEBUG from arguments.");
                    }
                }
            }
        }
    }

    /**
     * 프레임워크 및 시스템 내장 클래스 등
     * 바이트코드 조작이 불필요하거나 하면 안 되는 패키지 규칙을 정의합니다.
     */
    private static net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> buildIgnoreMatcher() {
        String[] ignorePrefixes = {
                "com.apm.agent", "java.", "sun.", "com.sun.", "jdk.",
                "org.springframework.", "org.apache.", "org.slf4j.", "org.xml.",
                "org.hibernate.", "org.jboss.logging.", "org.aspectj.", "ch.qos.logback.",
                "io.netty.", "io.undertow.", "io.micrometer.", "io.grpc.", "io.lettuce.",
                "com.zaxxer.hikari.", "com.fasterxml.jackson.", "reactor."
        };

        net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> matcher = ElementMatchers
                .none();
        for (String prefix : ignorePrefixes) {
            matcher = matcher.or(ElementMatchers.nameStartsWith(prefix));
        }

        return matcher
                .or(ElementMatchers.nameContains("CGLIB"))
                .or(ElementMatchers.nameContains("javassist"));
    }

    /**
     * JVM 구동 시 동적으로 부여된 에이전트 이름을 우선적으로 해석합니다.
     * 1순위: -Dlx.agent.name=MyAgent
     * 2순위: -javaagent:agent.jar=name=MyAgent
     * 3순위: 빌드 시 Manifest에 주입된 Agent-Name
     */
    private static String resolveAgentName(String agentArgs) {
        try {
            // 1. System Property (가장 높은 우선순위)
            String sysName = System.getProperty("lx.agent.name");
            if (sysName != null && !sysName.trim().isEmpty()) {
                return sysName;
            }

            // 2. Agent Arguments 파싱 (-javaagent:lx.jar=name=MyApp,mode=dev)
            if (agentArgs != null && !agentArgs.isEmpty()) {
                for (String argPair : agentArgs.split(",")) {
                    String[] kv = argPair.split("=");
                    if (kv.length == 2 && "name".equalsIgnoreCase(kv[0].trim())) {
                        return kv[1].trim();
                    }
                }
            }

            // 3. Fallback: Manifest에서 읽어오기
            URL metaInfUrl = LxAgent.class.getProtectionDomain().getCodeSource().getLocation();
            URL manifestUrl = new URL("jar:" + metaInfUrl.toExternalForm() + "!/META-INF/MANIFEST.MF");
            Manifest manifest = new Manifest(manifestUrl.openStream());
            Attributes attributes = manifest.getMainAttributes();

            String name = attributes.getValue("Agent-Name");
            return (name != null && !name.trim().isEmpty()) ? name : "Unknown-Agent";
        } catch (Exception e) {
            Logger.error("Failed to read Agent-Name from Manifest. Using default.");
            return "Unknown-Agent"; // 에러 발생 시 기본값 반환 (Defensive)
        }
    }
}
