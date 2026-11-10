# Docs

레포 문서의 지도입니다. 처음이면 [루트 README](../README.md)부터 읽습니다.

서비스가 무엇을 하고 일이 어떤 순서로 흐르는지는 [overview.md](overview.md)에 있습니다 — 역할 · 전체 흐름 · 도메인 지도 · 용어.

---

## 영역별 안내

- [AI/README.md](../AI/README.md) — 에이전트 다섯 · 실행 · API · 테스트 · 평가
- [backend/README.md](../backend/README.md) — 실행 · 테스트 · 로그인 · DB 스키마 · 환경 변수
- [frontend/README.md](../frontend/README.md) — 실행 · E2E · 화면 · 로그인
- [infra/README.md](../infra/README.md) — CI/CD · 배포와 복구 · DB 마이그레이션 · HTTPS
- [backend/scripts/README.md](../backend/scripts/README.md) — 서버 DB를 직접 고치는 운영 스크립트

## 팀 규칙

- [AGENTS.md](../AGENTS.md) — 모든 영역에 적용하는 규칙 (DB 스키마를 바꿀 때)
- [backend/AGENTS.md](../backend/AGENTS.md) — 백엔드 구현 · 테스트 · 문서 동기화 규칙

## 공통 규약

- [api/api-conventions.md](api/api-conventions.md) — API 경로 · 응답 형식 · 오류 코드 · HTTP 상태
- [db/schema.md](db/schema.md) — 테이블의 뜻과 규칙. 스키마를 바꾸면 Flyway 마이그레이션과 함께 같은 PR에서 고칩니다

## 기능 명세

- [api/api-spec.md](api/api-spec.md) — 엔드포인트별 요청 · 응답과 구현 상태
- [frontend/feature-spec.md](frontend/feature-spec.md) — 기능 목록 · 상태 전이 · 제품 규칙
- [frontend/screen-specs.md](frontend/screen-specs.md) — 화면별 동작
- [frontend/feature-interfaces.md](frontend/feature-interfaces.md) — 프론트 도메인 타입과 API 함수

## AI 설계와 평가

- [AI/AGENTIC.md](../AI/AGENTIC.md) — 단계 분기 · 도구 연동 · 상태 관리 · 예외 처리
- [AI/evals/SCOREBOARD.md](../AI/evals/SCOREBOARD.md) — 네 에이전트의 현재 점수
- 판정 기준 — [매칭](../AI/matching/CRITERIA.md) · [검증](../AI/evals/scripts/validation/README.md) · [요약](../AI/summary/CRITERIA.md) · [인사이트](../AI/insight/CRITERIA.md) · [알림](../AI/notify/CRITERIA.md). 코드가 기준과 어긋나면 AI 테스트가 깨집니다
