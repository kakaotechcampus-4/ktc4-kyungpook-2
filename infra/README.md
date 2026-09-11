# Infra

개발·배포 환경을 위한 설정과 자동화 파일을 관리합니다.

- `docker/`: Docker 및 Docker Compose 설정
- `nginx/`: 웹 서버·리버스 프록시 설정
- `terraform/`: 클라우드 인프라 코드
- `scripts/`: 환경 구성과 배포 보조 스크립트

비밀값은 커밋하지 않습니다. 환경 변수는 `.env.example`로만 공유합니다.
