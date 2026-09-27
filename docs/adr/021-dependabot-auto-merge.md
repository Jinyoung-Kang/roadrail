# ADR-021 Dependabot minor · patch 자동 병합 — 필수 검사 하나(ci passed)로 게이트

- 상태: 채택 (2026-09-27)
- 배경: 묶음 PR 로 주간 PR 수는 줄었지만(생태계마다 minor · patch 하나), CI 가 통과한 묶음도 사람이 매번 병합했다.
  메이저는 코드 이전이 필요해 사람이 봐야 하지만, minor · patch 는 CI(린트 · 테스트 · 의존성 감사 · 비밀키 검사 · 이미지 빌드)가 곧 검토다.

## 결정

1. **대상은 update-type 이 minor · patch 인 Dependabot PR** — `dependabot/fetch-metadata` 가 Dependabot 커밋 메타데이터에서 읽은
   PR 안의 가장 큰 변경으로 판단한다. 묶음 PR(npm · pip · gradle)과 보안 업데이트 PR 이 여기에 든다.
   메이저와 semver 로 읽을 수 없는 버전(update-type 없음)은 사람이 병합한다. actions 묶음은 update-types 제한이 없어
   메이저가 섞이면 자동 병합되지 않는다(예: 7건이 모두 메이저였던 actions 묶음).
2. **기다림은 GitHub 가 한다** — 워크플로는 `gh pr merge --auto --merge` 로 자동 병합을 켜기만 하고, 병합은 main 브랜치 보호의
   필수 검사가 통과한 뒤 GitHub 가 한다. 워크플로 안에 폴링 · 대기가 없다.
3. **필수 검사는 `ci passed` 하나** — CI 작업 5개(collector · api · web · secret scan · docker images)를 `needs` 로 모아 모두
   `success` 인지 확인한다. 작업 이름에 버전(Next.js 15 · Python 3.11 …)이 들어 있어 이름을 필수 검사로 걸면 버전을 올릴 때마다
   보호 설정이 깨진다. GitHub 는 건너뛴(skipped) 작업을 필수 검사에서 성공으로 치므로 `if: always()` 로 늘 돌고 결과를 직접 본다
   (앞 작업이 실패해 건너뛴 이미지 빌드 · 취소된 실행도 실패). 보호는 PR 에만 걸리므로 main push 에서는 돌지 않는다
   — 동시 실행 취소로 멈춘 이전 main 커밋에 실패 표시가 남지 않는다.
4. **쓰기 권한은 Dependabot 이 올린 PR 에서만** — 워크플로 기본 권한은 없음(`permissions: {}`), 작업에만 contents · pull-requests 쓰기.
   `github.actor` 와 PR 작성자가 모두 `dependabot[bot]` 일 때만 돈다. 쓰기 토큰을 쥐는 fetch-metadata 는 커밋 SHA 로 고정한다.
5. **저장소 설정** (코드 밖 — 여기에 기록)
   - Allow auto-merge: 켬
   - main 브랜치 보호: 필수 검사 `ci passed`(GitHub Actions 앱이 낸 것만 인정) · 최신 main 반영 요구(strict) 끔 · 관리자 예외(enforce_admins 끔)
     · 리뷰 요구 없음 · 강제 push · 삭제 허용 안 함(보호 기본값)
   - strict 를 끈 이유: 켜면 먼저 병합된 묶음 뒤의 PR 이 '뒤처짐'으로 멈춘다. 자동 병합은 브랜치를 갱신하지 않고, Dependabot 은 충돌이 날 때만
     rebase 한다. 묶음 PR 은 생태계마다 바꾸는 파일이 겹치지 않고, PR CI 는 그 시점의 main 과 합친 결과(merge ref)를 검사한다.

## 대안

- 작업 5개의 이름을 각각 필수 검사로 — 버전 표기가 바뀔 때마다 보호 설정도 바꿔야 하고, 빠뜨리면 PR 이 '검사 대기'로 멈춘다.
- CI 완료(`workflow_run`)를 받아 직접 병합 — 브랜치 보호 없이도 되지만 fetch-metadata 를 쓸 수 없어(PR 이벤트가 아님) 메타데이터를
  직접 해석해야 하고, 사람이 여는 PR 에는 아무 보호가 없다.
- 메이저도 자동 병합 — Next 16 · React 19 · Tailwind 4 · TypeScript 7 · pandas 3 처럼 코드 이전이 필요한 것이 CI 만으로 드러나지 않는다.

## 결과 · 한계

- 필수 검사가 실패한 PR 은 자동 병합이 켜진 채 열려 있다 — Dependabot 이 다시 올리거나(rebase) 사람이 고치면 이어서 병합된다.
- GITHUB_TOKEN 으로 켠 자동 병합은 github-actions[bot] 의 병합으로 기록된다. GitHub 는 GITHUB_TOKEN 이 일으킨 이벤트로 새 워크플로를
  만들지 않으므로(workflow_dispatch · repository_dispatch 제외) 이 병합 커밋에는 main push CI 가 돌지 않는다. PR 단계에서 병합 결과를
  검사했고, 다음 push 때 main CI 가 다시 돈다. main 에서도 돌게 하려면 GitHub App 토큰이나 PAT 로 켜야 하는데, 관리할 비밀이 늘어 쓰지 않는다.
  → 보완(2026-09-27): Dependabot 을 매주 월요일 06:00 KST 로 고정하고, 09:17 에 main 전체 CI · 09:37 에 CodeQL 이 일정으로 돌아
  그 주에 자동 병합된 main 을 다시 검사한다(`ci.yml` · `codeql.yml` 의 schedule). 공개 저장소의 일정 실행은 60일 동안 활동이 없으면
  꺼지고, 실패 알림은 cron 을 마지막으로 고친 사람에게 간다(GitHub 규칙).
- 관리자는 보호를 우회해 main 에 직접 push 하거나 `gh pr merge --admin` 으로 병합할 수 있다(1인 저장소). 그 밖의 병합은 `ci passed` 가 통과해야 한다.
- 되돌리기: 이 워크플로 파일을 지우거나 저장소 설정의 Allow auto-merge 를 끈다. 이미 켜진 PR 은 `gh pr merge --disable-auto <번호>`.
  브랜치 보호는 `gh api -X DELETE repos/{owner}/{repo}/branches/main/protection`.
