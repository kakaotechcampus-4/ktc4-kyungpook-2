#!/usr/bin/env bash
set -Eeuo pipefail

# SSM은 이 스크립트를 ubuntu 계정으로 실행한다. 비밀값을 출력하지 않는다.
# AI 컨테이너만 교체한다. DB·BE·FE·Caddy와 AI/.env·AI/evals 마운트는 건드리지 않는다.
commit=${1:?Usage: deploy-ai.sh COMMIT IMAGE_DIGEST}
image=${2:?Usage: deploy-ai.sh COMMIT IMAGE_DIGEST}
root=${DEPLOY_ROOT:-/home/ubuntu/ktc4-kyungpook-2}
health_timeout=${HEALTHCHECK_TIMEOUT:-60}
lock_timeout=${DEPLOY_LOCK_TIMEOUT:-300}
ai_url=${AI_URL:-http://127.0.0.1:8000}
image_pattern='^ghcr\.io/kakaotechcampus-4/ktc4-kyungpook-2-ai@sha256:[0-9a-f]{64}$'
[[ $commit =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 2; }
[[ $image =~ $image_pattern ]] || { echo 'An immutable AI GHCR image digest is required' >&2; exit 2; }
[[ $health_timeout =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid health-check timeout' >&2; exit 2; }
[[ $lock_timeout =~ ^[0-9]+$ ]] || { echo 'Invalid lock timeout' >&2; exit 2; }
compose_dir=$root/infra/docker
env_file=$compose_dir/.env
ai_env_file=$root/AI/.env
verifier=$root/infra/scripts/verify-ai.py
rollback_required=0
rollback_image=''
previous_head=''
checkout_moved=0

# .github/workflows/ai-ci-cd.yml의 paths와 같게 유지한다. 테스트가 일치 여부를 검사한다.
# 이 경로가 바뀐 커밋은 AI 워크플로를 실행시키므로, 더 새 AI 배포가 뒤따른다.
service_paths=(
  'AI/**'
  'infra/docker/compose.yaml'
  'infra/scripts/deploy-ai.sh'
  'infra/scripts/verify-ai.py'
  'infra/scripts/send-deploy.py'
  'infra/scripts/tests/test_ai_deploy.py'
  '.github/workflows/ai-ci-cd.yml'
  '!AI/**/*.md'
  '!AI/evals/**'
)

compose() {
  docker compose --env-file "$env_file" --project-directory "$compose_dir" \
    -f "$compose_dir/compose.yaml" "$@"
}

wait_for_ai() {
  local deadline=$((SECONDS + health_timeout))
  while ((SECONDS < deadline)); do
    python3 "$verifier" --url "$ai_url" "$@" 2>/dev/null && return 0
    sleep 2
  done
  # 마지막 실패 원인을 한 번 남긴다.
  python3 "$verifier" --url "$ai_url" "$@" && return 0
  echo "AI check timed out: $ai_url" >&2
  return 1
}

update_checkout() {
  # 실행 중인 AI보다 오래된 커밋이나 더 새 AI 변경을 덮는 커밋은 배포하지 않는다.
  local running_revision=$1 path pathspecs=()
  git merge-base --is-ancestor "$commit" origin/develop || { echo 'Commit is not on origin/develop' >&2; return 1; }
  # 커밋 라벨이 없는 기존 이미지는 비교할 수 없어 통과시킨다.
  if [[ $running_revision =~ ^[0-9a-f]{40}$ && $running_revision != "$commit" ]] && \
    git merge-base --is-ancestor "$commit" "$running_revision" 2>/dev/null; then
    echo 'Refusing to deploy a commit older than the running AI' >&2
    return 1
  fi
  if git merge-base --is-ancestor HEAD "$commit"; then
    # compose.yaml·검증 스크립트는 checkout에서 읽으므로 먼저 옮긴다. 실패하면 on_exit가 되돌린다.
    previous_head=$(git rev-parse HEAD)
    git merge --ff-only --quiet "$commit"
    checkout_moved=1
    return 0
  fi
  if ! git merge-base --is-ancestor "$commit" HEAD; then
    echo 'Server HEAD and the deployment commit have diverged' >&2
    return 1
  fi
  # 다른 서비스 배포가 checkout을 먼저 옮겼다. 그 사이 AI 변경이 없을 때만 그대로 쓴다.
  for path in "${service_paths[@]}"; do
    if [[ $path == '!'* ]]; then
      pathspecs+=(":(exclude,glob)${path#!}")
    else
      pathspecs+=(":(glob)$path")
    fi
  done
  if ! git diff --quiet "$commit" HEAD -- "${pathspecs[@]}"; then
    echo 'Server checkout has newer AI changes; the run for that commit deploys them' >&2
    return 1
  fi
  echo "Server checkout already contains $commit; keeping $(git rev-parse --short HEAD)"
}

restore_checkout() {
  # 이 배포가 옮긴 checkout만 되돌린다. 다른 서비스 배포가 옮긴 checkout은 그대로 둔다.
  ((checkout_moved)) || return 0
  git reset --quiet --keep "$previous_head" && checkout_moved=0
}

check_luna_settings() {
  [[ -r $ai_env_file ]] || { echo 'Server AI/.env must be readable' >&2; return 1; }
  python3 - "$ai_env_file" <<'PY'
import pathlib, re, sys
values = {}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", line)
    if match:
        values[match.group(1)] = match.group(2).strip().strip("\"'")
missing = [key for key in ("LUNA_API_URL", "LUNA_API_KEY") if not values.get(key)]
if missing:
    sys.exit("Set " + ", ".join(missing) + " in AI/.env on the server")
PY
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
    if re.match(r"^\s*(?:export\s+)?AI_IMAGE\s*=", line):
        if not written:
            result.append(f"AI_IMAGE={value}\n")
            written = True
    else:
        result.append(line)
if not written:
    if result and not result[-1].endswith("\n"):
        result[-1] += "\n"
    result.append(f"AI_IMAGE={value}\n")
mode = stat.S_IMODE(path.stat().st_mode)
fd, temporary = tempfile.mkstemp(prefix=".env.ai-", dir=path.parent)
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
    echo 'Deployment failed; restoring previous AI image' >&2
    set +e
    local restored=1
    # 이전 이미지는 이전 compose.yaml로 띄워야 하므로 checkout부터 되돌린다.
    restore_checkout || restored=0
    AI_IMAGE=$rollback_image compose up -d --no-deps --no-build --pull never ai || restored=0
    wait_for_ai || restored=0
    if ((restored)); then
      save_image_reference "$rollback_image" || restored=0
    fi
    if ((restored)); then
      echo 'Previous AI restored; deployment remains failed' >&2
    else
      echo 'Rollback did not complete; inspect the AI container on the server' >&2
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
# BE 배포와 같은 잠금을 쓴다. 둘 다 서버 checkout을 갱신한다.
exec 9> "$git_dir/deploy.lock"
flock -w "$lock_timeout" 9 || { echo 'Timed out waiting for another deployment' >&2; exit 1; }
[[ -z $(git status --porcelain) ]] || { echo 'Server working tree is not clean' >&2; exit 1; }
[[ $(git branch --show-current) == develop ]] || { echo 'Server must be on develop' >&2; exit 1; }

ai_id=$(compose ps --all --quiet ai)
[[ -n $ai_id ]] || { echo 'An existing AI container is required for rollback' >&2; exit 1; }
running_revision=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$ai_id")

echo "Preparing AI deployment of $commit"
git fetch --quiet origin develop
update_checkout "$running_revision"
[[ -r $verifier ]] || { echo 'Server checkout is missing infra/scripts/verify-ai.py' >&2; exit 1; }
check_luna_settings
AI_IMAGE=$image compose config --quiet

old_image=$(docker inspect --format '{{.Image}}' "$ai_id")
rollback_image="ktc-ai:rollback-${old_image#sha256:}"
docker tag "$old_image" "$rollback_image"

# GHCR 로그인은 서버의 ubuntu 계정에서 최초 한 번 준비한다.
# pull 실패는 현재 컨테이너에 영향을 주지 않는다.
AI_IMAGE=$image compose pull ai
rollback_required=1
AI_IMAGE=$image compose up -d --no-deps --no-build --pull never ai
wait_for_ai --require-luna
running_id=$(compose ps --quiet ai)
[[ $(docker inspect --format '{{.Config.Image}}' "$running_id") == "$image" ]] || { echo 'Unexpected running AI image' >&2; exit 1; }

save_image_reference "$image"
rollback_required=0
echo "AI deployment successful: $image"
