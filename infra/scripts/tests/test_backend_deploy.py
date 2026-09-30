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
ORIGIN = "http://deployment.test"
spec = importlib.util.spec_from_file_location("sender", SCRIPTS / "send-backend-deploy.py")
sender = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sender)

DOCKER = r'''import json, os, pathlib, sys
path = pathlib.Path(os.environ["FAKE_DOCKER_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
state["commands"].append({"args": args, "image": os.environ.get("BACKEND_IMAGE", "")})
code = 0
output = ""
if args[0] == "compose":
    env_file = pathlib.Path(args[args.index("--env-file") + 1])
    settings = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if "=" in line and not line.startswith("#"))
    origin = settings.get("PUBLIC_ORIGIN", "http://localhost")
    args = args[args.index("-f") + 2:]
    if args[0] == "config" and "json" in args:
        output = json.dumps({"services": {
            "caddy": {"environment": {"PUBLIC_ORIGIN": origin}},
            "backend": {"environment": {
                "CORS_ALLOWED_ORIGINS": settings.get("CORS_ALLOWED_ORIGINS", origin),
                "AUTH_COOKIE_SECURE": settings.get("AUTH_COOKIE_SECURE", "false"),
                "AUTH_SUCCESS_REDIRECT": origin + "/oauth/success",
                "AUTH_FAILURE_REDIRECT": origin + "/login",
            }},
        }})
    elif args[0] == "ps":
        output = "caddy-id" if args[-1] == "caddy" else "backend-id"
    elif args[0] == "pull" and state["mode"] == "pull_failure":
        code = 1
    elif args[0] == "up":
        state["image"] = os.environ["BACKEND_IMAGE"]
        state["phase"] = "new" if "ghcr.io/" in state["image"] else "old"
    elif args[0] == "exec":
        # Like the real CLI, exec forwards stdin even with -T.
        sys.stdin.read()
        if "adapt" in args:
            if state["mode"] == "validate_failure":
                code = 1
            else:
                version = "new" if state["config_changed"] else "old"
                # Different JSON formatting/key order must not cause a reload.
                output = json.dumps({"apps": {"http": {"servers": {version: {}}}}, "admin": {}}, indent=2)
        elif "wget" in args:
            output = '{"admin":{},"apps":{"http":{"servers":{"old":{}}}}}'
        elif "reload" in args:
            if "/tmp/backend-deploy-rollback.json" in args:
                state["active_config"] = "old"
            elif state["mode"] == "reload_failure":
                state["active_config"] = "new"
                code = 1
            else:
                state["active_config"] = "new"
elif args[0] == "inspect":
    if args[-1] == "caddy-id":
        output = "PUBLIC_ORIGIN=" + state["origin"]
    else:
        output = state["image"] if args[2] == "{{.Config.Image}}" else "sha256:" + "a" * 64
path.write_text(json.dumps(state))
if output:
    print(output)
sys.exit(code)
'''


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
        (self.root / "tracked.txt").write_text("old\n")
        scripts = self.root / "infra/scripts"
        scripts.mkdir(parents=True)
        (scripts / "verify-proxy.py").write_text((SCRIPTS / "verify-proxy.py").read_text())
        self.git("add", ".")
        self.git("commit", "-m", "old")
        self.old_commit = self.git("rev-parse", "HEAD").strip()
        (self.root / "tracked.txt").write_text("new\n")
        self.git("commit", "-am", "new")
        self.commit = self.git("rev-parse", "HEAD").strip()
        remote = self.base / "origin.git"
        self.git("clone", "--bare", str(self.root), str(remote))
        self.git("remote", "add", "origin", str(remote))
        self.git("reset", "--hard", self.old_commit)
        compose = self.root / "infra/docker"
        compose.mkdir(parents=True)
        self.env_file = compose / ".env"
        self.original_env = "# preserved\nPOSTGRES_PASSWORD=test-secret\nJWT_SECRET=test-jwt\nPUBLIC_ORIGIN=" + ORIGIN + "\n"
        self.env_file.write_text(self.original_env)
        self.env_file.chmod(0o600)
        self.state_file = self.base / "docker.json"
        self.set_state("success")
        executable_dir = self.base / "bin"
        executable_dir.mkdir()
        (executable_dir / "docker").write_text("#!" + sys.executable + "\n" + DOCKER)
        (executable_dir / "flock").write_text("#!/bin/sh\nexit 0\n")
        (executable_dir / "runuser").write_text('#!/bin/sh\n[ "$1 $2 $3" = "-u ubuntu --" ] || exit 97\nshift 3\nexec "$@"\n')
        for executable in executable_dir.iterdir():
            executable.chmod(0o755)
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                state = test.state()
                if self.path.endswith("/api/health"):
                    failed = state["phase"] == "new" and state["mode"] == "health_failure"
                    redirect = state["phase"] == "new" and state["mode"] == "redirect_failure" and not self.path.startswith("/direct/")
                    self.send_response(308 if redirect else (503 if failed else 200))
                    self.end_headers()
                    self.wfile.write(b'{"status":"ok"}')
                elif self.path.endswith("/oauth2/authorization/kakao"):
                    bad = state["phase"] == "new" and state["mode"] == "oauth_failure"
                    callback = test.environment["EXPECTED_PUBLIC_ORIGIN"] + ("/wrong-callback" if bad else "/login/oauth2/code/kakao")
                    self.send_response(302)
                    self.send_header("Location", "https://kauth.kakao.com/oauth/authorize?" + urlencode({"redirect_uri": callback}))
                    self.end_headers()
                else:
                    self.send_error(404)

            def do_OPTIONS(self):
                # Like Spring: same-origin requests get no CORS headers, other origins are rejected.
                # cors_failure models a backend that allows every origin.
                state = test.state()
                origin = self.headers["Origin"]
                permissive = state["phase"] == "new" and state["mode"] == "cors_failure"
                if permissive:
                    self.send_response(200)
                    self.send_header("Access-Control-Allow-Origin", origin)
                else:
                    self.send_response(200 if origin == "http://" + self.headers["Host"] else 403)
                self.end_headers()

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        url = "http://127.0.0.1:" + str(self.server.server_port)
        self.environment = dict(os.environ, DEPLOY_ROOT=str(self.root),
                                FAKE_DOCKER_STATE=str(self.state_file), HEALTHCHECK_TIMEOUT="1",
                                BACKEND_HEALTH_URL=url + "/direct/api/health", PROXY_PORT=str(self.server.server_port),
                                EXPECTED_PUBLIC_ORIGIN=ORIGIN,
                                PATH=str(executable_dir) + os.pathsep + os.environ["PATH"])
        self.environment.pop("BACKEND_IMAGE", None)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True, check=True)
        return result.stdout

    def set_state(self, mode):
        self.state_file.write_text(json.dumps({"image": "ktc-backend", "phase": "old", "mode": mode,
                                              "origin": ORIGIN, "active_config": "old", "commands": [],
                                              "config_changed": mode == "reload_failure"}))

    def set_config_changed(self):
        state = self.state()
        state["config_changed"] = True
        self.state_file.write_text(json.dumps(state))

    def reload_commands(self):
        return [entry["args"] for entry in self.state()["commands"] if "reload" in entry["args"]]

    def state(self):
        return json.loads(self.state_file.read_text())

    def deploy(self, commit=None, image=IMAGE):
        return subprocess.run(["bash", str(SCRIPTS / "deploy-backend.sh"), commit or self.commit, image],
                              env=self.environment, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=20)

    def deploy_through_ssm_command(self):
        args = argparse.Namespace(commit=self.commit, image=IMAGE, instance_id="i-0e30a4108bfd4ea9f",
                                  region="ap-northeast-2", root=str(self.root), origin=ORIGIN)
        script = (SCRIPTS / "deploy-backend.sh").read_text()
        command = sender.build_request(args, script)["Parameters"]["commands"][0]
        # SSM runs the command with sh, so run exactly what the sender would send.
        return subprocess.run(["sh", "-c", command], env=self.environment, input="",
                              capture_output=True, text=True, timeout=20)

    def assert_only_backend_recreated(self):
        commands = [entry["args"] for entry in self.state()["commands"]]
        updates = [args for args in commands if "up" in args]
        self.assertTrue(updates)
        for args in updates:
            self.assertEqual(args[-1], "backend")
            self.assertIn("--no-deps", args)
            self.assertIn("--no-build", args)

    def test_success_persists_digest_and_preserves_secrets_and_permissions(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.state()["image"], IMAGE)
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=" + IMAGE + "\n")
        self.assertEqual(stat.S_IMODE(self.env_file.stat().st_mode), 0o600)
        self.assertNotIn("test-secret", result.stdout + result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.commit)
        self.assert_only_backend_recreated()
        self.assertEqual(self.reload_commands(), [])
        self.assertIn("skipping reload", result.stdout)

    def test_ssm_command_runs_whole_script_although_exec_reads_stdin(self):
        result = self.deploy_through_ssm_command()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Backend deployment successful", result.stdout)
        self.assertEqual(self.state()["image"], IMAGE)
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=" + IMAGE + "\n")

    def test_changed_caddy_config_is_reloaded_without_force(self):
        self.set_config_changed()
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.state()["active_config"], "new")
        self.assertEqual(len(self.reload_commands()), 1)
        self.assertNotIn("--force", self.reload_commands()[0])

    def test_changed_caddy_config_is_restored_after_proxy_failure(self):
        self.assert_rollback("cors_failure", config_changed=True)
        self.assertEqual(len(self.reload_commands()), 2)

    def test_changed_config_is_not_reloaded_if_backend_health_fails(self):
        self.assert_rollback("health_failure", config_changed=True)
        self.assertEqual(self.reload_commands(), [])

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

    def test_caddy_reload_failure_restores_previous_image(self):
        self.assert_rollback("reload_failure")

    def test_redirect_with_success_json_is_not_healthy(self):
        self.assert_rollback("redirect_failure")

    def test_caddy_validate_failure_does_not_replace_backend(self):
        self.set_state("validate_failure")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["image"], "ktc-backend")
        self.assertFalse(any("up" in entry["args"] for entry in self.state()["commands"]))

    def test_origin_mismatch_cannot_be_hidden_by_shell_override(self):
        self.env_file.write_text(self.original_env.replace(ORIGIN, "http://wrong.test"))
        self.environment["PUBLIC_ORIGIN"] = ORIGIN
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PUBLIC_ORIGIN mismatch", result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)
        self.assertFalse(any("up" in entry["args"] for entry in self.state()["commands"]))

    def test_running_caddy_origin_must_match(self):
        state = self.state()
        state["origin"] = "http://old.test"
        self.state_file.write_text(json.dumps(state))
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)

    def test_https_requires_secure_cookie_before_changes(self):
        origin = ORIGIN.replace("http:", "https:")
        self.env_file.write_text(self.original_env.replace(ORIGIN, origin))
        self.environment["EXPECTED_PUBLIC_ORIGIN"] = origin
        state = self.state()
        state["origin"] = origin
        self.state_file.write_text(json.dumps(state))
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AUTH_COOKIE_SECURE", result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)

    def assert_rollback(self, mode, config_changed=False):
        self.set_state(mode)
        if config_changed:
            self.set_config_changed()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous backend restored", result.stderr, result.stdout + result.stderr)
        self.assertTrue(self.state()["image"].startswith("ktc-backend:rollback-"))
        self.assertEqual(self.state()["active_config"], "old")
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=" + self.state()["image"] + "\n")
        self.assert_only_backend_recreated()
        if not config_changed and mode != "reload_failure":
            self.assertEqual(self.reload_commands(), [])
        self.assertEqual(list((self.root / ".git").glob("caddy-*.*")), [])

    def test_pull_failure_does_not_replace_backend(self):
        self.set_state("pull_failure")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["image"], "ktc-backend")
        self.assertEqual(self.env_file.read_text(), self.original_env)
        self.assertFalse(any("up" in entry["args"] for entry in self.state()["commands"]))

    def test_dirty_checkout_is_preserved(self):
        (self.root / "tracked.txt").write_text("local changes\n")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "tracked.txt").read_text(), "local changes\n")
        self.assertEqual(self.state()["commands"], [])

    def test_older_commit_is_not_deployed(self):
        self.git("reset", "--hard", self.commit)
        result = self.deploy(commit=self.old_commit)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("older than server HEAD", result.stderr)
        self.assertFalse(any("up" in entry["args"] or "pull" in entry["args"]
                             for entry in self.state()["commands"]))

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
        self.assertIn("EXPECTED_PUBLIC_ORIGIN=" + ORIGIN, command)
        self.assertNotIn("env PUBLIC_ORIGIN=", command)
        self.assertIn('bash -c "$script" deploy-backend.sh ' + "c" * 40 + " " + IMAGE + " </dev/null", command)
        self.assertEqual(request["InstanceIds"], ["i-0e30a4108bfd4ea9f"])
        self.assertEqual(request["Parameters"]["executionTimeout"], ["900"])

    def test_injected_image_is_rejected(self):
        args = self.args()
        args.image = IMAGE + "; echo injected"
        with self.assertRaises(ValueError):
            sender.build_request(args, "echo deployment")

    def test_origin_is_required(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "send-backend-deploy.py"),
                                 "--commit", "c" * 40, "--image", IMAGE,
                                 "--instance-id", "i-0e30a4108bfd4ea9f", "--region", "ap-northeast-2"],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("--origin", result.stderr)

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


if __name__ == "__main__":
    unittest.main()
