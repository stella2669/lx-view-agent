# Lx-View Agent (Java Agent) 가이드

이 문서는 `lx-view-agent` 프로젝트를 빌드하고, 애플리케이션에 모니터링 에이전트로 부착하여 구동하는 방법을 설명합니다.

## 1. 빌드 방법 (Build)

본 에이전트는 대상 애플리케이션 클래스와의 의존성 충돌을 피하기 위해, 필요한 라이브러리(ByteBuddy 등)를 내부로 포함하고 패키지를 격리한 **Shadow Jar** 형태로 빌드됩니다.

### 기본 빌드
명령 프롬프트 또는 터미널에서 `lx-view-agent` 디렉토리로 이동 후, 다음 명령어를 실행합니다.

```bash
# Windows (cmd, powershell)
.\gradlew.bat clean shadowJar

# Mac/Linux (bash, zsh)
./gradlew clean shadowJar
```

### 선택적 파라미터 빌드 (Agent Name 지정)
`build.gradle`에서 커스텀 프로퍼티를 받아 `Manifest`의 `Agent-Name` 속성에 주입할 수 있도록 설계되어 있습니다. 파라미터 추가 시 아래와 같이 실행합니다.

```bash
gradle clean shadowJar -PagentName="My-Custom-Agent"
```

**빌드 결과물 위치:**
명령어가 성공적으로 완료되면 아래 경로에 `jar` 파일이 생성됩니다.
> `build/libs/lx-view-agent-1.0.0.jar`

---

## 2. 구동 방법 (Run)

완성된 Agent Jar 파일을 대상 애플리케이션(모니터링 대상 타겟)의 시작 옵션(JVM Argument)으로 추가하여 함께 구동합니다.

### 실행 옵션 기본 규칙

```bash
java -javaagent:<Agent-Jar-절대경로>[=에이전트인수] -jar <대상-애플리케이션-Jar>
```

🚨 **주의:** `-javaagent` 옵션은 반드시 애플리케이션 진입점(`-jar` 또는 메인 클래스명)보다 **앞에** 위치해야 합니다!

### 실행 예시

#### 1) 기본 실행
```bash
java -javaagent:C:\workspace\application\lx-view-agent\build\libs\lx-view-agent-1.0.0.jar -jar target-app.jar
```

#### 2) JVM 메모리 설정과 함께 실행
```bash
java -Xms512m -Xmx1024m -javaagent:C:\workspace\application\lx-view-agent\build\libs\lx-view-agent-1.0.0.jar -jar target-app.jar
```

#### 3) 에이전트 인수를 파라미터로 넘기며 실행 (AgentArgs)
만약 `premain` 메서드에서 사용할 별도의 런타임 인수가 있다면, jar 파일 경로 뒤에 `=` 기호를 붙여 문자열 형태로 전달할 수 있습니다. (예: `config=server.conf`)
```bash
java -javaagent:C:\workspace\application\lx-view-agent\build\libs\lx-view-agent-1.0.0.jar=config=agent.conf,mode=debug -jar target-app.jar
```

---

## 3. 체크리스트 및 주의사항

* **Java 버전 호환성:** 이 에이전트는 호환성을 위해 **Java 8** (`sourceCompatibility 1.8`)로 고정하여 컴파일되었습니다. 
* **자기 참조 방지:** `LxAgent.java` 내에서 무한루프(StackOverflow)를 막기 위해 에이전트 자신(`com.apm.agent패키지`)은 수정(Transform)하지 않도록 Defensive Coding이 적용되어 있습니다.
* **인코딩 문제:** Windows 환경에서 빌드 시 `unmappable character` 에러를 방지하기 위해 `JavaCompile` 설정에 `UTF-8` 인코딩이 강제로 적용되었습니다.
