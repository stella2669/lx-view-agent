---
description: 에이전트 빌드 및 agents 폴더 자동 복사 워크플로우
---

이 워크플로우는 에이전트 소스 수정 후 빌드와 배포를 한 번에 수행하기 위한 지침입니다.

### 실행 단계

1. **Gradle 빌드 실행**
   프로젝트 루트에서 shadowJar를 생성합니다. (cmd 기준, UTF-8 적용)
   ```cmd
   chcp 65001 && gradlew.bat clean shadowJar
   ```

// turbo
2. **빌드 결과물 복사**
   빌드된 jar 파일을 타겟 앱의 agents 폴더로 강제 복사합니다.
   ```cmd
   chcp 65001 && copy /y "build\libs\lx-view-agent-1.0.0.jar" "C:\workspace\application\webtics\agents\lx-view-agent-1.0.0.jar"
   ```

3. **배포 확인**
   복사된 파일 정보를 확인합니다.
   ```cmd
   chcp 65001 && dir "C:\workspace\application\webtics\agents\lx-view-agent-1.0.0.jar"
   ```
