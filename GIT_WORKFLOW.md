# 🚀 Git 배포 파이프라인 가이드 (dev → prod)

작업한 `dev` 브랜치의 소스 코드를 최종 상용(`prod`) 브랜치로 병합하고 배포하는 표준 순서도입니다.

## 📌 명령어 순서 요약

```bash
# 1. dev 브랜치 변경사항 커밋 및 최신화
git add .
git commit -m "작업 내용"
git pull origin dev

# 2. 병합을 위해 prod 브랜치로 이동
git checkout prod

# 3. 변경된 dev 브랜치의 내용을 prod로 가져오기 (Merge)
git merge dev

# 4. 병합된 내용을 원격 저장소의 prod로 반영 (Push)
git push origin prod

# 5. 다시 개발 작업을 이어나가기 위해 dev 브랜치로 복귀
git checkout dev
```

---

### 💡 (참고사항) 충돌이 발생했을 경우
`git merge dev` 실행 후 만약 충돌(Conflict)이 발생했다면, 
1. 해당 파일을 열어 충돌 부분을 올바르게 수정합니다.
2. `git add <충돌 해결된 파일>`
3. `git commit -m "Merge dev into prod (resolve conflicts)"`
4. 이후 4번(`git push origin prod`) 단계를 이어나가시면 됩니다.
