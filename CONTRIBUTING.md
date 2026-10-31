# 작업 규칙

2인 병행 개발 기준입니다. 상세 일정과 분담은 `docs/WORKPLAN.md` 를 보세요.

## 디렉터리 소유권

| 경로 | 담당 |
|---|---|
| `car-app/` | 차량 앱 담당 |
| `hub-server/`, `passenger-web/`, `demo/` | 서버·웹 담당 |
| `contract/` | 공동 — 변경은 양쪽 승인 필수 |

**서로의 디렉터리 파일은 직접 수정하지 않습니다.** 필요하면 이슈로 요청하세요.

## 브랜치

- `main` 은 보호 브랜치입니다. 직접 푸시 금지.
- 브랜치 이름: `feat/car-*`, `feat/hub-*`, `feat/web-*`, `feat/demo-*`, `fix/*`, `docs/*`
- PR로만 병합합니다. `contract/` 를 건드리는 PR은 상대를 리뷰어로 지정하고 승인 후 병합.

## 커밋

- 공모전 안내에 따라 **개발자 실명이 저장소에 노출되지 않도록** 합니다.
  저장소를 클론한 뒤 각자 아래를 실행하세요.

  ```bash
  git config user.name "<GitHub 사용자명>"
  git config user.email "<GitHub 사용자명>@users.noreply.github.com"
  ```

- 커밋 메시지는 한글/영문 모두 좋습니다. 무엇을 왜 바꿨는지 한 줄로.

## 절대 커밋하지 않는 것

- `local.properties`, 서명 키(`*.jks`, `*.keystore`), `.env`, API 키
- 접수양식 docx 등 개인정보가 담긴 제출 문서 (`.gitignore` 에서 `*.docx` 차단)
- 개인 전화번호·이메일·학교/학과 정보

## 통합 체크포인트

각자 다 만들고 마지막에 합치면 터집니다. `docs/WORKPLAN.md` 의 CP1~CP5 날짜에
반드시 양쪽을 붙여 한 줄로 동작하는지 확인합니다.
