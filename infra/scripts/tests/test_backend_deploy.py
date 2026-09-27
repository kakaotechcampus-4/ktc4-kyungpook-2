"""Exercise deployments with real Git/HTTP and an isolated fake Docker CLI."""

import argparse
import http.server
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock
from urllib.parse import urlencode

SCRIPTS = Path(__file__).resolve().parents[1]
IMAGE = "ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:" + "b" * 64
ORIGIN = "http://54.116.206.217"
spec = importlib.util.spec_from_file_location("sender", SCRIPTS / "send-backend-deploy.py")
sender = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sender)

DOCKER = r'''import json, os, pathlib, sys
path = pathlib.Path(os.environ["FAKE_DOCKER_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
state["commands"].append({"args": args, "image": os.environ.get(state["service"].upper() + "_IMAGE", "")})
code = 0
output = ""
if args[0] == "compose":
    args = args[args.index("-f") + 2:]
    if args[0] == "ps":
        output = state["service"] + "-id"
    elif args[0] == "pull" and state["mode"] == "pull_failure":
        code = 1
    elif args[0] == "pull" and state["mode"] == "unexpected_exit_75":
        code = 75
    elif args[0] == "up":
        state["image"] = os.environ[state["service"].upper() + "_IMAGE"]
        state["phase"] = "new" if "ghcr.io/" in state["image"] else "old"
        if state["phase"] == "new" and state["mode"] == "start_failure":
            code = 1
    elif args[0] == "exec" and args[-1] == "reload" and state["phase"] == "new" and state["mode"] == "reload_failure":
        code = 1
elif args[0] == "inspect":
    if args[2] == "{{.Config.Image}}":
        output = state["image"]
    elif "revision" in args[2]:
        output = "d" * 40 if state["mode"] == "revision_failure" else state["revision"]
    else:
        output = "sha256:" + "a" * 64
elif args[:2] == ["image", "inspect"]:
    output = "sha256:" + ("b" if state["mode"] == "digest_failure" else "a") * 64
path.write_text(json.dumps(state))
if output:
    print(output)
sys.exit(code)
'''


class DeploymentFixture(unittest.TestCase):
    service = "backend"

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
        (self.root / "tracked.txt").write_text("old\n")
        self.git("add", ".")
        self.git("commit", "-m", "old")
        self.old_commit = self.git("rev-parse", "HEAD").strip()
        (self.root / "tracked.txt").write_text("new\n")
        self.git("commit", "-am", "new")
        self.commit = self.git("rev-parse", "HEAD").strip()
        remote = self.base / "origin.git"
        self.git("clone", "--bare", str(self.root), str(remote))
        self.git("remote", "add", "origin", str(remote))
        self.git("fetch", "origin", "develop")
        self.git("reset", "--hard", self.old_commit)
        compose = self.root / "infra/docker"
        compose.mkdir(parents=True)
        self.env_file = compose / ".env"
        self.original_env = "# preserved\nPOSTGRES_PASSWORD=test-secret\nJWT_SECRET=test-jwt\n"
        self.env_file.write_text(self.original_env)
        self.env_file.chmod(0o600)
        self.state_file = self.base / "docker.json"
        self.set_state("success")
        executable_dir = self.base / "bin"
        executable_dir.mkdir()
        (executable_dir / "docker").write_text("#!" + sys.executable + "\n" + DOCKER)
        (executable_dir / "flock").write_text("#!/bin/sh\nexit 0\n")
        for executable in executable_dir.iterdir():
            executable.chmod(0o755)
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                state = test.state()
                if self.path.endswith("/health"):
                    failed = state["phase"] == "new" and state["mode"] == "health_failure"
                    self.send_response(503 if failed else 200)
                    self.end_headers()
                    body = {"status": "ok"}
                    if test.service == "ai":
                        body["luna_configured"] = not (state["phase"] == "new" and state["mode"] == "luna_failure")
                    self.wfile.write(json.dumps(body).encode())
                elif self.path.endswith("/oauth2/authorization/kakao"):
                    bad = state["phase"] == "new" and state["mode"] == "oauth_failure"
                    callback = ORIGIN + ("/wrong-callback" if bad else "/login/oauth2/code/kakao")
                    self.send_response(302)
                    self.send_header("Location", "https://kauth.kakao.com/oauth/authorize?" + urlencode({"redirect_uri": callback}))
                    self.end_headers()
                else:
                    self.send_error(404)

            def do_OPTIONS(self):
                state = test.state()
                self.send_response(200)
                if not (state["phase"] == "new" and state["mode"] == "cors_failure"):
                    self.send_header("Access-Control-Allow-Origin", self.headers["Origin"])
                self.end_headers()

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        url = "http://127.0.0.1:" + str(self.server.server_port)
        self.environment = dict(os.environ, DEPLOY_ROOT=str(self.root),
                                FAKE_DOCKER_STATE=str(self.state_file), HEALTHCHECK_TIMEOUT="1",
                                AI_HEALTH_URL=url + "/health", BACKEND_HEALTH_URL=url + "/direct/api/health", PROXY_BASE_URL=url + "/proxy",
                                PATH=str(executable_dir) + os.pathsep + os.environ["PATH"])
        self.environment.pop("BACKEND_IMAGE", None)
        self.environment.pop("AI_IMAGE", None)
        self.executable_dir = executable_dir

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True, check=True)
        return result.stdout

    def set_state(self, mode):
        self.state_file.write_text(json.dumps({"service": self.service, "image": "ktc-" + self.service,
                                              "revision": self.commit, "phase": "old", "mode": mode, "commands": []}))

    def state(self):
        return json.loads(self.state_file.read_text())

    def deploy(self, commit=None, image=IMAGE):
        return subprocess.run(["bash", str(SCRIPTS / ("deploy-" + self.service + ".sh")), commit or self.commit, image],
                              env=self.environment, capture_output=True, text=True, timeout=20)

    def assert_only_backend_recreated(self):
        commands = [entry["args"] for entry in self.state()["commands"]]
        updates = [args for args in commands if "up" in args]
        self.assertTrue(updates)
        for args in updates:
            self.assertEqual(args[-1], self.service)
            self.assertIn("--no-deps", args)
            self.assertIn("--no-build", args)


class DeployScriptTests(DeploymentFixture):
    def test_success_persists_digest_and_preserves_secrets_and_permissions(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.state()["image"], IMAGE)
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=" + IMAGE + "\n")
        self.assertEqual(stat.S_IMODE(self.env_file.stat().st_mode), 0o600)
        self.assertNotIn("test-secret", result.stdout + result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.commit)
        self.assert_only_backend_recreated()

    def test_next_deployment_replaces_existing_image_reference(self):
        self.env_file.write_text(self.original_env + 'BACKEND_IMAGE="ktc-backend:previous"\n')
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=" + IMAGE + "\n")

    def test_health_failure_restores_previous_image(self):
        self.assert_rollback("health_failure")

    def test_cors_failure_restores_previous_image(self):
        self.assert_rollback("cors_failure")

    def test_oauth_failure_restores_previous_image(self):
        self.assert_rollback("oauth_failure")

    def test_nginx_reload_failure_restores_previous_image(self):
        self.assert_rollback("reload_failure")

    def assert_rollback(self, mode):
        self.set_state(mode)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous backend restored", result.stderr, result.stdout + result.stderr)
        self.assertTrue(self.state()["image"].startswith("ktc-backend:rollback-"))
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=" + self.state()["image"] + "\n")
        self.assert_only_backend_recreated()

    def test_pull_failure_does_not_replace_backend(self):
        self.set_state("pull_failure")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["image"], "ktc-backend")
        self.assertEqual(self.env_file.read_text(), self.original_env)
        self.assertFalse(any("up" in entry["args"] for entry in self.state()["commands"]))

    def test_tool_exit_75_is_failure_not_superseded(self):
        self.set_state("unexpected_exit_75")
        self.assertEqual(self.deploy().returncode, 1)
        self.assertEqual(self.state()["image"], "ktc-backend")

    def test_dirty_checkout_is_preserved(self):
        (self.root / "tracked.txt").write_text("local changes\n")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "tracked.txt").read_text(), "local changes\n")
        self.assertEqual(self.state()["commands"], [])

    def test_older_commit_is_not_deployed(self):
        self.git("reset", "--hard", self.commit)
        result = self.deploy(commit=self.old_commit)
        self.assertEqual(result.returncode, 75)
        self.assertIn("older than server HEAD", result.stderr)
        self.assertEqual(self.state()["commands"], [])

    def test_mutable_image_tag_is_rejected_before_changes(self):
        result = self.deploy(image="ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend:latest")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)
        self.assertEqual(self.state()["commands"], [])


class SenderTests(unittest.TestCase):
    def args(self):
        return argparse.Namespace(commit="c" * 40, image=IMAGE, instance_id="i-0e30a4108bfd4ea9f",
                                  region="ap-northeast-2", root="/home/ubuntu/ktc4-kyungpook-2", origin=ORIGIN)

    def test_script_runs_as_ubuntu_with_exact_commit_and_digest(self):
        request = sender.build_request(self.args(), "echo deployment\n")
        command = request["Parameters"]["commands"][0]
        self.assertIn("runuser -u ubuntu", command)
        self.assertIn("bash -s -- " + "c" * 40 + " " + IMAGE, command)
        self.assertEqual(request["InstanceIds"], ["i-0e30a4108bfd4ea9f"])
        self.assertEqual(request["Parameters"]["executionTimeout"], ["1800"])

    def test_injected_image_is_rejected(self):
        args = self.args()
        args.image = IMAGE + "; echo injected"
        with self.assertRaises(ValueError):
            sender.build_request(args, "echo deployment")

    @mock.patch.object(sender.time, "sleep")
    @mock.patch.object(sender, "aws_command")
    def test_wait_retries_eventual_consistency_and_pending(self, command, _sleep):
        command.side_effect = [
            subprocess.CompletedProcess([], 255, "", "InvocationDoesNotExist"),
            subprocess.CompletedProcess([], 0, json.dumps({"Status": "InProgress"}), ""),
            subprocess.CompletedProcess([], 0, json.dumps({"Status": "Success", "ResponseCode": 0}), ""),
        ]
        sender.wait_for_command("ap-northeast-2", "i-0e30a4108bfd4ea9f", "command")
        self.assertEqual(command.call_count, 3)

    @mock.patch.object(sender, "aws_command")
    def test_remote_failure_is_not_reported_as_success(self, command):
        command.return_value = subprocess.CompletedProcess([], 0, json.dumps({"Status": "Failed", "ResponseCode": 1}), "")
        with self.assertRaises(RuntimeError):
            sender.wait_for_command("ap-northeast-2", "i-0e30a4108bfd4ea9f", "command")

    @mock.patch.object(sender, "aws_command")
    def test_success_status_with_nonzero_exit_code_is_rejected(self, command):
        command.return_value = subprocess.CompletedProcess([], 0, json.dumps({"Status": "Success", "ResponseCode": 1}), "")
        with self.assertRaises(RuntimeError):
            sender.wait_for_command("ap-northeast-2", "i-0e30a4108bfd4ea9f", "command")

    @mock.patch.object(sender, "aws_command")
    def test_only_remote_exit_75_is_superseded(self, command):
        command.return_value = subprocess.CompletedProcess([], 0, json.dumps({"Status": "Failed", "ResponseCode": 75}), "")
        self.assertEqual(sender.wait_for_command("region", "instance", "command"), "superseded")
        command.return_value = subprocess.CompletedProcess([], 0, json.dumps({"Status": "TimedOut", "ResponseCode": 75}), "")
        with self.assertRaises(RuntimeError):
            sender.wait_for_command("region", "instance", "command")

    def test_ai_request_and_reviewed_helper_are_bundled(self):
        args = self.args()
        args.service = "ai"
        args.image = IMAGE.replace("-backend@", "-ai@")
        script = sender.render_script("ai")
        self.assertIn("flock -w", script)
        self.assertNotIn('source "$(dirname', script)
        subprocess.run(["bash", "-n"], input=script, text=True, check=True)
        request = sender.build_request(args, script)
        self.assertIn(args.image, request["Parameters"]["commands"][0])
        self.assertEqual(request["Parameters"]["executionTimeout"], ["1800"])
        args.image = IMAGE
        with self.assertRaises(ValueError):
            sender.build_request(args, script)

    @mock.patch.object(sender.time, "monotonic", side_effect=[0, 1981])
    def test_result_wait_has_a_bounded_timeout(self, _clock):
        with self.assertRaises(TimeoutError):
            sender.wait_for_command("region", "instance", "command")


if __name__ == "__main__":
    unittest.main()
