# ktc4-team-06

카카오테크 캠퍼스 4기 2단계 팀 프로젝트 — 경북대 2팀

## 구성

| 경로 | 역할 | 안내 문서 |
| --- | --- | --- |
| `backend/` | Spring Boot API 서버 | [backend/README.md](backend/README.md) |
| `frontend/` | 웹 애플리케이션 | [frontend/README.md](frontend/README.md) |
| `AI/` | AI 서비스 | [AI/README.md](AI/README.md) |
| `infra/` | Docker, Nginx 등 실행 환경 | [infra/README.md](infra/README.md) |
| `docs/` | 프로젝트 공통 문서 | [docs/README.md](docs/README.md) |

## 실행

```bash
cp infra/docker/.env.example infra/docker/.env   # POSTGRES_PASSWORD 를 채운다
touch AI/.env                                    # 비어 있어도 되지만 파일은 있어야 한다
cd infra/docker && docker compose up -d --build
```

접속 주소는 **http://localhost** 입니다. nginx 가 80 번만 외부에 열고
`/api/` 는 backend 로, 나머지는 frontend 로 넘깁니다.

프론트엔드만 띄울 때는 [frontend/README.md](frontend/README.md) 를 참고하세요.

## 백엔드 문서 안내

- 실행 방법, 설정 프로필, 현재 구현 상태: [backend/README.md](backend/README.md)
- 백엔드 구현·검토 규칙: [backend/AGENTS.md](backend/AGENTS.md)
- API 응답·오류·HTTP 상태 계약: [docs/api/api-conventions.md](docs/api/api-conventions.md)

## 프론트엔드 문서 안내

- 실행 방법, 스택, 화면 목록, UI 규칙: [frontend/README.md](frontend/README.md)
- 도메인 타입·API 함수 계약: [docs/frontend/feature-interfaces.md](docs/frontend/feature-interfaces.md)
- 화면별 기능 명세: [docs/frontend/screen-specs.md](docs/frontend/screen-specs.md)
- 전체 기능 명세·상태 전이·제품 규칙: [docs/frontend/feature-spec.md](docs/frontend/feature-spec.md)
- 프론트엔드가 요청하는 API 명세: [docs/api/api-spec.md](docs/api/api-spec.md)
