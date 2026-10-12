"""Exercise AI deployments with real Git/HTTP and an isolated fake Docker CLI."""

import argparse
import fcntl
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
WORKFLOW = SCRIPTS.parents[1] / ".github/workflows/ai-ci-cd.yml"
IMAGE = "ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-ai@sha256:" + "b" * 64
spec = importlib.util.spec_from_file_location("sender", SCRIPTS / "send-deploy.py")
sender = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sender)

DOCKER = r'''import json, os, pathlib, sys
path = pathlib.Path(os.environ["FAKE_DOCKER_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
entry = {"args": args, "image": os.environ.get("AI_IMAGE", "")}
if "--project-directory" in args:
    # Record which checkout Compose read, to see what each command was given.
    entry["checkout"] = (pathlib.Path(args[args.index("--project-directory") + 1]).parents[1] / "AI/main.py").read_text().strip()
state["commands"].append(entry)
code = 0
output = ""
if args[0] == "compose":
    args = args[args.index("-f") + 2:]
    if args[0] == "ps":
        output = "ai-id"
    elif args[0] == "pull" and state["mode"] == "pull_failure":
        code = 1
    elif args[0] == "up":
        state["image"] = os.environ["AI_IMAGE"]
        state["phase"] = "new" if "ghcr.io/" in state["image"] else "old"
elif args[0] == "inspect":
    if "Labels" in args[2]:
        output = state["revision"]
    else:
        output = state["image"] if args[2] == "{{.Config.Image}}" else "sha256:" + "a" * 64
path.write_text(json.dumps(state))
if output:
    print(output)
sys.exit(code)
'''


def workflow_path_filters(workflow):
    """Return every `paths:` list of the workflow, in order."""
    filters, current = [], None
    for line in workflow.read_text().splitlines():
        item = re.fullmatch(r"\s+- '([^']+)'", line)
        if current is not None and item:
            current.append(item.group(1))
            continue
        if current is not None:
            filters.append(current)
            current = None
        if line.strip() == "paths:":
            current = []
    return filters


def script_service_paths(script):
    block = re.search(r"^service_paths=\(\n(.*?)^\)", script.read_text(), re.S | re.M).group(1)
    return re.findall(r"'([^']+)'", block)


class DeployScriptTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "repo"
        self.root.mkdir()
        self.git("init", "--initial-branch=develop")
        self.git("config", "user.name", "Deployment test")
        self.git("config", "user.email", "test@example.invalid")
        (self.root / ".gitignore").write_text(".env\n")
        (self.root / "AI").mkdir()
        (self.root / "AI/main.py").write_text("old\n")
        scripts = self.root / "infra/scripts"
        scripts.mkdir(parents=True)
        (scripts / "verify-ai.py").write_text((SCRIPTS / "verify-ai.py").read_text())
        self.git("add", ".")
        self.git("commit", "-m", "old")
        self.old_commit = self.git("rev-parse", "HEAD").strip()
        (self.root / "AI/main.py").write_text("new\n")
        self.git("commit", "-am", "new")
        self.commit = self.git("rev-parse", "HEAD").strip()
        self.remote = self.base / "origin.git"
        self.git("clone", "--bare", str(self.root), str(self.remote))
        self.git("remote", "add", "origin", str(self.remote))
        self.git("reset", "--hard", self.old_commit)
        compose = self.root / "infra/docker"
        compose.mkdir(parents=True)
        self.env_file = compose / ".env"
        self.original_env = "# preserved\nPOSTGRES_PASSWORD=test-secret\n"
        self.env_file.write_text(self.original_env)
        self.env_file.chmod(0o600)
        self.ai_env_file = self.root / "AI/.env"
        self.ai_env_file.write_text("LUNA_API_URL=https://luna.invalid\nLUNA_API_KEY=luna-secret\n")
        self.state_file = self.base / "docker.json"
        self.set_state("success")
        self.bin = self.base / "bin"
        self.bin.mkdir()
        (self.bin / "docker").write_text("#!" + sys.executable + "\n" + DOCKER)
        (self.bin / "flock").write_text("#!/bin/sh\nexit 0\n")
        (self.bin / "runuser").write_text('#!/bin/sh\n[ "$1 $2 $3" = "-u ubuntu --" ] || exit 97\nshift 3\nexec "$@"\n')
        for executable in self.bin.iterdir():
            executable.chmod(0o755)
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, code, body):
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_GET(self):
                state = test.state()
                new = state["phase"] == "new"
                if self.path != "/health":
                    self.send_error(404)
                elif new and state["mode"] == "health_failure":
                    self.reply(503, {"status": "starting"})
                elif new and state["mode"] == "luna_off":
                    # 실제 AI 서버는 Luna 설정이 없으면 /health 를 503 으로 준다
                    self.reply(503, {"status": "unavailable", "luna_configured": False})
                else:
                    self.reply(200, {"status": "ok", "luna_configured": True})

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                if self.path != "/matching":
                    self.send_error(404)
                    return
                self.reply(200, {"status": "auto", "matched_child_id": 1, "llm_called": False})

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.environment = dict(os.environ, DEPLOY_ROOT=str(self.root),
                                FAKE_DOCKER_STATE=str(self.state_file), HEALTHCHECK_TIMEOUT="1",
                                AI_URL="http://127.0.0.1:" + str(self.server.server_port),
                                PATH=str(self.bin) + os.pathsep + os.environ["PATH"])
        self.environment.pop("AI_IMAGE", None)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def git(self, *args, cwd=None):
        result = subprocess.run(["git", *args], cwd=cwd or self.root, capture_output=True, text=True, check=True)
        return result.stdout

    def push_commit(self, relative, text):
        """Add a commit on top of origin/develop, as another merge would."""
        work = self.base / "work"
        if not work.exists():
            subprocess.run(["git", "clone", "--quiet", "--branch", "develop", str(self.remote), str(work)], check=True)
            self.git("config", "user.name", "Deployment test", cwd=work)
            self.git("config", "user.email", "test@example.invalid", cwd=work)
        self.git("pull", "--quiet", "--ff-only", cwd=work)
        target = work / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        self.git("add", relative, cwd=work)
        self.git("commit", "--quiet", "-m", "change " + relative, cwd=work)
        self.git("push", "--quiet", "origin", "develop", cwd=work)
        return self.git("rev-parse", "HEAD", cwd=work).strip()

    def move_server_to(self, commit):
        self.git("fetch", "--quiet", "origin", "develop")
        self.git("merge", "--ff-only", "--quiet", commit)

    def set_state(self, mode, revision=""):
        self.state_file.write_text(json.dumps({"image": "ktc-ai", "phase": "old", "mode": mode,
                                              "revision": revision, "commands": []}))

    def state(self):
        return json.loads(self.state_file.read_text())

    def changed_containers(self):
        return [entry["args"] for entry in self.state()["commands"]
                if "up" in entry["args"] or "pull" in entry["args"]]

    def deploy(self, commit=None, image=IMAGE):
        return subprocess.run(["bash", str(SCRIPTS / "deploy-ai.sh"), commit or self.commit, image],
                              env=self.environment, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=20)

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.state()["image"], IMAGE)
        self.assertEqual(self.env_file.read_text(), self.original_env + "AI_IMAGE=" + IMAGE + "\n")

    def assert_unchanged(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr, result.stdout + result.stderr)
        self.assertEqual(self.state()["image"], "ktc-ai")
        self.assertEqual(self.env_file.read_text(), self.original_env)
        self.assertEqual(self.changed_containers(), [])

    def test_success_persists_digest_and_preserves_secrets_and_permissions(self):
        result = self.deploy()
        self.assert_success(result)
        self.assertEqual(stat.S_IMODE(self.env_file.stat().st_mode), 0o600)
        self.assertNotIn("test-secret", result.stdout + result.stderr)
        self.assertNotIn("luna-secret", result.stdout + result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.commit)
        updates = [args for args in self.changed_containers() if "up" in args]
        self.assertTrue(updates)
        for args in updates:
            self.assertEqual(args[-1], "ai")
            self.assertIn("--no-deps", args)
            self.assertIn("--no-build", args)

    def test_ssm_command_runs_whole_script(self):
        args = argparse.Namespace(service="ai", commit=self.commit, image=IMAGE, instance_id="i-0e30a4108bfd4ea9f",
                                  region="ap-northeast-2", root=str(self.root), origin=None)
        command = sender.build_request(args, (SCRIPTS / "deploy-ai.sh").read_text())["Parameters"]["commands"][0]
        result = subprocess.run(["sh", "-c", command], env=self.environment, input="",
                                capture_output=True, text=True, timeout=20)
        self.assertIn("AI deployment successful", result.stdout, result.stdout + result.stderr)
        self.assert_success(result)

    def test_next_deployment_replaces_existing_image_reference(self):
        self.env_file.write_text(self.original_env + 'AI_IMAGE="ktc-ai:previous"\n')
        self.assert_success(self.deploy())

    def test_pull_failure_does_not_replace_ai(self):
        self.set_state("pull_failure")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["image"], "ktc-ai")
        self.assertEqual(self.env_file.read_text(), self.original_env)
        self.assertFalse(any("up" in args for args in self.changed_containers()))
        self.assert_checkout_restored()

    def assert_checkout_restored(self):
        # 검증하려고 옮긴 checkout을 되돌린다. 실패한 배포의 compose.yaml이 서버에 남지 않게 한다.
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)
        self.assertEqual((self.root / "AI/main.py").read_text(), "old\n")
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_failure_keeps_checkout_moved_by_another_deployment(self):
        # 이 배포가 옮긴 게 아니면 되돌리지 않는다. 되돌리면 BE 배포가 반영한 checkout을 깬다.
        newer = self.push_commit("backend/App.java", "backend change\n")
        self.move_server_to(newer)
        self.set_state("pull_failure")
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), newer)

    def test_health_failure_restores_previous_image(self):
        self.assert_rollback("health_failure")

    def test_unconfigured_luna_after_replacement_restores_previous_image(self):
        self.assert_rollback("luna_off")

    def assert_rollback(self, mode):
        self.set_state(mode)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous AI restored", result.stderr, result.stdout + result.stderr)
        self.assertTrue(self.state()["image"].startswith("ktc-ai:rollback-"))
        self.assertEqual(self.env_file.read_text(), self.original_env + "AI_IMAGE=" + self.state()["image"] + "\n")
        self.assert_checkout_restored()
        # 이전 이미지는 이전 compose.yaml로 다시 띄운다.
        rollback_up = [entry for entry in self.state()["commands"] if "up" in entry["args"]][-1]
        self.assertEqual(rollback_up["checkout"], "old")

    def test_missing_luna_key_stops_before_changes(self):
        self.ai_env_file.write_text("LUNA_API_URL=https://luna.invalid\nLUNA_API_KEY=\n")
        self.assert_unchanged(self.deploy(), "LUNA_API_KEY")
        self.assert_checkout_restored()

    def test_dirty_checkout_is_preserved(self):
        (self.root / "AI/main.py").write_text("local changes\n")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "AI/main.py").read_text(), "local changes\n")
        self.assertEqual(self.state()["commands"], [])

    def test_mutable_image_tag_is_rejected_before_changes(self):
        result = self.deploy(image="ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-ai:latest")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["commands"], [])

    def test_backend_image_is_rejected(self):
        result = self.deploy(image="ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:" + "b" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["commands"], [])

    # ── 역순 배포: 다른 서비스 배포가 서버 checkout을 먼저 옮긴 경우 ──

    def test_checkout_ahead_without_ai_changes_is_kept(self):
        newer = self.push_commit("backend/App.java", "backend change\n")
        self.move_server_to(newer)
        result = self.deploy()
        self.assert_success(result)
        self.assertIn("already contains", result.stdout)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), newer)

    def test_checkout_ahead_with_evals_or_docs_changes_is_kept(self):
        self.push_commit("AI/evals/cases.json", "[]\n")
        newer = self.push_commit("AI/README.md", "docs\n")
        self.move_server_to(newer)
        self.assert_success(self.deploy())

    def test_checkout_ahead_with_newer_ai_changes_is_refused(self):
        newer = self.push_commit("AI/matching/config.py", "TAU = 1\n")
        self.move_server_to(newer)
        self.assert_unchanged(self.deploy(), "newer AI changes")
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), newer)

    def test_checkout_ahead_with_compose_changes_is_refused(self):
        newer = self.push_commit("infra/docker/compose.yaml", "services: {}\n")
        self.move_server_to(newer)
        self.assert_unchanged(self.deploy(), "newer AI changes")

    def test_commit_older_than_running_ai_is_refused(self):
        self.move_server_to(self.commit)
        self.set_state("success", revision=self.commit)
        self.assert_unchanged(self.deploy(commit=self.old_commit), "older than the running AI")

    def test_same_commit_as_running_ai_can_be_redeployed(self):
        self.set_state("success", revision=self.commit)
        self.assert_success(self.deploy())

    def test_diverged_checkout_is_refused(self):
        (self.root / "server-only.txt").write_text("local commit\n")
        self.git("add", "server-only.txt")
        self.git("commit", "-m", "server only")
        self.assert_unchanged(self.deploy(), "diverged")

    def test_commit_outside_develop_is_refused(self):
        self.assert_unchanged(self.deploy(commit="c" * 40), "not on origin/develop")

    # ── 공유 잠금: BE 배포와 같은 파일을 쓴다 ──

    def use_real_flock(self):
        if shutil.which("flock", path=os.environ["PATH"]) is None:
            if os.environ.get("REQUIRE_FLOCK_TESTS") == "true":
                self.fail("flock is required in CI")
            self.skipTest("flock is not installed")
        (self.bin / "flock").unlink()
        lock = open(self.root / ".git/deploy.lock", "w")
        self.addCleanup(lock.close)
        fcntl.flock(lock, fcntl.LOCK_EX)
        return lock

    def test_waits_for_lock_held_by_another_deployment(self):
        lock = self.use_real_flock()
        self.environment["DEPLOY_LOCK_TIMEOUT"] = "20"
        process = subprocess.Popen(["bash", str(SCRIPTS / "deploy-ai.sh"), self.commit, IMAGE],
                                   env=self.environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        time.sleep(1.5)
        self.assertIsNone(process.poll())
        self.assertEqual(self.state()["commands"], [])
        fcntl.flock(lock, fcntl.LOCK_UN)
        stdout, stderr = process.communicate(timeout=20)
        self.assert_success(subprocess.CompletedProcess([], process.returncode, stdout, stderr))

    def test_lock_timeout_fails_without_changes(self):
        self.use_real_flock()
        self.environment["DEPLOY_LOCK_TIMEOUT"] = "1"
        result = self.deploy()
        self.assert_unchanged(result, "Timed out waiting for another deployment")
        self.assertEqual(self.state()["commands"], [])
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)


class ConsistencyTests(unittest.TestCase):
    def test_script_paths_match_workflow_trigger(self):
        filters = workflow_path_filters(WORKFLOW)
        self.assertEqual(len(filters), 2)
        for paths in filters:
            self.assertEqual(paths, script_service_paths(SCRIPTS / "deploy-ai.sh"))


class SenderTests(unittest.TestCase):
    def args(self, image=IMAGE):
        return argparse.Namespace(service="ai", commit="c" * 40, image=image, instance_id="i-0e30a4108bfd4ea9f",
                                  region="ap-northeast-2", root="/home/ubuntu/ktc4-kyungpook-2", origin=None)

    def test_ai_request_runs_ai_script_without_origin(self):
        request = sender.build_request(self.args(), "echo deployment\n")
        command = request["Parameters"]["commands"][0]
        self.assertIn("runuser -u ubuntu", command)
        self.assertNotIn("EXPECTED_PUBLIC_ORIGIN", command)
        self.assertIn('bash -c "$script" deploy-ai.sh ' + "c" * 40 + " " + IMAGE + " </dev/null", command)
        self.assertEqual(request["Comment"], "Deploy ai " + "c" * 40)

    def test_backend_image_is_rejected_for_ai(self):
        with self.assertRaises(ValueError):
            sender.build_request(self.args("ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:" + "b" * 64),
                                 "echo deployment")


if __name__ == "__main__":
    unittest.main()
