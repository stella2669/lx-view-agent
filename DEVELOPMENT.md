# 개발 가이드라인 (Development Guidelines)

본 프로젝트의 개발 및 운영 시 준수해야 할 핵심 규칙입니다.

### 🐚 쉘 환경 (Shell Environment)
- **모든 쉘 커맨드는 PowerShell이 아닌 일반 CMD 문법으로 작성하고 실행해야 합니다.**
- **CMD 실행 시 한글 깨짐 방지를 위해 반드시 `chcp 65001` 명령어를 선행하여 UTF-8 환경을 확보해야 합니다.**
  - 예: `cmd /c "chcp 65001 && gradlew.bat ..."`
- 에이전트(AI)가 커맨드를 제안할 때도 반드시 CMD 호환성과 UTF-8 환경을 우선시합니다.
  - 예: `copy /y` (O), `Copy-Item` (X)
  - 예: `dir` (O), `Get-ChildItem` (X)

### 🔤 인코딩 (Encoding)
- **모든 소스 코드, 파일 입출력, 로그 기록, 네트워크 전송은 반드시 UTF-8 인코딩을 사용해야 합니다.**
- Java 코드에서 파일 작업 시 `StandardCharsets.UTF_8`을 명시적으로 선언하십시오.

### 🛡️ 방어적 프로그래밍 (Defensive Coding)
- **에이전트의 예외가 타겟 애플리케이션으로 전파되어서는 절대로 안 됩니다.**
- 모든 Instrumentation 로직(Advice)은 내부적으로 `try-catch`를 통해 예외를 처리(Swallow)하거나 로깅만 수행해야 합니다.
- 외부 서버(수집 서버) 통신 시 반드시 짧은 **Connect/Read Timeout**을 설정하여 앱의 비즈니스 로직 스레드가 블록되지 않도록 합니다.

### ⚡ 성능 및 최적화 (Performance)
- **객체 생성 최소화**: 매 메서드 호출마다 새로운 객체를 생성(Allocation)하는 것을 지양하십시오. GC 부하를 최소화하기 위해 기본 타입(Primitive) 사용과 `StringBuilder` 활용을 권장합니다.
- **Asynchronous Reporting**: 메트릭 데이터 전송은 반드시 별도의 백그라운드 스레드(ScheduledExecutor)를 통해 비동기로 처리하며, 메모리 부족 방지를 위해 큐 사이즈를 제한합니다.

### 🧩 ByteBuddy Advice 규칙
- **헬퍼 메서드 공개**: Advice(`@OnMethodEnter`, `@OnMethodExit`) 내에서 호출하는 모든 정적 헬퍼 메서드는 반드시 **`public static`**으로 선언해야 합니다. (대상 클래스 인라인 시 `IllegalAccessError` 방지)

### ⚙️ 구성 우선순위 (Configuration Priority)
- 에이전트 설정은 다음 순서로 적용됩니다:
  1. **System Property** (-Dlx.agent.xxxx=...)
  2. **Properties File** (lx-agent.properties)
  3. **Default Values** (Code level)

### ♻️ 재사용성 및 모듈화 (Reuse & Modularity)
- **중복 배제(DRY)**: 2회 이상 반복되는 로직은 반드시 공통 유틸리티나 상위 인터페이스로 추출하여 캡슐화하십시오.
- **관심사 분리**: 각 클래스는 고유의 책임(Responsibility)에 집중하게 하며, 서비스 간의 결합도를 낮추어 재사용성을 높입니다.
- **예외 없는 중복 관리**: 아주 단순한 로직이라도 프로젝트 전반에서 공유된다면 공통 모듈화를 우선적으로 고려합니다.

### 🚫 하드코딩 지양 (Avoid Hardcoding)
- **매직 넘버 및 리터럴 금지**: 타임아웃, 데이터 형식, 메트릭 타입 등 반복되거나 변경 가능성이 있는 값은 반드시 `Constants` 클래스에 상수로 정의하여 사용하십시오.
- **설정의 외부화**: 환경에 따라 변할 수 있는 값은 상수가 아닌 프로퍼티 파일(`lx-agent.properties`)을 통해 관리하는 것을 지향합니다.

---

### 🏗️ 빌드 및 배포 (Build & Deploy)
- 소스 수정 후 빌드와 배포를 동시에 수행하려면 다음 워크플로우를 실행하십시오.
  - 명령어: `/build-and-copy`
  - 상세 절차는 `.agent/workflows/build-and-copy.md`를 참조하십시오.

### ⚙️ 에이전트 설정
- 에이전트 설정 변경 시 `lx-agent.properties`를 활용하며, 로그 경로는 절대 경로 사용을 권장합니다.
