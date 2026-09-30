#!/usr/bin/env python3
"""Send the reviewed deploy script through SSM and wait for its exit status."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time

# 서비스마다 서버에서 실행할 스크립트와 허용하는 이미지가 다르다.
SERVICES = {
    "backend": ("deploy-backend.sh", r"ghcr\.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:[0-9a-f]{64}"),
    "ai": ("deploy-ai.sh", r"ghcr\.io/kakaotechcampus-4/ktc4-kyungpook-2-ai@sha256:[0-9a-f]{64}"),
}


def build_request(args, script):
    script_name, image_pattern = SERVICES[args.service]
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise ValueError("commit must be a full SHA")
    if not re.fullmatch(image_pattern, args.image):
        raise ValueError("image must be the " + args.service + " GHCR digest")
    if not re.fullmatch(r"i-[0-9a-f]{8}(?:[0-9a-f]{9})?", args.instance_id):
        raise ValueError("invalid EC2 instance ID")
    environment = ["DEPLOY_ROOT=" + shlex.quote(args.root)]
    if args.service == "backend":
        if not args.origin or not re.fullmatch(r"https?://[a-zA-Z0-9.-]+(?::[0-9]+)?", args.origin):
            raise ValueError("invalid public origin")
        environment.append("EXPECTED_PUBLIC_ORIGIN=" + shlex.quote(args.origin))
    if not args.root.startswith("/") or "\n" in args.root:
        raise ValueError("root must be an absolute server path")
    # One quoted heredoc, executed as ubuntu, preserves its Docker login and .env ownership.
    # Pass the script as an argument, not on stdin: `docker compose exec` forwards stdin even
    # with -T, so it would swallow the unread rest of the script and bash would exit 0 early.
    delimiter = "DEPLOY_SCRIPT_" + args.commit
    if delimiter in script:
        raise ValueError("heredoc delimiter occurs in deployment script")
    command = (
        "set -eu\n"
        f"script=$(cat <<'{delimiter}'\n{script}\n{delimiter}\n)\n"
        f"runuser -u ubuntu -- env {' '.join(environment)} bash -c \"$script\" {script_name} "
        f"{shlex.quote(args.commit)} {shlex.quote(args.image)} </dev/null\n"
    )
    return {
        "DocumentName": "AWS-RunShellScript",
        "InstanceIds": [args.instance_id],
        "Comment": f"Deploy {args.service} {args.commit}",
        "TimeoutSeconds": 120,
        "Parameters": {"commands": [command], "executionTimeout": ["900"]},
    }


def aws_command(region, *arguments):
    return subprocess.run(
        ["aws", "ssm", *arguments, "--region", region, "--output", "json", "--no-cli-pager",
         "--cli-connect-timeout", "5", "--cli-read-timeout", "30"],
        capture_output=True, text=True, check=False,
    )


def wait_for_command(region, instance_id, command_id, *, timeout=1080):
    deadline = time.monotonic() + timeout
    last_status = None
    while time.monotonic() < deadline:
        result = aws_command(region, "get-command-invocation", "--command-id", command_id,
                             "--instance-id", instance_id)
        if result.returncode:
            # SSM invocation results are eventually consistent after SendCommand.
            if "InvocationDoesNotExist" not in result.stderr:
                raise RuntimeError(result.stderr.strip())
        else:
            invocation = json.loads(result.stdout)
            status = invocation["Status"]
            if status != last_status:
                print("SSM status: " + status, flush=True)
                last_status = status
            if status in {"Success", "Failed", "Cancelled", "TimedOut"}:
                for key in ("StandardOutputContent", "StandardErrorContent"):
                    output = invocation.get(key, "").strip()
                    if output:
                        print(output, flush=True)
                if status != "Success" or invocation.get("ResponseCode") != 0:
                    raise RuntimeError("Remote deployment failed: " + status)
                return
        time.sleep(5)
    raise TimeoutError("SSM result wait timed out; inspect command " + command_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True, choices=sorted(SERVICES))
    parser.add_argument("--commit", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--root", default="/home/ubuntu/ktc4-kyungpook-2")
    parser.add_argument("--origin", help="required for backend")
    args = parser.parse_args()
    if args.service == "backend" and not args.origin:
        parser.error("--origin is required for backend")
    script = Path(__file__).with_name(SERVICES[args.service][0]).read_text()
    request = build_request(args, script)
    fd, filename = tempfile.mkstemp(prefix=args.service + "-ssm-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(request, stream)
        result = aws_command(args.region, "send-command", "--cli-input-json", "file://" + filename)
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        command_id = json.loads(result.stdout)["Command"]["CommandId"]
        print("SSM command: " + command_id, flush=True)
        wait_for_command(args.region, args.instance_id, command_id)
    finally:
        os.unlink(filename)


if __name__ == "__main__":
    main()
