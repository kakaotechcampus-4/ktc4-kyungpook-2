# Docs

프로젝트 공통 문서의 탐색 지점입니다. 문서의 상세 내용은 각 원본 문서에서 관리합니다.

## API

| 문서 | 역할 |
| --- | --- |
| [api/api-conventions.md](api/api-conventions.md) | 외부 API 경로, 응답 형식, 오류 코드, HTTP 상태 코드 규약 |
| [api/api-spec.md](api/api-spec.md) | 프론트엔드가 요청하는 엔드포인트 명세 · 확정 필요 사항 · 구현 우선순위 |

## DB

| 문서 | 역할 |
| --- | --- |
| [db/schema.md](db/schema.md) | DB 스키마 원본 (노션은 사본). **엔티티를 바꾸면 같은 PR에서 갱신** |

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
| [AI/evals/scripts/matching/README.md](../AI/evals/scripts/matching/README.md) | 매칭 테스트 데이터 형식 · 채점 방법 · 지표 읽는 법 |

각 에이전트의 판정 기준은 레포 안에 있고, 문서와 코드가 어긋나면 테스트가 깨집니다.

| 문서 | 역할 |
| --- | --- |
| [AI/matching/CRITERIA.md](../AI/matching/CRITERIA.md) | 매칭 — `auto` / `review` / `multi` / `unmatched` 판정 기준 |
| [AI/evals/scripts/validation/README.md](../AI/evals/scripts/validation/README.md) | 검증 — `PASS` / `REVIEW` / `BLOCK` 과 이슈 유형 7개 |

프론트엔드 실행 방법과 스택은 [frontend/README.md](../frontend/README.md)에서 확인합니다.

백엔드의 실행 방법과 현재 상태는 [backend/README.md](../backend/README.md), 구현·검토
규칙은 [backend/AGENTS.md](../backend/AGENTS.md)에서 확인합니다.
