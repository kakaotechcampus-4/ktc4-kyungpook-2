# Infra

개발·배포 환경을 위한 설정과 자동화 파일을 관리합니다.

- `docker/`: Docker 및 Docker Compose 설정
- `nginx/`: 웹 서버·리버스 프록시 설정
- `terraform/`: 클라우드 인프라 코드
- `scripts/`: 환경 구성과 배포 보조 스크립트

비밀값은 커밋하지 않습니다. 환경 변수는 `.env.example`로만 공유합니다.

## BE·AI CI/CD

`.github/workflows/backend-ci-cd.yml`과 `ai-ci-cd.yml`은 PR에서 검증하고, 모든 `develop`
push에서 서비스별 누적 변경을 검사하여 변경된 서비스만 기존 EC2에 SSM으로 배포합니다.
FE 자동 배포, mock 설정 변경, DB 배포와 Flyway 도입은 포함하지 않습니다.

| 작업 | 실행 시점 | 내용 |
| --- | --- | --- |
| `changes` | 모든 `develop` push와 `develop` 수동 실행 | 서비스별 마지막 성공 배포부터 누적 변경 검사 |
| BE `test` | `develop`·`main` 대상 PR, `develop` push, 수동 실행 | 배포 스크립트 테스트와 Java 21 `./gradlew test integrationTest build` |
| AI `test` | 위와 동일 | Python 3.11, `pip check`, pytest, Docker 빌드·상태 확인 |
| `publish` | 서비스 변경이 감지되고 해당 CI 성공 후 | `linux/amd64` 이미지 빌드·GHCR 업로드 |
| `deploy` | 이미지 게시 성공 후 | 서버 잠금·해당 서비스 교체·검증·성공 배포 기록 |

push 트리거에는 `paths` 필터를 사용하지 않습니다. AI 배포가 실패하거나 대기 실행이
취소돼도 이후 BE·문서만 변경된 push에서 AI 미배포 변경을 다시 검사합니다.
PR에서는 검증만 실행하며 AWS OIDC·패키지 게시·배포 기록 쓰기 권한을 부여하지 않습니다.
수동 실행도 선택한 브랜치가 `develop`일 때만 해당 서비스를 강제로 게시·배포합니다.
테스트 보고서는 7일 보관합니다. AI CI는 실제 모델 호출이나 운영 키를 사용하지 않습니다.

### 성공 기준과 변경 감지

성공 기준은 워크플로 전체의 성공 여부가 아니라 GitHub Deployments API의 `be-develop`,
`ai-develop` 기록입니다. 환경별 배포 목록을 페이지 단위로 읽고 **최신 상태가 `success`인
가장 최근 배포의 SHA**를 사용합니다. 실패·복구·취소·오래된 실행·배포 생략은 기준을 갱신하지 않습니다.

`fetch-depth: 0`으로 전체 이력을 받고 기준 SHA부터 대상 SHA까지 비교합니다.
성공 기록 없음·기준 커밋 누락·비조상 이력은 전체 배포로 전환하고, `develop` force-push는
두 서비스를 강제 배포합니다. 인증·네트워크·API 조회 오류는 변경 없음으로 처리하지 않고 실패합니다.

- `backend/`, BE 워크플로·배포 코드, Nginx 설정: BE 대상
- `AI/`, AI 워크플로·배포 코드: AI 대상
- 공통 Compose·배포 helper·SSM 전달·기록 관리 코드: BE·AI 대상
- `docs/`, README·AGENTS, FE 코드: 배포를 유발하지 않음

Deployments에는 SHA·이미지 digest·실행 로그 URL을 남깁니다. 생성 시 `auto_merge=false`,
`required_contexts=[]`를 지정하고 CI 성공은 워크플로 의존성으로 보장합니다.
서버 상태·실행 이미지 digest/revision·`.env` 저장까지 완료한 뒤 `success`를 기록합니다.
기록 갱신 실패도 워크플로 실패입니다. 다음 실행은 이전 성공 기준부터 다시 검사합니다.

### GitHub 설정

저장소 **Settings → Secrets and variables → Actions → Variables**에 다음 값을 등록합니다.
주소·ARN·인스턴스 ID는 비밀값이 아니므로 Variables로 관리합니다.

| 이름 | 현재 값 |
| --- | --- |
| `AWS_ROLE_ARN` | `arn:aws:iam::782178410739:role/ktc-backend-deploy` |
| `AWS_REGION` | `ap-northeast-2` |
| `EC2_INSTANCE_ID` | `i-0e30a4108bfd4ea9f` |

이미지는 `ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend:<전체 SHA>`와
`ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-ai:<전체 SHA>`로 게시합니다.
두 이미지 모두 `org.opencontainers.image.revision` 라벨에 전체 SHA를 기록합니다.
배포에는 태그 대신 digest를 사용합니다. 최초 게시된 패키지는 비공개 상태를 유지하고,
조직에서 패키지 생성과 이 저장소의 Actions 접근을 허용해야 합니다.
Actions는 자동 제공되는 `GITHUB_TOKEN`으로 업로드하므로 업로드용 PAT는 필요하지 않습니다.

### AWS IAM Role

EC2에는 실행 중인 SSM Agent와 기존 SSM 인스턴스 권한이 필요합니다.
서버의 기존 S3·SSM 인스턴스 Role은 변경하지 않습니다.
BE·AI Actions는 기존 Role `ktc-backend-deploy`를 재사용합니다. 정책은 다음 두 파일에 있습니다.
교육용 관리 Role `ktc-github-deploy`와 그 제한 정책은 변경하지 않습니다.

- [`iam/backend-deploy-trust.json`](iam/backend-deploy-trust.json): 이 저장소의 `develop`만 신뢰
- [`iam/backend-deploy-permissions.json`](iam/backend-deploy-permissions.json): 대상 EC2에 `AWS-RunShellScript` 실행 및 실행 결과 조회

이 저장소는 immutable OIDC subject를 사용합니다. 따라서 조직·저장소 ID가 들어간
`repo:kakaotechcampus-4@287968646/ktc4-kyungpook-2@1340154210:ref:refs/heads/develop`을 사용합니다.
GitHub 저장소의 OIDC 설정을 변경하면 신뢰 정책도 그 설정에 맞춰야 합니다.
`job.environment`는 지정하지 않으며 Deployments API의 환경 필드만 사용하므로
기존 `develop` 기반 OIDC subject를 유지합니다. 배포 job만 `deployments: write`를 갖고,
변경 감지 job은 `deployments: read`만 사용합니다.

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

패키지 게시 후 GitHub Packages에서 해당 계정에 **BE와 새 AI 패키지 모두** 읽기 접근 권한이
있는지도 확인합니다. 서버의 동일한 읽기 전용 PAT 로그인을 재사용하며 개발자별 토큰은 필요하지 않습니다.
토큰이 만료되면 같은 방식으로 서버에서 다시 로그인합니다.

### 배포와 복구

SSM은 Actions가 검증한 서비스별 배포 스크립트와 공통 helper 내용을 함께 전달하여
`ubuntu` 계정으로 실행합니다. 서버의 helper를 새로 읽지 않습니다.
루트 경로는 `/home/ubuntu/ktc4-kyungpook-2`, 브랜치는 `develop`입니다.

1. BE·AI 모두 기존 `.git/backend-deploy.lock`을 `flock -w 600`으로 최대 10분 기다립니다.
   타임아웃·수정 파일·다른 브랜치는 배포 실패입니다.
2. 잠금 획득 후 원격을 fetch하고 대상 SHA가 당시 `origin/develop`의 최신 SHA인지 확인합니다.
   뒤처진 실행은 컨테이너와 성공 기준을 변경하지 않고 원격 종료 코드 `75`로 종료합니다.
   호출 측은 `superseded`로 구분하고 배포 기록을 `inactive`로 남깁니다.
3. 정상 이력은 fast-forward합니다. 이력 재작성은 기존 HEAD가 이전 원격 이력에 속할 때만
   허용하고 `refs/deploy-backup/`에 보관한 뒤 `checkout --no-overwrite-ignore -B develop`으로 맞춥니다.
   서버 고유 커밋·수정 파일·무시된 파일 충돌은 덮어쓰지 않고 실패합니다. fast-forward도 무시된 파일을 보호합니다.
4. 기존 이미지를 복구용 로컬 태그로 보관하고 새 digest를 다운로드합니다.
   다운로드 실패는 실행 중인 서비스를 교체하지 않습니다.
5. `--no-deps --no-build --pull never`로 대상 서비스만 교체합니다. 상태 확인은 최대 120초입니다.
   AI는 `/health`의 `status=ok`, `luna_configured=true`를 검사하며 실제 모델 호출은 하지 않습니다.
   BE는 기존 직접 상태·Nginx 재로딩·프록시 상태·CORS·카카오 인가 콜백 검사를 유지합니다.
6. 실행 컨테이너의 digest 참조·실제 이미지 ID·전체 revision SHA를 검사하고 서버
   `infra/docker/.env`의 해당 `BACKEND_IMAGE` 또는 `AI_IMAGE`만 원자적으로 저장합니다.
   다른 값·파일 권한·`AI/.env`·평가 데이터 마운트를 보존합니다.
7. 교체 후 실패하면 이전 서비스 이미지를 복구하고 상태를 다시 검사합니다.
   AI 배포는 BE·FE·DB·Nginx를 재생성하지 않습니다. 복구 성공해도 해당 배포 기록은 실패입니다.

GitHub 배포 그룹은 `backend-develop-deploy`, `ai-develop-deploy`로 분리하고
`cancel-in-progress: false`를 유지합니다. 서비스 간 상호 배제는 서버 잠금이 담당합니다.
같은 서비스의 대기 실행이 교체되면 최신 실행이 성공 배포 기준부터 누적 변경을 다시 검사합니다.
SSM 명령 실행은 30분, 결과 대기는 33분, GitHub 배포 job은 40분으로 제한합니다.

GitHub의 BE 외부 API 확인만 실패하면 서버 배포 자체는 이미 완료됐을 수 있습니다.
네트워크와 SSM 결과를 함께 확인하고 해당 서비스의 `develop` 수동 워크플로로 다시 검증합니다.
단일 컨테이너 교체로 짧은 중단이 발생할 수 있으며, BE 이미지 복구는 DB 스키마를 되돌리지 않습니다.

### 최초 실행 및 확인

기능 브랜치에서 PR을 열어 `test` 작업을 확인한 뒤 `develop`에 병합합니다.
두 서비스의 기존 Deployments 기록이 없으므로 최초 적용은 BE·AI 각각 배포하여 기준을 만듭니다.
첫 push에서 두 이미지가 게시됩니다. 새 AI 패키지에 서버 PAT 계정의 읽기 권한을 확인합니다.
접근 오류로 배포가 실패하면 권한 수정 후 **Actions → AI CI/CD → Run workflow**에서
`develop`을 선택해 다시 실행합니다. BE도 **Backend CI/CD**에서 같은 방식으로 재배포합니다.
배포 후 각 서비스의 digest·revision·상태 응답과 Deployments `success` 기록을 함께 확인합니다.

본인 컴퓨터에서 다음 주소를 확인합니다.

```bash
curl -fsS http://54.116.206.217/api/health
```

`{"status":"ok"}`이면 외부 → Nginx → BE 경로가 정상입니다.
SSM 로그의 OAuth 검사는 인가 요청의 `redirect_uri`까지 검사하며, 카카오 콘솔 등록 및
사용자의 실제 로그인 성공 여부는 별도로 확인해야 합니다.

수동 배포도 같은 스크립트를 사용합니다. `<커밋 SHA>`와 `<이미지 digest>`를 실제 값으로 바꾸세요.

```bash
cd /home/ubuntu/ktc4-kyungpook-2
bash infra/scripts/deploy-backend.sh \
  '<커밋 SHA>' \
  'ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:<이미지 digest>'
```

서버에서 AI 상태·이미지를 확인합니다. 실제 운영 키는 출력하지 않습니다.

```bash
cd /home/ubuntu/ktc4-kyungpook-2
curl -fsS http://127.0.0.1:8000/health
docker inspect --format '{{.Config.Image}} {{ index .Config.Labels "org.opencontainers.image.revision" }}' \
  "$(docker compose -f infra/docker/compose.yaml ps -q ai)"
```

AI 수동 배포는 `bash infra/scripts/deploy-ai.sh <전체 SHA> <AI 이미지 digest>`를 사용합니다.
기존 BE 수동 인터페이스는 유지합니다. 직접 운영 이미지를 바꾼 뒤에는 해당 서비스의
`develop` 수동 워크플로를 실행해 Deployments 기록을 다시 동기화해야 합니다.

### 로컬 검증

```bash
for script in infra/scripts/deploy-{backend,ai,common}.sh; do bash -n "$script"; done
python3 -m unittest discover -s infra/scripts/tests -p 'test_*.py' -v
cd backend
./gradlew --no-daemon test integrationTest build
```

배포 테스트는 임시 Git 저장소·HTTP 서버·가짜 Docker로 서비스 격리, 다운로드·기동·상태·
digest/revision 오류 복구, 환경 파일 보존, Git 이력 재작성·무시된 파일 충돌 보호를 검사합니다.
Linux에서는 실제 `flock` 대기·타임아웃도 검사합니다. Deployments API를 모킹하여 페이지 조회,
현재 성공 상태, 누적 미배포 변경, 기록 갱신 실패를 검사합니다. 운영 서버나 DB에는 접근하지 않습니다.
AI 테스트 명령은 [AI 문서](../AI/README.md#cicd)를 참고하세요.
