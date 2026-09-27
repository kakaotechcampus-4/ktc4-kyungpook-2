"""AI isolation, rollback, locking and checkout protection with real Git and HTTP."""

import shutil
import stat
import subprocess
import time

from test_backend_deploy import DeploymentFixture

IMAGE = "ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-ai@sha256:" + "b" * 64


class AIDeployTests(DeploymentFixture):
    service = "ai"

    def deploy(self, commit=None, image=IMAGE):
        return super().deploy(commit, image)

    def test_success_changes_only_ai_and_preserves_env_files(self):
        ai_env = self.root / "AI/.env"
        ai_env.parent.mkdir()
        ai_env.write_text("LUNA_API_KEY=preserved-test-key\n")
        self.env_file.write_text(self.original_env + "BACKEND_IMAGE=preserved-backend\n")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.state()["image"], IMAGE)
        self.assertEqual(self.env_file.read_text(), self.original_env + "BACKEND_IMAGE=preserved-backend\nAI_IMAGE=" + IMAGE + "\n")
        self.assertEqual(stat.S_IMODE(self.env_file.stat().st_mode), 0o600)
        self.assertEqual(ai_env.read_text(), "LUNA_API_KEY=preserved-test-key\n")
        self.assertNotIn("preserved-test-key", result.stdout + result.stderr)
        self.assert_only_backend_recreated()
        self.assertFalse(any("nginx" in entry["args"] for entry in self.state()["commands"]))

    def test_replacement_failures_restore_previous_ai(self):
        for mode in ("start_failure", "health_failure", "luna_failure", "revision_failure", "digest_failure"):
            with self.subTest(mode=mode):
                self.set_state(mode)
                self.env_file.write_text(self.original_env)
                result = self.deploy()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Previous AI restored", result.stderr, result.stdout + result.stderr)
                self.assertTrue(self.state()["image"].startswith("ktc-ai:rollback-"))
                self.assertEqual(self.env_file.read_text(), self.original_env + "AI_IMAGE=" + self.state()["image"] + "\n")
                self.assert_only_backend_recreated()

    def test_pull_failure_leaves_ai_and_env_untouched(self):
        self.set_state("pull_failure")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["image"], "ktc-ai")
        self.assertEqual(self.env_file.read_text(), self.original_env)
        self.assertFalse(any("up" in entry["args"] for entry in self.state()["commands"]))

    def test_superseded_execution_does_not_touch_container(self):
        result = self.deploy(self.old_commit)
        self.assertEqual(result.returncode, 75)
        self.assertEqual(self.state()["commands"], [])
        self.assertEqual(self.env_file.read_text(), self.original_env)

    def test_dirty_files_are_preserved(self):
        (self.root / "tracked.txt").write_text("server-only change\n")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "tracked.txt").read_text(), "server-only change\n")
        self.assertEqual(self.state()["commands"], [])

    def test_local_commit_is_not_overwritten(self):
        self.git("commit", "--allow-empty", "-m", "server-only commit")
        local = self.git("rev-parse", "HEAD").strip()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("local commits", result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), local)
        self.assertEqual(self.state()["commands"], [])

    def rewrite_remote(self, collision=False):
        writer = self.base / "writer"
        self.git("clone", str(self.base / "origin.git"), str(writer))
        def run(*args):
            return subprocess.run(["git", *args], cwd=writer, capture_output=True, text=True, check=True).stdout.strip()
        run("config", "user.name", "Writer")
        run("config", "user.email", "writer@example.invalid")
        run("checkout", "--orphan", "rewritten")
        run("rm", "-rf", ".")
        (writer / ".gitignore").write_text(".env\n")
        (writer / "tracked.txt").write_text("rewritten\n")
        if collision:
            (writer / ".env").write_text("must not replace server secret\n")
        run("add", "-f", ".")
        run("commit", "-m", "rewrite")
        self.commit = run("rev-parse", "HEAD")
        run("push", "--force", "origin", "HEAD:develop")
        self.set_state("success")

    def test_rewritten_history_saves_backup_and_preserves_ignored_env(self):
        self.rewrite_remote()
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.commit)
        refs = self.git("for-each-ref", "--format=%(objectname)", "refs/deploy-backup/")
        self.assertEqual(refs.strip(), self.old_commit)
        self.assertTrue(self.env_file.read_text().startswith(self.original_env))

    def test_rewritten_history_cannot_overwrite_ignored_file(self):
        secret = self.root / ".env"
        secret.write_text("server secret\n")
        self.rewrite_remote(collision=True)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(secret.read_text(), "server secret\n")
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.old_commit)
        self.assertEqual(self.state()["commands"], [])

    def test_fast_forward_cannot_overwrite_ignored_file(self):
        writer = self.base / "writer"
        self.git("clone", str(self.base / "origin.git"), str(writer))
        subprocess.run(["git", "config", "user.name", "Writer"], cwd=writer, check=True)
        subprocess.run(["git", "config", "user.email", "writer@example.invalid"], cwd=writer, check=True)
        (writer / ".env").write_text("remote replacement\n")
        subprocess.run(["git", "add", "-f", ".env"], cwd=writer, check=True)
        subprocess.run(["git", "commit", "-m", "track ignored file"], cwd=writer, capture_output=True, check=True)
        subprocess.run(["git", "push", "origin", "develop"], cwd=writer, capture_output=True, check=True)
        self.commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=writer, text=True).strip()
        secret = self.root / ".env"
        secret.write_text("server secret\n")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(secret.read_text(), "server secret\n")
        self.assertEqual(self.state()["commands"], [])

    def test_real_lock_wait_and_timeout(self):
        real_flock = shutil.which("flock")
        if not real_flock:
            self.skipTest("native flock is tested on the Linux CI runner")
        (self.executable_dir / "flock").unlink()
        lock = self.root / ".git/backend-deploy.lock"
        ready = self.base / "locked"
        holder = subprocess.Popen([real_flock, str(lock), "sh", "-c", 'touch "$1"; sleep 2', "sh", str(ready)])
        self.addCleanup(lambda: holder.poll() is None and holder.kill())
        deadline = time.monotonic() + 5
        while not ready.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(ready.exists())
        self.environment["DEPLOY_LOCK_TIMEOUT"] = "0"
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("lock timed out", result.stderr)
        self.assertEqual(self.state()["commands"], [])
        self.environment["DEPLOY_LOCK_TIMEOUT"] = "5"
        started = time.monotonic()
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertGreater(time.monotonic() - started, 0.5)
        holder.wait(timeout=5)
