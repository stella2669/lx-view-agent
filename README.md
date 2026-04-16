# lx-view-agent

> **Zero-code APM Java Agent** — 코드 수정 없이 JVM 애플리케이션에 부착하여 트랜잭션 및 JVM 메트릭을 실시간 수집합니다.

---

## 📌 Overview

`lx-view-agent`는 **Java Instrumentation API + ByteBuddy** 기반의 APM(Application Performance Monitoring) Java Agent입니다.

대상 애플리케이션의 **소스 코드를 수정하지 않고** `-javaagent` 옵션만으로 부착하며, 메서드 실행 시간(Transaction), JVM 리소스(Heap, GC, Thread) 등의 메트릭을 수집하여 수집 서버(lx-view)로 배치 전송합니다.

```
[Target App JVM]
      │
      ├─ premain() 진입
      │
      ├─ ByteBuddy → 바이트코드 변환 (Instrumentation)
      │       └─ MethodInterceptor (Advice) 삽입
      │
      ├─ AgentDataSender → 배치 큐 → HTTP POST → [lx-view Server]
      │
      └─ JvmMetricCollector → 주기적 JVM 지표 수집 → [lx-view Server]
```

---

## 🏗️ Architecture

```
src/main/java/com/apm/agent/
├── LxAgent.java                  # premain() 진입점, ByteBuddy 설치
├── advice/
│   ├── MethodInterceptor.java    # @Advice: 일반 메서드 실행 전후 후킹
│   ├── ServletInterceptor.java   # @Advice: HTTP 요청 정보 및 Transaction ID 캡처
│   └── JdbcInterceptor.java      # @Advice: SQL 쿼리 및 실행 시간 수집 (L1 캐시 포함)
├── reporter/
│   ├── AgentDataSender.java      # 배치 큐 + HTTP 비동기 전송
│   └── JvmMetricCollector.java   # JVM Heap/GC/Thread 주기 수집
└── util/
    ├── ConfigLoader.java         # .properties 파일 로더
    ├── HttpContext.java          # ThreadLocal 기반 HTTP 컨텍스트 보관 및 txId 생성
    ├── SqlContextMap.java        # PreparedStatement와 SQL 문자열 매핑 관리 (WeakHashMap 기반)
    ├── Constants.java            # 공통 상수 및 기본 설정값 정의
    └── Logger.java               # 경량 내장 로거 (파일/콘솔 선택 지원)
```

### 핵심 설계 원칙

| 원칙 | 내용 |
|------|------|
| **Shadow Jar** | ByteBuddy를 내부 패키지로 relocate하여 대상 앱과의 의존성 충돌 원천 차단 |
| **Defensive Ignore** | JDK, Spring, Hibernate 등 프레임워크 클래스는 Instrumentation 대상에서 제외 |
| **Batch Queue** | 메트릭을 즉시 전송하지 않고 큐에 누적 → 배치로 전송하여 네트워크 I/O 최소화 |
| **Graceful Shutdown** | JVM 종료 시 `ShutdownHook`으로 잔여 큐 flush 후 안전 종료 |
| **Java 8 호환** | `sourceCompatibility = 1.8` 고정으로 레거시 환경까지 광범위 지원 |
| **JDBC Monitoring** | `java.sql` 표준 인터페이스 가로채기로 특정 DB 벤더 독립적인 SQL 수집 |
| **L1 Deduplication** | 동일 쿼리의 폭주를 방지하기 위해 에이전트 내부에서 10초간 중복 전송 차단 (최대 500개) |

---

## ⚙️ Configuration

에이전트 옵션은 **우선순위에 따라** 아래 3가지 방식으로 설정합니다.

| 우선순위 | 방식 | 예시 |
|----------|------|------|
| **1순위** (최고) | JVM System Property | `-Dlx.agent.name=MyApp` |
| **2순위** | AgentArgs (jar 뒤 `=` 연결) | `-javaagent:agent.jar=name=MyApp` |
| **3순위** (최저) | 빌드 시 Manifest 주입 | `gradle shadowJar -PagentName=MyApp` |

### `lx-agent.properties` 설정 파일 (권장)

```properties
# 수집 서버 엔드포인트
lx.agent.server.url=http://localhost:8080/api/metrics

# 배치 전송 설정
lx.agent.batch.size=100          # 배치당 최대 이벤트 수
lx.agent.flush.interval=3        # 강제 flush 주기 (초)
lx.agent.queue.size=2000         # 최대 큐 크기

# JVM 메트릭 수집
lx.agent.jvm.interval=10         # JVM 지표 수집 주기 (초)

# 모니터링 대상 패키지 (미설정 시 전체 적용)
lx.agent.target.package=com.example.myapp

# 최소 기록 임계치: N ms 이상 소요된 메서드만 수집 (0 = 전체)
lx.agent.min.duration.ms=0

# 슬로우 쿼리 임계치: N ms 이상 소요된 SQL만 수집 (기본값: 1000)
lx.agent.slow.query.ms=1000

# API 인증 키: lx-view 서버의 X-LX-Agent-Key 헤더 값과 일치해야 함
lx.agent.key=your-secret-key-here

# 로그 레벨: INFO | DEBUG
lx.agent.log.level=INFO

# 에이전트 로그 파일 디렉토리 (미설정 시 콘솔 전용 출력)
# 설정하면 타겟 앱 로그와 분리되어 별도 파일로 기록됨
# 파일명: lx-agent-{agentName}-yyyy-MM-dd.log
lx.agent.log.dir=C:\workspace\application\webtics\agents\logs
```

#### 실전 설정 — `webtics-gs`

```properties
# C:\workspace\application\webtics\agents\lx-agent.properties
lx.agent.server.url=http://localhost:8080/api/metrics
lx.agent.target.package=com.llynx.webtics
lx.agent.log.level=INFO
lx.agent.log.dir=C:\workspace\application\webtics\agents\logs
lx.agent.batch.size=100
lx.agent.flush.interval=3
lx.agent.slow.query.ms=1000
lx.agent.key=lx-view-agent-secret-key-2026
```

---

## 🔨 빌드 (Build)

의존성 충돌 방지를 위해 **Shadow Jar** (Fat Jar + Relocation) 형태로 빌드합니다.

```bash
# Windows
.\gradlew.bat clean shadowJar

# Mac / Linux
./gradlew clean shadowJar
```

**빌드 결과물:**
```
build/libs/lx-view-agent-1.0.0.jar
```

#### Agent Name을 빌드 시 주입하는 경우

```bash
.\gradlew clean shadowJar -PagentName="webtcis-gs"
```

---

### 🔧 개발 자동화 워크플로우 (Antigravity 추천)

에이전트 수정 후 **빌드와 복사**를 한 번에 수행하려면 아래 워크플로우 명령어를 사용하세요.

- **명령어**: `/build-and-copy`
- **역할**: `shadowJar` 빌드 실행 후, 자동으로 `C:\workspace\application\webtics\agents\` 폴더에 복사하고 결과를 보고합니다.

---

## 🚀 실행 (Run)

### 기본 구동

```bash
java -javaagent:/path/to/lx-view-agent-1.0.0.jar -jar target-app.jar
```

> ⚠️ `-javaagent` 옵션은 반드시 `-jar` 또는 메인 클래스 앞에 위치해야 합니다.

### 외부 설정 파일 연동 (권장)

```bash
java \
  -Dlx.agent.name=Auth-Agent \
  -Dlx.agent.config=/path/to/lx-agent.properties \
  -javaagent:/path/to/lx-view-agent-1.0.0.jar \
  -jar target-app.jar
```

### JVM 메모리 옵션 병행

```bash
java -Xms512m -Xmx1024m \
  -Dlx.agent.name=MyApp \
  -javaagent:/path/to/lx-view-agent-1.0.0.jar \
  -jar target-app.jar
```

### AgentArgs로 런타임 파라미터 전달

```bash
java -javaagent:/path/to/lx-view-agent-1.0.0.jar=name=MyApp,mode=debug \
  -jar target-app.jar
```

### Maven Spring Boot 환경 (`mvn spring-boot:run`)

`-Dspring-boot.run.jvmArguments`에 에이전트 옵션을 묶어서 전달합니다.

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="\
    -Dlx.agent.name=my-service \
    -Dlx.agent.config=/path/to/lx-agent.properties \
    -javaagent:/path/to/lx-view-agent-1.0.0.jar"
```

#### 실전 예시 — `webtics-gs` 구동

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dlx.agent.name=webtics-gs -Dlx.agent.config=C:\workspace\application\webtics\agents\lx-agent.properties -javaagent:C:\workspace\application\webtics\agents\lx-view-agent-1.0.0.jar"
```

---

## 📦 수집 데이터 형식

에이전트는 세 가지 타입의 페이로드를 수집 서버로 전송합니다.

### TRANSACTION (메서드 실행 정보)

```json
{
  "type": "TRANSACTION",
  "agentName": "Auth-Agent",
  "className": "com.example.UserService",
  "methodName": "login",
  "durationMs": 42,
  "timestamp": 1710000000000,
  "status": "SUCCESS"
}
```

### JVM (리소스 정보)

```json
{
  "type": "JVM",
  "agentName": "Auth-Agent",
  "heapUsed": 134217728,
  "heapMax": 536870912,
  "gcCount": 12,
  "gcTime": 340,
  "threadCount": 48,
  "timestamp": 1710000000000
}
```

### SQL (DB 쿼리 실행 정보)

슬로우 쿼리(`lx.agent.slow.query.ms`) 또는 SQL 에러 발생 시 전송됩니다.

```json
{
  "type": "SQL",
  "txId": "REQ-20240410-163612-421",
  "timestamp": 1710000000000,
  "responseTimeMs": 15,
  "sql": "SELECT * FROM users WHERE id = ?",
  "isError": false
}
```

> [!TIP]
> **SQL L1 Cache**: 동일한 정규화 SQL에 대해 **10초(10,000ms)** 내에는 한 번만 전송합니다. 캐시는 최대 **500개**까지 유지되며, 초과 시 가장 오래된 항목부터 정리됩니다. 단, 에러가 발생한 쿼리는 캐시를 무시하고 즉시 전송합니다.

---

## 🛡️ 주의사항 (Checklist)

- **Java 버전:** Java 8 이상 지원 (`sourceCompatibility = 1.8` 컴파일)
- **자기 참조 방지:** `com.apm.agent` 패키지 자신은 Instrumentation 대상에서 자동 제외 (무한루프 방지)
- **인코딩:** Windows 빌드 환경에서 `unmappable character` 에러 방지를 위해 `JavaCompile` 인코딩 UTF-8 강제 적용
- **Getter/Setter 제외:** `get*`, `set*`, `is*`, `build*`, 람다(`$`) 메서드는 수집 노이즈 제거를 위해 Instrumentation 대상에서 자동 제외

---

## 🔗 연관 프로젝트

| 프로젝트 | 역할 |
|----------|------|
| `lx-view-agent` | Java Agent — 메트릭 수집 및 전송 (본 프로젝트) |
| `lx-view` | APM 대시보드 — 수집 서버 + 시각화 UI |
