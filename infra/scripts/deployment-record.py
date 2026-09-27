#!/usr/bin/env python3
"""Track actual service deployments; skipped workflow runs never advance the baseline."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ENVIRONMENTS = {"backend": "be-develop", "ai": "ai-develop"}
IMAGE_PREFIX = "ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-"


class GitHubAPI:
    def __init__(self):
        repository = os.environ["GITHUB_REPOSITORY"]
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository):
            raise ValueError("invalid repository")
        self.base = "https://api.github.com/repos/" + repository
        self.token = os.environ["GH_TOKEN"]

    def request(self, method, path, payload=None):
        body = json.dumps(payload).encode() if payload is not None else None
        request = Request(self.base + path, data=body, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            # Do not print tokens, headers or response bodies.
            raise RuntimeError(f"GitHub API {method} {path}: HTTP {error.code}") from None
        except (URLError, TimeoutError) as error:
            raise RuntimeError("GitHub API request failed") from error


def last_success(api, service):
    page = 1
    while True:
        query = urlencode({"environment": ENVIRONMENTS[service], "per_page": 100, "page": page})
        deployments = api.request("GET", "/deployments?" + query)
        if not deployments:
            return None
        for deployment in deployments:
            if deployment["environment"] != ENVIRONMENTS[service] or deployment.get("task") != "deploy":
                continue
            statuses = api.request("GET", f'/deployments/{deployment["id"]}/statuses?per_page=1')
            if statuses and statuses[0]["state"] == "success":
                commit = deployment["sha"]
                validate_commit(commit)
                return commit
        page += 1


def validate_commit(commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("commit must be a full SHA")


def git(*arguments, cwd=None, allowed=(0,)):
    result = subprocess.run(["git", *arguments], cwd=cwd, capture_output=True, check=False)
    if result.returncode not in allowed:
        raise RuntimeError("Git operation failed: " + arguments[0])
    return result


def relevant_path(service, path):
    # Documentation does not change deployed code; Markdown prompts still do.
    if path.startswith("docs/") or Path(path).name in {"README.md", "AGENTS.md"}:
        return False
    if path.startswith("backend/"):
        return service == "backend"
    if path.startswith("AI/"):
        return service == "ai"
    if path == ".github/workflows/backend-ci-cd.yml":
        return service == "backend"
    if path == ".github/workflows/ai-ci-cd.yml":
        return service == "ai"
    if path.startswith("infra/nginx/"):
        return service == "backend"
    if path in {"infra/scripts/deploy-ai.sh", "infra/scripts/tests/test_ai_deploy.py"}:
        return service == "ai"
    if path in {"infra/scripts/deploy-backend.sh", "infra/scripts/tests/test_backend_deploy.py"}:
        return service == "backend"
    return path.startswith(("infra/scripts/", "infra/docker/", "infra/iam/"))


def changed(service, baseline, commit, *, force=False, cwd=None):
    validate_commit(commit)
    git("cat-file", "-e", commit + "^{commit}", cwd=cwd)
    if force:
        return True, "manual execution or force-push"
    if baseline is None:
        return True, "no successful service deployment"
    validate_commit(baseline)
    if git("cat-file", "-e", baseline + "^{commit}", cwd=cwd, allowed=(0, 128)).returncode:
        return True, "baseline commit is missing"
    if git("merge-base", "--is-ancestor", baseline, commit, cwd=cwd, allowed=(0, 1)).returncode:
        return True, "branch history was rewritten"
    paths = git("diff", "--no-renames", "--name-only", "-z", baseline, commit, cwd=cwd).stdout
    selected = any(relevant_path(service, path.decode("utf-8", "surrogateescape"))
                   for path in paths.split(b"\0") if path)
    return selected, "service changes since successful deployment" if selected else "no service changes"


def output(key, value):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write(f"{key}={value}\n")


def start(api, service, commit, image, log_url):
    validate_commit(commit)
    if not re.fullmatch(re.escape(IMAGE_PREFIX + service) + r"@sha256:[0-9a-f]{64}", image):
        raise ValueError("image must be the service GHCR digest")
    deployment = api.request("POST", "/deployments", {
        "ref": commit, "auto_merge": False, "required_contexts": [], "task": "deploy",
        "environment": ENVIRONMENTS[service], "production_environment": False,
        "payload": {"service": service, "image": image, "log_url": log_url},
        "description": f"Deploy {service} {commit}",
    })
    deployment_id = deployment["id"]
    output("deployment_id", deployment_id)
    api.request("POST", f"/deployments/{deployment_id}/statuses", {
        "state": "in_progress", "log_url": log_url, "auto_inactive": False,
    })
    return deployment_id


def final_state(job_status, outcome):
    if job_status == "success":
        if outcome == "deployed":
            return "success"
        if outcome == "superseded":
            return "inactive"
        raise ValueError("successful job must have a verified deployment outcome")
    return "error" if job_status == "cancelled" else "failure"


def finish(api, service, deployment_id, job_status, outcome, log_url):
    deployment = api.request("GET", f"/deployments/{deployment_id}")
    if deployment["environment"] != ENVIRONMENTS[service]:
        raise ValueError("deployment belongs to another service")
    state = final_state(job_status, outcome)
    api.request("POST", f"/deployments/{deployment_id}/statuses", {
        "state": state, "log_url": log_url, "auto_inactive": state == "success",
    })
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["detect", "start", "finish"])
    parser.add_argument("--service", required=True, choices=ENVIRONMENTS)
    parser.add_argument("--commit")
    parser.add_argument("--image")
    parser.add_argument("--deployment-id", type=int)
    parser.add_argument("--job-status", choices=["success", "failure", "cancelled"])
    parser.add_argument("--outcome", default="")
    args = parser.parse_args()
    api = GitHubAPI()
    log_url = f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
    if args.action == "detect":
        baseline = last_success(api, args.service)
        force = os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch"
        with open(os.environ["GITHUB_EVENT_PATH"]) as stream:
            event = json.load(stream)
        selected, reason = changed(args.service, baseline, args.commit, force=force or event.get("forced") is True)
        output("changed", str(selected).lower())
        print(f"{args.service}: {reason}; baseline={baseline}; deploy={selected}")
    elif args.action == "start":
        deployment_id = start(api, args.service, args.commit, args.image, log_url)
        print(f"Deployment {deployment_id} in progress")
    else:
        if args.deployment_id is None or args.deployment_id <= 0:
            raise ValueError("deployment ID is required")
        state = finish(api, args.service, args.deployment_id, args.job_status, args.outcome, log_url)
        print(f"Deployment {args.deployment_id}: {state}")


if __name__ == "__main__":
    main()
