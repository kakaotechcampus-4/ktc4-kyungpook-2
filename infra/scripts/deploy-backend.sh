#!/usr/bin/env bash
set -Eeuo pipefail

# SSM은 이 스크립트를 ubuntu 계정으로 실행한다. 비밀값을 출력하지 않는다.
commit=${1:?Usage: deploy-backend.sh COMMIT IMAGE_DIGEST}
image=${2:?Usage: deploy-backend.sh COMMIT IMAGE_DIGEST}
root=${DEPLOY_ROOT:-/home/ubuntu/ktc4-kyungpook-2}
origin=${PUBLIC_ORIGIN:-http://54.116.206.217}
health_timeout=${HEALTHCHECK_TIMEOUT:-120}
backend_health_url=${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}
proxy_base_url=${PROXY_BASE_URL:-http://127.0.0.1}
image_pattern='^ghcr\.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:[0-9a-f]{64}$'
[[ $commit =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 2; }
[[ $image =~ $image_pattern ]] || { echo 'An immutable backend GHCR image digest is required' >&2; exit 2; }
[[ $origin =~ ^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$ ]] || { echo 'Invalid public origin' >&2; exit 2; }
[[ $health_timeout =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid health-check timeout' >&2; exit 2; }
host=${origin#*://}
compose_dir=$root/infra/docker
env_file=$compose_dir/.env
rollback_required=0
rollback_image=''

compose() {
  docker compose --project-directory "$compose_dir" -f "$compose_dir/compose.yaml" "$@"
}

# shellcheck source=deploy-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/deploy-common.sh"

on_exit() {
  local code=$?
  trap - EXIT
  if ((code == 75 && deployment_superseded == 0)); then code=1; fi
  if ((code != 0 && rollback_required)); then
    echo 'Deployment failed; restoring previous backend image' >&2
    set +e
    local restored=1
    BACKEND_IMAGE=$rollback_image compose up -d --no-deps --no-build --pull never backend || restored=0
    wait_for_health "$backend_health_url" || restored=0
    verify_restored_image backend "$old_image" || restored=0
    compose exec -T nginx nginx -t && compose exec -T nginx nginx -s reload || restored=0
    wait_for_health "$proxy_base_url/api/health" || restored=0
    if ((restored)); then
      save_image_reference BACKEND_IMAGE "$rollback_image" || restored=0
    fi
    if ((restored)); then
      echo 'Previous backend restored; deployment remains failed' >&2
    else
      echo 'Rollback did not complete; inspect the backend and nginx on the server' >&2
    fi
  fi
  exit "$code"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

prepare_checkout "$commit"

BACKEND_IMAGE=$image compose config --quiet

backend_id=$(compose ps --all --quiet backend)
[[ -n $backend_id ]] || { echo 'An existing backend is required for rollback' >&2; exit 1; }
old_image=$(docker inspect --format '{{.Image}}' "$backend_id")
rollback_image="ktc-backend:rollback-${old_image#sha256:}"
docker tag "$old_image" "$rollback_image"

# GHCR 로그인은 서버의 ubuntu 계정에서 최초 한 번 준비한다.
# pull 실패는 현재 컨테이너에 영향을 주지 않는다.
BACKEND_IMAGE=$image compose pull backend
rollback_required=1
BACKEND_IMAGE=$image compose up -d --no-deps --no-build --pull never backend
wait_for_health "$backend_health_url"
verify_running_image backend "$image" "$commit"
compose exec -T nginx nginx -t
compose exec -T nginx nginx -s reload
wait_for_health "$proxy_base_url/api/health"

python3 - "$origin" "$host" "$proxy_base_url" <<'PY'
import sys
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

origin, host, proxy = sys.argv[1:]
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
opener = build_opener(NoRedirect)
headers = {"Host": host, "Origin": origin, "Access-Control-Request-Method": "GET"}
response = opener.open(Request(proxy + "/api/health", headers=headers, method="OPTIONS"), timeout=10)
if response.status != 200 or response.headers.get("Access-Control-Allow-Origin") != origin:
    raise SystemExit("CORS verification failed")
try:
    response = opener.open(Request(proxy + "/oauth2/authorization/kakao", headers={"Host": host}), timeout=10)
except HTTPError as error:
    response = error
callback = parse_qs(urlparse(response.headers.get("Location", "")).query).get("redirect_uri", [""])[0]
if response.code != 302 or callback != origin + "/login/oauth2/code/kakao":
    raise SystemExit("OAuth redirect URI verification failed")
print("CORS and OAuth callback verified")
PY

save_image_reference BACKEND_IMAGE "$image"
rollback_required=0
echo "Backend deployment successful: $image"
