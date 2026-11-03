#!/usr/bin/env bash
set -Eeuo pipefail

# SSM은 이 스크립트를 ubuntu 계정으로 실행한다. 비밀값을 출력하지 않는다.
# 프론트 컨테이너만 교체한다. DB·BE·AI·Caddy는 건드리지 않는다.
#
# AI·BE와 달리 프론트는 예전에 서버에서 직접 빌드했다(#270). 지금은 CI가 GHCR에 이미지를 올리고
# 서버는 pull만 한다 — EC2 가용 메모리가 2.5Gi뿐이라 Vite 빌드가 자동 배포 중 OOM으로 죽으면
# 프론트가 내려간 채 남는다. 번들에 박히는 VITE_* 의 진실의 원천도 그래서 서버 .env 가 아니라
# GitHub repo variables 다.
commit=${1:?Usage: deploy-frontend.sh COMMIT IMAGE_DIGEST}
image=${2:?Usage: deploy-frontend.sh COMMIT IMAGE_DIGEST}
root=${DEPLOY_ROOT:-/home/ubuntu/ktc4-kyungpook-2}
health_timeout=${HEALTHCHECK_TIMEOUT:-60}
lock_timeout=${DEPLOY_LOCK_TIMEOUT:-300}
frontend_url=${FRONTEND_URL:-http://127.0.0.1:3000}
image_pattern='^ghcr\.io/kakaotechcampus-4/ktc4-kyungpook-2-frontend@sha256:[0-9a-f]{64}$'
[[ $commit =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 2; }
[[ $image =~ $image_pattern ]] || { echo 'An immutable frontend GHCR image digest is required' >&2; exit 2; }
[[ $health_timeout =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid health-check timeout' >&2; exit 2; }
[[ $lock_timeout =~ ^[0-9]+$ ]] || { echo 'Invalid lock timeout' >&2; exit 2; }
compose_dir=$root/infra/docker
env_file=$compose_dir/.env
rollback_required=0
rollback_image=''
previous_head=''
checkout_moved=0

# .github/workflows/frontend-ci.yml의 paths와 같게 유지한다. 테스트가 일치 여부를 검사한다.
# 이 경로가 바뀐 커밋은 프론트 워크플로를 실행시키므로, 더 새 프론트 배포가 뒤따른다.
service_paths=(
  'frontend/**'
  'infra/docker/compose.yaml'
  'infra/scripts/deploy-frontend.sh'
  'infra/scripts/send-deploy.py'
  'infra/scripts/tests/test_frontend_deploy.py'
  '.github/workflows/frontend-ci.yml'
  '!frontend/**/*.md'
)

compose() {
  docker compose --env-file "$env_file" --project-directory "$compose_dir" \
    -f "$compose_dir/compose.yaml" "$@"
}

# 정적 파일을 주는 nginx라 상태 끝점이 없다. 빌드 결과가 실제로 들어 있는지를 본다 —
# 기본 nginx 안내 페이지도 200 을 주므로 상태 코드만으로는 교체 실패를 못 잡는다.
check_frontend() {
  python3 - "$frontend_url" <<'PY'
import sys, urllib.request
url = sys.argv[1].rstrip("/") + "/"
request = urllib.request.Request(url, headers={"Accept": "text/html"})
with urllib.request.urlopen(request, timeout=5) as response:
    if response.status != 200:
        sys.exit("status " + str(response.status))
    body = response.read(16384).decode("utf-8", "replace")
if "<script" not in body:
    sys.exit("index.html has no bundle script; the image may not hold a build")
PY
}

wait_for_frontend() {
  local deadline=$((SECONDS + health_timeout))
  while ((SECONDS < deadline)); do
    check_frontend 2>/dev/null && return 0
    sleep 2
  done
  # 마지막 실패 원인을 한 번 남긴다.
  check_frontend && return 0
  echo "Frontend check timed out: $frontend_url" >&2
  return 1
}

update_checkout() {
  # 실행 중인 프론트보다 오래된 커밋이나 더 새 프론트 변경을 덮는 커밋은 배포하지 않는다.
  local running_revision=$1 path pathspecs=()
  git merge-base --is-ancestor "$commit" origin/develop || { echo 'Commit is not on origin/develop' >&2; return 1; }
  # 커밋 라벨이 없는 기존 이미지는 비교할 수 없어 통과시킨다.
  if [[ $running_revision =~ ^[0-9a-f]{40}$ && $running_revision != "$commit" ]] && \
    git merge-base --is-ancestor "$commit" "$running_revision" 2>/dev/null; then
    echo 'Refusing to deploy a commit older than the running frontend' >&2
    return 1
  fi
  if git merge-base --is-ancestor HEAD "$commit"; then
    # compose.yaml은 checkout에서 읽으므로 먼저 옮긴다. 실패하면 on_exit가 되돌린다.
    previous_head=$(git rev-parse HEAD)
    git merge --ff-only --quiet "$commit"
    checkout_moved=1
    return 0
  fi
  if ! git merge-base --is-ancestor "$commit" HEAD; then
    echo 'Server HEAD and the deployment commit have diverged' >&2
    return 1
  fi
  # 다른 서비스 배포가 checkout을 먼저 옮겼다. 그 사이 프론트 변경이 없을 때만 그대로 쓴다.
  for path in "${service_paths[@]}"; do
    if [[ $path == '!'* ]]; then
      pathspecs+=(":(exclude,glob)${path#!}")
    else
      pathspecs+=(":(glob)$path")
    fi
  done
  if ! git diff --quiet "$commit" HEAD -- "${pathspecs[@]}"; then
    echo 'Server checkout has newer frontend changes; the run for that commit deploys them' >&2
    return 1
  fi
  echo "Server checkout already contains $commit; keeping $(git rev-parse --short HEAD)"
}

restore_checkout() {
  # 이 배포가 옮긴 checkout만 되돌린다. 다른 서비스 배포가 옮긴 checkout은 그대로 둔다.
  ((checkout_moved)) || return 0
  git reset --quiet --keep "$previous_head" && checkout_moved=0
}

save_image_reference() {
  # .env의 다른 줄과 파일 권한을 보존하고, 이미지 식별자만 원자적으로 갱신한다.
  python3 - "$env_file" "$1" <<'PY'
import os, pathlib, re, stat, sys, tempfile
path = pathlib.Path(sys.argv[1])
value = sys.argv[2]
lines = path.read_text().splitlines(keepends=True)
result = []
written = False
for line in lines:
    if re.match(r"^\s*(?:export\s+)?FRONTEND_IMAGE\s*=", line):
        if not written:
            result.append(f"FRONTEND_IMAGE={value}\n")
            written = True
    else:
        result.append(line)
if not written:
    if result and not result[-1].endswith("\n"):
        result[-1] += "\n"
    result.append(f"FRONTEND_IMAGE={value}\n")
mode = stat.S_IMODE(path.stat().st_mode)
fd, temporary = tempfile.mkstemp(prefix=".env.frontend-", dir=path.parent)
try:
    with os.fdopen(fd, "w") as output:
        output.writelines(result)
    os.chmod(temporary, mode)
    os.replace(temporary, path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
}

on_exit() {
  local code=$?
  trap - EXIT
  if ((code != 0 && !rollback_required)); then
    set +e
    # 컨테이너는 바뀌지 않았다. 실패한 배포의 checkout이 서버에 남지 않게 되돌린다.
    restore_checkout || echo 'Could not restore the server checkout; inspect git on the server' >&2
  fi
  if ((code != 0 && rollback_required)); then
    echo 'Deployment failed; restoring previous frontend image' >&2
    set +e
    local restored=1
    # 이전 이미지는 이전 compose.yaml로 띄워야 하므로 checkout부터 되돌린다.
    restore_checkout || restored=0
    FRONTEND_IMAGE=$rollback_image compose up -d --no-deps --no-build --pull never frontend || restored=0
    wait_for_frontend || restored=0
    if ((restored)); then
      save_image_reference "$rollback_image" || restored=0
    fi
    if ((restored)); then
      echo 'Previous frontend restored; deployment remains failed' >&2
    else
      echo 'Rollback did not complete; inspect the frontend container on the server' >&2
    fi
  fi
  exit "$code"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in git docker python3 flock; do
  command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }
done
[[ -r $env_file && -w $env_file ]] || { echo 'Server Compose .env must be readable and writable' >&2; exit 1; }
cd "$root"
git_dir=$(git rev-parse --absolute-git-dir)
# BE·AI 배포와 같은 잠금을 쓴다. 셋 다 서버 checkout을 갱신한다.
exec 9> "$git_dir/deploy.lock"
flock -w "$lock_timeout" 9 || { echo 'Timed out waiting for another deployment' >&2; exit 1; }
[[ -z $(git status --porcelain) ]] || { echo 'Server working tree is not clean' >&2; exit 1; }
[[ $(git branch --show-current) == develop ]] || { echo 'Server must be on develop' >&2; exit 1; }

frontend_id=$(compose ps --all --quiet frontend)
[[ -n $frontend_id ]] || { echo 'An existing frontend container is required for rollback' >&2; exit 1; }
running_revision=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$frontend_id")

echo "Preparing frontend deployment of $commit"
git fetch --quiet origin develop
update_checkout "$running_revision"
FRONTEND_IMAGE=$image compose config --quiet

old_image=$(docker inspect --format '{{.Image}}' "$frontend_id")
rollback_image="ktc-frontend:rollback-${old_image#sha256:}"
docker tag "$old_image" "$rollback_image"

# GHCR 로그인은 서버의 ubuntu 계정에서 최초 한 번 준비한다.
# pull 실패는 현재 컨테이너에 영향을 주지 않는다.
FRONTEND_IMAGE=$image compose pull frontend
rollback_required=1
FRONTEND_IMAGE=$image compose up -d --no-deps --no-build --pull never frontend
wait_for_frontend
running_id=$(compose ps --quiet frontend)
[[ $(docker inspect --format '{{.Config.Image}}' "$running_id") == "$image" ]] || { echo 'Unexpected running frontend image' >&2; exit 1; }

save_image_reference "$image"
rollback_required=0
echo "Frontend deployment successful: $image"
