#!/usr/bin/env bash
# BE와 AI가 공유한다. SSM 전달 시에도 이 파일의 내용을 함께 전달한다.
# root, env_file, health_timeout are supplied by each service wrapper.
# shellcheck disable=SC2154
deployment_superseded=0

validate_image() {
  local service=$1 image=$2
  local pattern="^ghcr\\.io/kakaotechcampus-4/ktc4-kyungpook-2-${service}@sha256:[0-9a-f]{64}$"
  [[ $image =~ $pattern ]] || { echo 'An immutable service GHCR image digest is required' >&2; exit 2; }
}

prepare_checkout() {
  local commit=$1 previous_remote
  [[ $commit =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 2; }
  [[ $health_timeout =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid health-check timeout' >&2; exit 2; }
  [[ ${DEPLOY_LOCK_TIMEOUT:-600} =~ ^[0-9]+$ ]] || { echo 'Invalid lock timeout' >&2; exit 2; }
  for tool in git docker curl python3 flock; do
    command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }
  done
  [[ -r $env_file && -w $env_file ]] || { echo 'Server Compose .env must be readable and writable' >&2; exit 1; }
  cd "$root" || exit 1
  exec 9> "$(git rev-parse --absolute-git-dir)/backend-deploy.lock"
  flock -w "${DEPLOY_LOCK_TIMEOUT:-600}" 9 || { echo 'Deployment lock timed out' >&2; exit 1; }
  [[ -z $(git status --porcelain) ]] || { echo 'Server working tree is not clean' >&2; exit 1; }
  [[ $(git branch --show-current) == develop ]] || { echo 'Server must be on develop' >&2; exit 1; }
  previous_remote=$(git rev-parse --verify refs/remotes/origin/develop 2>/dev/null || true)
  git fetch --quiet origin +refs/heads/develop:refs/remotes/origin/develop
  if [[ $commit != "$(git rev-parse origin/develop)" ]]; then
    echo 'Deployment superseded: commit is older than server HEAD or current origin/develop' >&2
    # Read by the service wrapper's EXIT trap.
    # shellcheck disable=SC2034
    deployment_superseded=1
    exit 75
  fi
  if git merge-base --is-ancestor HEAD "$commit"; then
    git merge --ff-only --no-overwrite-ignore --quiet "$commit"
  else
    # 이전에 원격 이력에 포함됐던 HEAD만 이력 재작성으로 취급한다.
    if [[ -z $previous_remote ]] || ! git merge-base --is-ancestor HEAD "$previous_remote"; then
      echo 'Server has local commits; refusing to overwrite them' >&2; exit 1;
    fi
    git update-ref "refs/deploy-backup/$(date -u +%Y%m%dT%H%M%S)-$$" HEAD
    git checkout --quiet --no-overwrite-ignore -B develop "$commit"
  fi
  [[ $(git rev-parse HEAD) == "$commit" ]] || { echo 'Server commit does not match deployment commit' >&2; exit 1; }
}

wait_for_health() {
  local url=$1 service=${2:-backend} body deadline=$((SECONDS + health_timeout))
  while ((SECONDS < deadline)); do
    if body=$(curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
      -H "Host: ${host:-localhost}" "$url" 2>/dev/null) &&
      printf '%s' "$body" | python3 -c '
import json, sys
body = json.load(sys.stdin)
assert body == {"status": "ok"} if sys.argv[1] == "backend" else body.get("status") == "ok" and body.get("luna_configured") is True
' "$service" 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "Health check timed out: $url" >&2
  return 1
}

verify_running_image() {
  local service=$1 image=$2 commit=$3 running_id
  running_id=$(compose ps --quiet "$service")
  [[ -n $running_id ]] && [[ $(docker inspect --format '{{.Config.Image}}' "$running_id") == "$image" ]] || {
    echo 'Unexpected running service image' >&2; return 1;
  }
  [[ $(docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$running_id") == "$commit" ]] || {
    echo 'Unexpected image revision' >&2; return 1;
  }
  [[ $(docker inspect --format '{{.Image}}' "$running_id") == "$(docker image inspect --format '{{.Id}}' "$image")" ]] || {
    echo 'Running image does not match downloaded digest' >&2; return 1;
  }
}

verify_restored_image() {
  local service=$1 expected_id=$2 running_id
  running_id=$(compose ps --quiet "$service")
  [[ -n $running_id ]] && [[ $(docker inspect --format '{{.Image}}' "$running_id") == "$expected_id" ]] || {
    echo 'Rollback did not restore the previous image ID' >&2; return 1;
  }
}

save_image_reference() {
  python3 - "$env_file" "$1" "$2" <<'PY'
import os, pathlib, re, stat, sys, tempfile
path, key, value = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
assert key in {"BACKEND_IMAGE", "AI_IMAGE"}
lines, result, written = path.read_text().splitlines(keepends=True), [], False
for line in lines:
    if re.match(r"^\s*(?:export\s+)?" + key + r"\s*=", line):
        if not written:
            result.append(f"{key}={value}\n")
            written = True
    else:
        result.append(line)
if not written:
    if result and not result[-1].endswith("\n"):
        result[-1] += "\n"
    result.append(f"{key}={value}\n")
mode = stat.S_IMODE(path.stat().st_mode)
fd, temporary = tempfile.mkstemp(prefix=".env.deploy-", dir=path.parent)
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
