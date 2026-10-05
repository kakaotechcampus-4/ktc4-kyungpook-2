#!/usr/bin/env bash
set -Eeuo pipefail

# SSM은 이 스크립트를 ubuntu 계정으로 실행한다. 비밀값을 출력하지 않는다.
commit=${1:?Usage: deploy-backend.sh COMMIT IMAGE_DIGEST}
image=${2:?Usage: deploy-backend.sh COMMIT IMAGE_DIGEST}
root=${DEPLOY_ROOT:-/home/ubuntu/ktc4-kyungpook-2}
expected_origin=${EXPECTED_PUBLIC_ORIGIN:?EXPECTED_PUBLIC_ORIGIN is required}
health_timeout=${HEALTHCHECK_TIMEOUT:-120}
lock_timeout=${DEPLOY_LOCK_TIMEOUT:-300}
backend_health_url=${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}
proxy_port=${PROXY_PORT:-}
proxy_ca_bundle=${PROXY_CA_BUNDLE:-}
image_pattern='^ghcr\.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:[0-9a-f]{64}$'
[[ $commit =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 2; }
[[ $image =~ $image_pattern ]] || { echo 'An immutable backend GHCR image digest is required' >&2; exit 2; }
[[ $expected_origin =~ ^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$ ]] || { echo 'Invalid public origin' >&2; exit 2; }
[[ $health_timeout =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid health-check timeout' >&2; exit 2; }
[[ $lock_timeout =~ ^[0-9]+$ ]] || { echo 'Invalid lock timeout' >&2; exit 2; }
compose_dir=$root/infra/docker
env_file=$compose_dir/.env
verifier=$root/infra/scripts/verify-proxy.py
origin=''
rollback_required=0
rollback_image=''
caddy_snapshot=''
caddy_candidate=''
caddy_changed=0
caddy_reload_attempted=0
previous_head=''
checkout_moved=0

# .github/workflows/backend-ci-cd.yml의 paths와 같게 유지한다. 테스트가 일치 여부를 검사한다.
# 이 경로가 바뀐 커밋은 BE 워크플로를 실행시키므로, 더 새 BE 배포가 뒤따른다.
service_paths=(
  'backend/**'
  'infra/**'
  '.github/workflows/backend-ci-cd.yml'
  '!backend/**/*.md'
  '!infra/**/*.md'
  '!infra/scripts/deploy-ai.sh'
  '!infra/scripts/verify-ai.py'
  '!infra/scripts/tests/test_ai_deploy.py'
)

compose() {
  # GitHub supplies an expectation, never a Compose override of the server .env.
  env -u PUBLIC_ORIGIN -u AUTH_COOKIE_SECURE -u CORS_ALLOWED_ORIGINS \
    docker compose --env-file "$env_file" --project-directory "$compose_dir" \
    -f "$compose_dir/compose.yaml" "$@"
}

verify_proxy() {
  local options=(--origin "$origin" --local)
  [[ -z $proxy_port ]] || options+=(--port "$proxy_port")
  [[ -z $proxy_ca_bundle ]] || options+=(--ca-bundle "$proxy_ca_bundle")
  python3 "$verifier" "$1" "${options[@]}"
}

check_settings() {
  local caddy_id running_origin
  caddy_id=$(compose ps --quiet caddy)
  [[ -n $caddy_id ]] || { echo 'Complete the initial Caddy migration before deployment' >&2; return 1; }
  running_origin=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$caddy_id" \
    | sed -n 's/^PUBLIC_ORIGIN=//p')
  compose config --format json | python3 "$verifier" settings \
    --origin "$expected_origin" --running-origin "$running_origin"
}

wait_for_health() {
  local url=$1 body deadline=$((SECONDS + health_timeout))
  while ((SECONDS < deadline)); do
    if body=$(curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
      --write-out '\n%{http_code}' "$url" 2>/dev/null) && \
      printf '%s' "$body" | python3 -c 'import json,sys; body,code=sys.stdin.read().rsplit("\n",1); assert code == "200" and json.loads(body) == {"status": "ok"}' 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "Health check timed out: $url" >&2
  return 1
}

wait_for_proxy() {
  local deadline=$((SECONDS + health_timeout))
  while ((SECONDS < deadline)); do
    verify_proxy health 2>/dev/null && return 0
    sleep 2
  done
  echo 'Proxy health check timed out' >&2
  return 1
}

update_checkout() {
  # 실행 중인 BE보다 오래된 커밋이나 더 새 BE·infra 변경을 덮는 커밋은 배포하지 않는다.
  local running_revision=$1 path pathspecs=()
  git merge-base --is-ancestor "$commit" origin/develop || { echo 'Commit is not on origin/develop' >&2; return 1; }
  # 커밋 라벨이 없는 기존 이미지는 비교할 수 없어 통과시킨다.
  if [[ $running_revision =~ ^[0-9a-f]{40}$ && $running_revision != "$commit" ]] && \
    git merge-base --is-ancestor "$commit" "$running_revision" 2>/dev/null; then
    echo 'Refusing to deploy a commit older than the running backend' >&2
    return 1
  fi
  if git merge-base --is-ancestor HEAD "$commit"; then
    # compose.yaml·Caddyfile은 checkout에서 읽어 검증하므로 먼저 옮긴다. 실패하면 on_exit가 되돌린다.
    previous_head=$(git rev-parse HEAD)
    git merge --ff-only --quiet "$commit"
    checkout_moved=1
    return 0
  fi
  if ! git merge-base --is-ancestor "$commit" HEAD; then
    echo 'Server HEAD and the deployment commit have diverged' >&2
    return 1
  fi
  # 다른 서비스 배포가 checkout을 먼저 옮겼다. 그 사이 BE 변경이 없을 때만 그대로 쓴다.
  for path in "${service_paths[@]}"; do
    if [[ $path == '!'* ]]; then
      pathspecs+=(":(exclude,glob)${path#!}")
    else
      pathspecs+=(":(glob)$path")
    fi
  done
  if ! git diff --quiet "$commit" HEAD -- "${pathspecs[@]}"; then
    echo 'Server checkout has newer backend changes; the run for that commit deploys them' >&2
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
    if re.match(r"^\s*(?:export\s+)?BACKEND_IMAGE\s*=", line):
        if not written:
            result.append(f"BACKEND_IMAGE={value}\n")
            written = True
    else:
        result.append(line)
if not written:
    if result and not result[-1].endswith("\n"):
        result[-1] += "\n"
    result.append(f"BACKEND_IMAGE={value}\n")
mode = stat.S_IMODE(path.stat().st_mode)
fd, temporary = tempfile.mkstemp(prefix=".env.backend-", dir=path.parent)
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
    # 컨테이너는 바뀌지 않았다. 재시작한 Caddy가 검증 안 된 Caddyfile을 읽지 않게 checkout만 되돌린다.
    restore_checkout || echo 'Could not restore the server checkout; inspect git on the server' >&2
  fi
  if ((code != 0 && rollback_required)); then
    echo 'Deployment failed; restoring previous backend image' >&2
    set +e
    local restored=1
    # 이전 이미지는 이전 compose.yaml로 띄워야 하므로 checkout부터 되돌린다.
    restore_checkout || restored=0
    BACKEND_IMAGE=$rollback_image compose up -d --no-deps --no-build --pull never backend || restored=0
    wait_for_health "$backend_health_url" || restored=0
    if ((caddy_reload_attempted)); then
      # A failed reload may have applied the config; restore the saved active JSON.
      compose exec -T caddy sh -c 'umask 077; cat > /tmp/backend-deploy-rollback.json' \
        < "$caddy_snapshot" && \
        compose exec -T caddy caddy reload --config /tmp/backend-deploy-rollback.json || restored=0
    fi
    wait_for_proxy || restored=0
    verify_proxy verify || restored=0
    if ((restored)); then
      save_image_reference "$rollback_image" || restored=0
    fi
    if ((restored)); then
      echo 'Previous backend restored; deployment remains failed' >&2
    else
      echo 'Rollback did not complete; inspect the backend and Caddy on the server' >&2
    fi
  fi
  if [[ -n $caddy_snapshot ]]; then
    rm -f "$caddy_snapshot"
  fi
  [[ -z $caddy_candidate ]] || rm -f "$caddy_candidate"
  if ((caddy_reload_attempted)); then
    compose exec -T caddy rm -f /tmp/backend-deploy-candidate.json /tmp/backend-deploy-rollback.json >/dev/null 2>&1 || true
  fi
  exit "$code"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in git docker curl python3 flock; do
  command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }
done
[[ -r $env_file && -w $env_file ]] || { echo 'Server Compose .env must be readable and writable' >&2; exit 1; }
cd "$root"
git_dir=$(git rev-parse --absolute-git-dir)
# AI 배포와 같은 잠금을 쓴다. 둘 다 서버 checkout을 갱신한다.
exec 9> "$git_dir/deploy.lock"
flock -w "$lock_timeout" 9 || { echo 'Timed out waiting for another deployment' >&2; exit 1; }
[[ -z $(git status --porcelain) ]] || { echo 'Server working tree is not clean' >&2; exit 1; }
[[ $(git branch --show-current) == develop ]] || { echo 'Server must be on develop' >&2; exit 1; }
[[ -r $verifier ]] || { echo 'Complete the initial Caddy migration before deployment' >&2; exit 1; }
origin=$(check_settings)
backend_id=$(compose ps --all --quiet backend)
[[ -n $backend_id ]] || { echo 'An existing backend is required for rollback' >&2; exit 1; }
running_revision=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$backend_id")

echo "Preparing deployment of $commit"
git fetch --quiet origin develop
update_checkout "$running_revision"
BACKEND_IMAGE=$image compose config --quiet
origin=$(check_settings)
# Validate and compare effective JSON, ignoring Caddyfile comments and JSON formatting.
caddy_candidate=$(mktemp "$git_dir/caddy-candidate.XXXXXX")
compose exec -T caddy caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile --validate > "$caddy_candidate"
caddy_snapshot=$(mktemp "$git_dir/caddy-active.XXXXXX")
compose exec -T caddy wget -qO- http://127.0.0.1:2019/config/ > "$caddy_snapshot"
caddy_changed=$(python3 - "$caddy_snapshot" "$caddy_candidate" <<'PY'
import json, pathlib, sys
active, candidate = [json.loads(pathlib.Path(path).read_text()) for path in sys.argv[1:]]
assert isinstance(active, dict) and isinstance(candidate, dict)
print(int(active != candidate))
PY
)

old_image=$(docker inspect --format '{{.Image}}' "$backend_id")
rollback_image="ktc-backend:rollback-${old_image#sha256:}"
docker tag "$old_image" "$rollback_image"

# GHCR 로그인은 서버의 ubuntu 계정에서 최초 한 번 준비한다.
# pull 실패는 현재 컨테이너에 영향을 주지 않는다.
BACKEND_IMAGE=$image compose pull backend
rollback_required=1
BACKEND_IMAGE=$image compose up -d --no-deps --no-build --pull never backend
wait_for_health "$backend_health_url"
running_id=$(compose ps --quiet backend)
[[ $(docker inspect --format '{{.Config.Image}}' "$running_id") == "$image" ]] || { echo 'Unexpected running backend image' >&2; exit 1; }
if ((caddy_changed)); then
  caddy_reload_attempted=1
  compose exec -T caddy sh -c 'umask 077; cat > /tmp/backend-deploy-candidate.json' < "$caddy_candidate"
  compose exec -T caddy caddy reload --config /tmp/backend-deploy-candidate.json
else
  echo 'Caddy configuration unchanged; skipping reload'
fi
wait_for_proxy
verify_proxy verify
echo 'CORS and OAuth callback verified'

save_image_reference "$image"
rollback_required=0
echo "Backend deployment successful: $image"
