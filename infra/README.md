# Infra

개발·배포 환경을 위한 설정과 자동화 파일을 관리합니다.

- `docker/`: Docker 및 Docker Compose 설정
- `caddy/`: 외부 리버스 프록시·자동 HTTPS 설정
- `terraform/`: 클라우드 인프라 코드
- `scripts/`: 환경 구성과 배포 보조 스크립트

비밀값은 커밋하지 않습니다. 환경 변수는 `.env.example`로만 공유합니다.

## 백엔드 CI/CD

`.github/workflows/backend-ci-cd.yml`은 PR에서 백엔드를 검증하고, `develop` 반영 시
GHCR에 이미지를 게시한 뒤 기존 EC2를 SSM Run Command로 배포합니다.
GitHub의 Ubuntu 실행 환경에서 빌드하며 서버에서는 이미지를 받아 BE만 교체합니다.
FE·AI·DB 컨테이너 배포와 Flyway 도입은 포함하지 않습니다.

| 작업 | 실행 시점 | 내용 |
| --- | --- | --- |
| `test` | `develop`·`main` 대상 PR, `develop` push, 수동 실행 | 배포 스크립트 테스트와 `./gradlew test integrationTest build` |
| `publish` | `develop` push 또는 `develop` 수동 실행, 테스트 성공 후 | `linux/amd64` BE 이미지 빌드·GHCR 업로드 |
| `deploy` | 이미지 업로드 성공 후, `CADDY_READY=true` | SSM 배포·Caddy 재로딩·내부 검증·외부 HTTPS API 확인 |

모든 PR에서 검증하므로 경로 필터 때문에 필수 검사가 대기하는 문제를 피합니다.
PR에는 AWS OIDC 권한과 패키지 업로드 권한을 부여하지 않습니다.
수동 실행도 선택한 브랜치가 `develop`일 때만 이미지를 게시하며, 배포는 `CADDY_READY=true`가 필요합니다.

### GitHub 설정

저장소 **Settings → Secrets and variables → Actions → Variables**에 다음 값을 등록합니다.
주소·ARN·인스턴스 ID는 비밀값이 아니므로 Variables로 관리합니다.

| 이름 | 현재 값 |
| --- | --- |
| `AWS_ROLE_ARN` | `arn:aws:iam::782178410739:role/ktc-backend-deploy` |
| `AWS_REGION` | `ap-northeast-2` |
| `EC2_INSTANCE_ID` | `i-0e30a4108bfd4ea9f` |
| `PUBLIC_ORIGIN` | `https://iitda.duckdns.org` |
| `CADDY_READY` | 최초 운영 전환 전 미설정 또는 `false`, 완료 후 `true` |

이미지는 `ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend:<커밋 SHA>`로 업로드합니다.
배포에는 태그 대신 digest를 사용합니다. 최초 게시된 패키지는 비공개 상태를 유지하고,
조직에서 패키지 생성과 이 저장소의 Actions 접근을 허용해야 합니다.
Actions는 자동 제공되는 `GITHUB_TOKEN`으로 업로드하므로 업로드용 PAT는 필요하지 않습니다.

### AWS IAM Role

EC2에는 실행 중인 SSM Agent와 기존 SSM 인스턴스 권한이 필요합니다.
서버의 기존 S3·SSM 인스턴스 Role은 변경하지 않습니다.
GitHub Actions가 사용할 별도 Role `ktc-backend-deploy`의 정책은 다음 두 파일에 있습니다.
교육용 관리 Role `ktc-github-deploy`와 그 제한 정책은 변경하지 않습니다.

- [`iam/backend-deploy-trust.json`](iam/backend-deploy-trust.json): 이 저장소의 `develop`만 신뢰
- [`iam/backend-deploy-permissions.json`](iam/backend-deploy-permissions.json): 대상 EC2에 `AWS-RunShellScript` 실행 및 실행 결과 조회

이 저장소는 immutable OIDC subject를 사용합니다. 따라서 조직·저장소 ID가 들어간
`repo:kakaotechcampus-4@287968646/ktc4-kyungpook-2@1340154210:ref:refs/heads/develop`을 사용합니다.
GitHub 저장소의 OIDC 설정을 변경하면 신뢰 정책도 그 설정에 맞춰야 합니다.

전용 Role을 최초로 만들 때는 저장소 루트에서 다음 명령으로 설정합니다.
기존 GitHub OIDC Provider를 사용하고 다른 관리 Role의 정책은 변경하지 않습니다.

```bash
aws iam create-role \
  --role-name ktc-backend-deploy \
  --assume-role-policy-document file://infra/iam/backend-deploy-trust.json \
  --description 'GitHub develop backend deployment through SSM'

aws iam put-role-policy \
  --role-name ktc-backend-deploy \
  --policy-name BackendDeploySSM \
  --policy-document file://infra/iam/backend-deploy-permissions.json
```

### 서버에서 GHCR 로그인

비공개 이미지를 다운로드하려면 패키지에 접근할 수 있는 계정에서 **PAT classic**을
만들고 `read:packages` 권한을 부여합니다. 조직이 SSO를 요구하면 토큰도 SSO 승인해야 합니다.
토큰은 코드, GitHub Variables, SSM 명령에 넣지 않습니다.

EC2의 `ubuntu` 계정에서 아래 명령으로 로그인합니다. `sudo docker login`을 사용하면
root 계정의 로그인으로 저장되어 `ubuntu`로 실행되는 배포에 적용되지 않습니다.

```bash
AWS_DEFAULT_REGION=ap-northeast-2 ssh ktc-server

read -r -p 'GitHub username: ' ghcr_user
read -r -s -p 'GHCR read:packages token: ' ghcr_token
printf '\n'
printf '%s' "$ghcr_token" | docker login ghcr.io -u "$ghcr_user" --password-stdin
unset ghcr_token ghcr_user
```

패키지 게시 후 GitHub Packages에서 해당 계정에 읽기 접근 권한이 있는지도 확인합니다.
토큰이 만료되면 같은 방식으로 서버에서 다시 로그인합니다.

### 배포와 복구

SSM은 Actions가 검증한 [`scripts/deploy-backend.sh`](scripts/deploy-backend.sh)를 전달받아
`ubuntu` 계정으로 실행합니다. 이 계정이 기존 Docker를 실행할 수 있어야 합니다.
루트 경로 기본값은 `/home/ubuntu/ktc4-kyungpook-2`, 브랜치는 `develop`입니다.

1. 수정 중인 서버 파일·다른 브랜치·이미 진행 중인 배포가 있으면 중단합니다.
   전달받은 `EXPECTED_PUBLIC_ORIGIN`, 서버 `.env`, 실행 중인 Caddy의 사이트 주소가 다르거나
   CORS·로그인 완료 주소·HTTPS 보안 쿠키 설정이 맞지 않으면 컨테이너를 변경하지 않습니다.
2. 대상 커밋이 `origin/develop`에 속하는지 확인하고 fast-forward로 갱신합니다.
   서버 HEAD보다 오래된 커밋으로 돌아가는 배포는 거부합니다.
3. 기존 이미지를 복구용 로컬 태그로 보관하고 새 이미지 digest를 다운로드합니다.
   다운로드 실패 시 실행 중인 BE는 그대로 유지합니다.
   교체 전에 Caddyfile을 검사하고 기존 활성 JSON 설정을 백업합니다.
4. `--no-deps --no-build --pull never`로 BE만 교체하고 직접 상태 확인을 최대 120초 기다립니다.
5. Caddy를 재로딩한 뒤 프록시 상태·CORS·카카오 인가 요청의 콜백 주소를 검사합니다.
   HTTPS는 도메인과 SNI를 유지한 채 루프백에 접속하여 시스템 CA로 인증서를 검증합니다.
   리다이렉트를 따라가지 않고 HTTP 상태 코드도 확인합니다.
6. 성공하면 서버 `infra/docker/.env`의 `BACKEND_IMAGE`만 원자적으로 갱신합니다.
   다른 환경변수와 파일 권한은 보존합니다.
7. 교체 후 검증이 실패하면 이전 이미지로 BE를 복구하고 백업한 Caddy 활성 JSON을 다시 로딩합니다.
   잘못된 새 Caddyfile을 복구 단계에서 다시 읽지 않습니다.
   복구 성공 여부를 로그에 남기며 워크플로는 실패로 표시합니다.

GitHub에서는 배포 작업을 직렬 실행하고 서버에서는 `flock`으로 중복 실행을 막습니다.
SSM 결과가 아직 조회되지 않을 때는 재시도하고, 원격 명령의 종료 코드까지 확인합니다.
GitHub의 외부 API 확인만 실패하면 서버 배포 자체는 이미 완료된 상태일 수 있으므로
네트워크 경로와 SSM 실행 결과를 함께 확인합니다.

운영 `.env`·`AI/.env`·실행 중인 DB·Caddy·기존 BE가 준비된 서버를 전제로 합니다.
단일 BE 컨테이너를 교체하므로 짧은 중단이 발생합니다. `ddl-auto: update`를 유지하므로
이전 이미지 복구는 DB 스키마를 되돌리지 않습니다.

### 최초 실행 및 확인

기능 브랜치에서 PR을 열어 `test` 작업을 확인한 뒤 `develop`에 병합합니다.
최초 Caddy 전환을 마치고 게이트를 열면 자동 배포가 실행됩니다. 이후 **Actions → Backend CI/CD → Run workflow**에서
`develop`을 선택하여 동일한 커밋을 다시 배포할 수 있습니다.

본인 컴퓨터에서 다음 주소를 확인합니다.

```bash
python3 infra/scripts/verify-proxy.py health --origin https://iitda.duckdns.org
```

명령이 성공하면 외부 → Caddy → BE 경로와 인증서, HTTP 200, `{"status":"ok"}` 응답이 정상입니다.
SSM 로그의 OAuth 검사는 인가 요청의 `redirect_uri`까지 검사하며, 카카오 콘솔 등록 및
사용자의 실제 로그인 성공 여부는 별도로 확인해야 합니다.

수동 배포도 같은 스크립트를 사용합니다. `<커밋 SHA>`와 `<이미지 digest>`를 실제 값으로 바꾸세요.

```bash
cd /home/ubuntu/ktc4-kyungpook-2
EXPECTED_PUBLIC_ORIGIN=https://iitda.duckdns.org bash infra/scripts/deploy-backend.sh \
  '<커밋 SHA>' \
  'ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:<이미지 digest>'
```

### 로컬 검증

```bash
bash -n infra/scripts/deploy-backend.sh
python3 -m unittest discover -s infra/scripts/tests -p 'test_*.py' -v
cd backend
./gradlew --no-daemon test integrationTest build
```

배포 테스트는 임시 Git 저장소·HTTP 서버·가짜 Docker를 사용하여 성공, 다운로드 실패,
기동 실패, CORS·OAuth·Caddy 재로딩 실패 시 복구, 서버 수정 파일 보존, 오래된 커밋 거부를
검증합니다. Docker 프록시 테스트는 임시 Caddy와 에코 서버, 내부 CA로 실제 HTTPS 경로를 확인합니다.
Docker가 없으면 해당 테스트는 건너뛰지만 CI에서는 `REQUIRE_DOCKER_TESTS=true`로 필수 실행합니다.
실제 서버와 운영 DB에는 접근하지 않습니다.

## Caddy와 HTTPS

로컬은 `.env.example`의 `PUBLIC_ORIGIN=http://localhost`, `AUTH_COOKIE_SECURE=false`를 사용합니다.
운영 서버 `infra/docker/.env`에는 아래 값을 설정합니다.

```dotenv
PUBLIC_ORIGIN=https://iitda.duckdns.org
AUTH_COOKIE_SECURE=true
```

Caddy가 공인 인증서를 발급·갱신하고 HTTP 요청을 HTTPS로 이동시킵니다.
도메인이 Elastic IP를 가리켜야 하며 보안 그룹은 TCP 80·443을 허용해야 합니다.
2019 관리 API는 컨테이너 내부 루프백에만 열리고 외부에 공개하지 않습니다.
`caddy_data`와 `caddy_config` 볼륨을 삭제하지 마세요. 인증서와 발급 계정 정보가 들어 있습니다.
Certbot이나 별도 갱신 cron은 필요하지 않습니다.

`PUBLIC_ORIGIN`을 변경할 때는 Caddy와 백엔드를 재생성해야 합니다. 파일 재로딩만으로
컨테이너 환경변수가 바뀌지 않습니다. 일반 백엔드 배포는 이 변경을 자동으로 처리하지 않고
실행 중인 Caddy와 주소가 다르면 중단합니다. GitHub 값은 `EXPECTED_PUBLIC_ORIGIN`으로만 전달해
서버 `.env`를 덮어쓰지 않습니다. 주소 변경 후 GitHub의 `PUBLIC_ORIGIN`도 맞춥니다.

운영 Swagger는 `https://iitda.duckdns.org/swagger-ui/index.html`입니다.
OpenAPI JSON은 `/v3/api-docs`를 사용합니다. YAML 경로는 이번 외부 프록시에 포함하지 않습니다.
프론트 컨테이너 안의 정적 파일용 Nginx는 유지하며, 캐시 헤더도 이 컨테이너에서 정합니다.
API 응답 헤더 대기와 응답 읽기 사이의 대기 시간은 각각 120초로 제한합니다.
HTTP/3와 HSTS는 이번 구성에서 활성화하지 않습니다.

### 최초 EC2 전환

이 절차는 운영자가 별도로 실행합니다. 먼저 GitHub의 `CADDY_READY`를 `false`로 설정하고
이미 진행 중인 배포가 끝났는지 확인합니다. 병합 후 게이트가 닫혀 있는 동안에는
백엔드 핫픽스도 자동 배포되지 않으므로 전환 시간을 미리 정합니다.

1. DuckDNS 주소와 EC2의 Elastic IP가 같은지 확인합니다. 카카오 콘솔에
   `https://iitda.duckdns.org/login/oauth2/code/kakao`를 추가하고 기존 HTTP 콜백은 유지합니다.
2. 서버의 작업 트리가 깨끗하고 브랜치가 `develop`인지 확인한 뒤, 이전 커밋·환경값·실행 이미지와
   Nginx 컨테이너 ID를 백업합니다. 다음 명령은 **서버 저장소 루트**에서 실행합니다.

```bash
cd /home/ubuntu/ktc4-kyungpook-2
git status --short
git branch --show-current
mkdir -p .git/caddy-migration
chmod 700 .git/caddy-migration
git rev-parse HEAD > .git/caddy-migration/previous-sha
cp -p infra/docker/.env .git/caddy-migration/previous.env
backend_id=$(docker compose -f infra/docker/compose.yaml ps --quiet backend)
docker tag "$(docker inspect --format '{{.Image}}' "$backend_id")" ktc-backend:before-caddy
docker compose -f infra/docker/compose.yaml ps --quiet nginx > .git/caddy-migration/nginx-id
```

3. `git fetch origin develop` 후 전환 대상으로 정한 `<SHA>`를 `git merge --ff-only <SHA>`로 적용합니다.
   서버 `.env`의 운영 값을 설정하고 CI가 게시한 해당 SHA의 백엔드 digest를 확인합니다.
   컨테이너 포트를 열기 전에 이미지를 받고 설정을 검사합니다.

```bash
docker compose -f infra/docker/compose.yaml pull caddy
docker pull 'ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:<digest>'
docker compose -f infra/docker/compose.yaml config --quiet
docker compose -f infra/docker/compose.yaml run --rm --no-deps caddy \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

4. 기존 Nginx를 중지한 뒤 Caddy를 시작합니다. Nginx를 삭제하거나 `--remove-orphans`를 사용하지 않습니다.
   인증서가 발급되면 곧바로 같은 SHA의 백엔드를 적용합니다. 이 과정에는 짧은 전환 시간이 있습니다.

```bash
docker stop "$(cat .git/caddy-migration/nginx-id)"
docker compose -f infra/docker/compose.yaml up -d --no-deps caddy
# 인증서 발급 대기: 실패하면 Caddy 로그를 확인하고 무한히 기다리지 않는다.
for attempt in {1..60}; do
  python3 infra/scripts/verify-proxy.py health --origin https://iitda.duckdns.org --local && break
  sleep 2
done
python3 infra/scripts/verify-proxy.py health --origin https://iitda.duckdns.org --local
EXPECTED_PUBLIC_ORIGIN=https://iitda.duckdns.org bash infra/scripts/deploy-backend.sh \
  '<SHA>' 'ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:<digest>'
```

5. HTTPS 인증서, HTTP 리다이렉트, Swagger, 외부 8080 접근 차단을 확인합니다.
   실제 카카오 로그인 후 `access_token`의 `Secure`·`HttpOnly`와 `/api/v1/auth/me`를 확인합니다.
   프론트가 `VITE_AUTH_MOCK=true`로 빌드돼 있으면 로그인 진입 URL로 직접 이동합니다.
   이 변경은 프론트 로그인 모드나 프론트 이미지를 바꾸지 않습니다.
6. 성공하면 GitHub `PUBLIC_ORIGIN`을 운영 주소로 맞추고 `CADDY_READY=true`로 배포 게이트를 엽니다.
   안정화 후 중지된 Nginx 컨테이너, 보안 그룹 8080 규칙, 카카오 HTTP 콜백을 정리합니다.

### 최초 전환 실패 시 복구

게이트를 닫은 채 진행 중인 배포가 없는지 확인합니다. 추가 서버 수정이 없다면 이전 커밋과
환경을 복원할 수 있습니다. DB·AI·프론트와 볼륨은 건드리지 않습니다.

```bash
cd /home/ubuntu/ktc4-kyungpook-2
(
  flock -n 9 || exit 1
  test -z "$(git status --porcelain)" || exit 1
  docker compose -f infra/docker/compose.yaml stop caddy
  git reset --hard "$(cat .git/caddy-migration/previous-sha)"
  cp -p .git/caddy-migration/previous.env infra/docker/.env
  BACKEND_IMAGE=ktc-backend:before-caddy docker compose -f infra/docker/compose.yaml \
    up -d --no-deps --no-build --pull never nginx backend
  python3 - <<'PY'
from pathlib import Path
path = Path('infra/docker/.env')
lines = [line for line in path.read_text().splitlines() if not line.startswith('BACKEND_IMAGE=')]
path.write_text('\n'.join(lines) + '\nBACKEND_IMAGE=ktc-backend:before-caddy\n')
PY
  curl --fail http://54.116.206.217/api/health
) 9>.git/backend-deploy.lock
```

`develop` 브랜치와 깨끗한 작업 트리를 유지하므로 이후 후손 커밋으로 재시도할 수 있습니다.
실패한 최초 전환에서는 백엔드 이미지 복구만으로 HTTP·Nginx 운영 상태까지 돌아가지 않으므로
위 전체 복구 절차가 필요합니다. Caddy 영구 볼륨은 보존합니다.

일반 배포의 자동 복구는 이전 활성 Caddy JSON을 다시 로딩합니다. 저장소의 새 Caddyfile까지
되돌리는 작업은 하지 않으므로 잘못된 배포 설정은 수정 커밋으로 해결한 뒤 Caddy를 재시작하세요.
