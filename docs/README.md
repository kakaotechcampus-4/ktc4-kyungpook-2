# Docs

프로젝트 공통 문서의 탐색 지점입니다. 문서의 상세 내용은 각 원본 문서에서 관리합니다.

## API

| 문서 | 역할 |
| --- | --- |
| [api/api-conventions.md](api/api-conventions.md) | 외부 API 경로, 응답 형식, 오류 코드, HTTP 상태 코드 규약 |
| [api/api-spec.md](api/api-spec.md) | 프론트엔드가 요청하는 엔드포인트 명세 · 확정 필요 사항 · 구현 우선순위 |

## 프론트엔드

| 문서 | 역할 |
| --- | --- |
| [frontend/feature-interfaces.md](frontend/feature-interfaces.md) | 도메인 타입과 API 함수 계약 (팀 공유용 인터페이스) |
| [frontend/screen-specs.md](frontend/screen-specs.md) | 화면별 기능 명세 (기관 15 · 학부모 13) |
| [frontend/feature-spec.md](frontend/feature-spec.md) | 전체 기능 명세 · 상태 전이 · 제품 불변 규칙 |

## AI

| 문서 | 역할 |
| --- | --- |
| [AI/README.md](../AI/README.md) | 에이전트 구조 · 실행 방법 · API 계약 · 고칠 곳 |
| [AI/evals/README.md](../AI/evals/README.md) | 테스트 데이터 형식 · 채점 방법 · 지표 읽는 법 |

매칭 에이전트의 판정 기준(어떤 상황에서 `auto` / `review` / `multi` / `unmatched` 가
정답인지)은 구현이 아니라 정책이므로 노션에서 관리합니다 →
[AI · Matching Agent 자동 확정 판정 기준](https://app.notion.com/p/elice-track/AI-Matching-Agent-9-23-3e42bb98425780498d9ffb9db2d83819?v=e022bb98425783e2baac88bd5a3d8489&source=copy_link)

프론트엔드 실행 방법과 스택은 [frontend/README.md](../frontend/README.md)에서 확인합니다.

백엔드의 실행 방법과 현재 상태는 [backend/README.md](../backend/README.md), 구현·검토
규칙은 [backend/AGENTS.md](../backend/AGENTS.md)에서 확인합니다.
