package com.apm.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.net.URL;

public class LxAgent {

    /**
     * JVM 시작 시(Pre-main) 호출되는 에이전트 진입점
     * 
     * @param agentArgs 에이전트 인수
     * @param inst      Instrumentation 객체
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        String agentName = resolveAgentName();
        System.out.println("[LxAgent] Starting APM Agent (" + agentName + ") ...");

        new AgentBuilder.Default()
                // 5. 자기 참조 방지 (Defensive):
                // 에이전트가 자신(com.apm.agent)의 클래스나 강제 재배치(Shadowing)된 bytebuddy 클래스
                // (relocate 설정으로 패키지가 com.apm.agent.shadow.bytebuddy로 바뀜)
                // 를 인터셉트하여 발생하는 무한 루프(StackOverflow) 원천 차단.
                // 또한 java.* 등 시스템 핵심 패키지를 무시하여 불필요한 성능 저하 방지
                .ignore(ElementMatchers.nameStartsWith("com.apm.agent")
                        .or(ElementMatchers.nameStartsWith("java."))
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("jdk.")))
                .type(ElementMatchers.any()) // TODO: 실제 타겟팅할 애플리케이션 클래스로 한정해야 함
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    // TODO: 바이트코드 조작 로직 (Advice) 주입
                    return builder;
                })
                .installOn(inst);
    }

    /**
     * 현재 실행 중인 에이전트 Jar 파일의 Manifest에서 'Agent-Name' 속성을 읽어옵니다.
     */
    private static String resolveAgentName() {
        try {
            // LxAgent 클래스를 로드한 클래스로더에서 리소스 획득
            URL metaInfUrl = LxAgent.class.getProtectionDomain().getCodeSource().getLocation();
            URL manifestUrl = new URL("jar:" + metaInfUrl.toExternalForm() + "!/META-INF/MANIFEST.MF");
            Manifest manifest = new Manifest(manifestUrl.openStream());
            Attributes attributes = manifest.getMainAttributes();

            String name = attributes.getValue("Agent-Name");
            return (name != null && !name.trim().isEmpty()) ? name : "Unknown-Agent";
        } catch (Exception e) {
            System.err.println("[LxAgent] Failed to read Agent-Name from Manifest. Using default.");
            return "Unknown-Agent"; // 에러 발생 시 기본값 반환 (Defensive)
        }
    }
}
