#!/usr/bin/env bash
set -Eeuo pipefail

commit=${1:?Usage: deploy-ai.sh COMMIT IMAGE_DIGEST}
image=${2:?Usage: deploy-ai.sh COMMIT IMAGE_DIGEST}
root=${DEPLOY_ROOT:-/home/ubuntu/ktc4-kyungpook-2}
health_timeout=${HEALTHCHECK_TIMEOUT:-120}
ai_health_url=${AI_HEALTH_URL:-http://127.0.0.1:8000/health}
compose_dir=$root/infra/docker
env_file=$compose_dir/.env
rollback_required=0
rollback_image=''
# shellcheck source=deploy-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/deploy-common.sh"

compose() {
  docker compose --project-directory "$compose_dir" -f "$compose_dir/compose.yaml" "$@"
}

on_exit() {
  local code=$?
  trap - EXIT
  # Reserve 75 exclusively for a superseded checkout, never a tool failure.
  if ((code == 75 && deployment_superseded == 0)); then code=1; fi
  if ((code != 0 && rollback_required)); then
    echo 'Deployment failed; restoring previous AI image' >&2
    set +e
    local restored=1
    AI_IMAGE=$rollback_image compose up -d --no-deps --no-build --pull never ai || restored=0
    wait_for_health "$ai_health_url" ai || restored=0
    verify_restored_image ai "$old_image" || restored=0
    if ((restored)); then
      save_image_reference AI_IMAGE "$rollback_image" || restored=0
    fi
    if ((restored)); then
      echo 'Previous AI restored; deployment remains failed' >&2
    else
      echo 'Rollback did not complete; inspect AI on the server' >&2
    fi
  fi
  exit "$code"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

validate_image ai "$image"
prepare_checkout "$commit"
AI_IMAGE=$image compose config --quiet
ai_id=$(compose ps --all --quiet ai)
[[ -n $ai_id ]] || { echo 'An existing AI is required for rollback' >&2; exit 1; }
old_image=$(docker inspect --format '{{.Image}}' "$ai_id")
rollback_image="ktc-ai:rollback-${old_image#sha256:}"
docker tag "$old_image" "$rollback_image"

AI_IMAGE=$image compose pull ai
rollback_required=1
AI_IMAGE=$image compose up -d --no-deps --no-build --pull never ai
wait_for_health "$ai_health_url" ai
verify_running_image ai "$image" "$commit"
save_image_reference AI_IMAGE "$image"
rollback_required=0
echo "AI deployment successful: $image"
