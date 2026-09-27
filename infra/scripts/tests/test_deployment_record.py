"""Successful deployment baselines and cumulative changes, without GitHub writes."""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError

spec = importlib.util.spec_from_file_location("record", Path(__file__).resolve().parents[1] / "deployment-record.py")
record = importlib.util.module_from_spec(spec)
spec.loader.exec_module(record)
SHA = "a" * 40
IMAGE = record.IMAGE_PREFIX + "ai@sha256:" + "b" * 64


class BaselineTests(unittest.TestCase):
    def test_latest_status_and_pagination_ignore_failed_and_superseded(self):
        api = mock.Mock()
        api.request.side_effect = [
            [{"id": 4, "sha": "d" * 40, "environment": "ai-develop", "task": "deploy"},
             {"id": 3, "sha": "c" * 40, "environment": "ai-develop", "task": "deploy"}],
            [{"state": "failure"}], [{"state": "inactive"}],
            [{"id": 2, "sha": SHA, "environment": "ai-develop", "task": "deploy"}],
            [{"state": "success"}],
        ]
        self.assertEqual(record.last_success(api, "ai"), SHA)
        self.assertIn("page=2", api.request.call_args_list[3].args[1])
        self.assertTrue(all("per_page=1" in call.args[1] for call in api.request.call_args_list if "/statuses" in call.args[1]))

    def test_empty_records_and_api_failure_are_distinct(self):
        api = mock.Mock()
        api.request.return_value = []
        self.assertIsNone(record.last_success(api, "backend"))
        api.request.side_effect = RuntimeError("API unavailable")
        with self.assertRaises(RuntimeError):
            record.last_success(api, "backend")

    @mock.patch.dict("os.environ", {"GITHUB_REPOSITORY": "owner/repo", "GH_TOKEN": "test-token"})
    def test_http_auth_network_and_malformed_response_fail_closed(self):
        for error in (HTTPError("url", 403, "denied", {}, None), URLError("offline"), TimeoutError()):
            with self.subTest(error=type(error).__name__), mock.patch.object(record, "urlopen", side_effect=error):
                with self.assertRaises(RuntimeError) as caught:
                    record.GitHubAPI().request("GET", "/deployments")
                self.assertNotIn("test-token", str(caught.exception))
        response = mock.Mock()
        response.read.return_value = b"invalid json"
        response.__enter__ = mock.Mock(return_value=response)
        response.__exit__ = mock.Mock(return_value=False)
        with mock.patch.object(record, "urlopen", return_value=response), self.assertRaises(json.JSONDecodeError):
            record.GitHubAPI().request("GET", "/deployments")

    def test_records_include_exact_revision_digest_and_log(self):
        api = mock.Mock()
        api.request.return_value = {"id": 42}
        self.assertEqual(record.start(api, "ai", SHA, IMAGE, "https://logs.invalid/run"), 42)
        payload = api.request.call_args_list[0].args[2]
        self.assertEqual(payload["ref"], SHA)
        self.assertEqual(payload["environment"], "ai-develop")
        self.assertIs(payload["auto_merge"], False)
        self.assertEqual(payload["required_contexts"], [])
        self.assertEqual(payload["payload"]["image"], IMAGE)
        self.assertEqual(payload["payload"]["log_url"], "https://logs.invalid/run")
        self.assertEqual(api.request.call_args_list[1].args[2]["state"], "in_progress")

    def test_only_verified_completed_deployment_can_advance_baseline(self):
        for job, outcome, state in (("success", "deployed", "success"),
                                    ("success", "superseded", "inactive"),
                                    ("failure", "deployed", "failure"),
                                    ("failure", "", "failure"),
                                    ("cancelled", "", "error")):
            with self.subTest(job=job, outcome=outcome):
                api = mock.Mock()
                api.request.return_value = {"environment": "ai-develop"}
                self.assertEqual(record.finish(api, "ai", 42, job, outcome, "log"), state)
                self.assertEqual(api.request.call_args.args[2]["auto_inactive"], state == "success")
        with self.assertRaises(ValueError):
            record.final_state("success", "")

    def test_status_write_failure_is_not_ignored(self):
        api = mock.Mock()
        api.request.side_effect = [{"environment": "ai-develop"}, RuntimeError("write failed")]
        with self.assertRaises(RuntimeError):
            record.finish(api, "ai", 42, "success", "deployed", "log")

    def test_cross_service_record_cannot_be_updated(self):
        api = mock.Mock()
        api.request.return_value = {"environment": "be-develop"}
        with self.assertRaises(ValueError):
            record.finish(api, "ai", 42, "success", "deployed", "log")
        self.assertEqual(api.request.call_count, 1)


class ChangesTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "--initial-branch=develop")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("commit", "--allow-empty", "-m", "baseline")
        self.baseline = self.head()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.DEVNULL, text=True).strip()

    def head(self):
        return self.git("rev-parse", "HEAD")

    def commit_file(self, name):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("changed\n")
        self.git("add", ".")
        self.git("commit", "-m", name)
        return self.head()

    def selected(self, service, baseline=None, force=False):
        return record.changed(service, baseline or self.baseline, self.head(), force=force, cwd=self.root)[0]

    def test_service_source_and_workflow_changes_are_independent(self):
        for path, service in (("AI/main.py", "ai"), ("backend/src/Main.java", "backend"),
                              (".github/workflows/ai-ci-cd.yml", "ai"),
                              (".github/workflows/backend-ci-cd.yml", "backend")):
            with self.subTest(path=path):
                self.git("reset", "--hard", self.baseline)
                self.commit_file(path)
                self.assertTrue(self.selected(service))
                self.assertFalse(self.selected("backend" if service == "ai" else "ai"))

    def test_shared_compose_and_helper_change_both_services(self):
        for path in ("infra/docker/compose.yaml", "infra/scripts/deploy-common.sh", "infra/scripts/deployment-record.py"):
            self.git("reset", "--hard", self.baseline)
            self.commit_file(path)
            self.assertTrue(self.selected("ai"))
            self.assertTrue(self.selected("backend"))

    def test_unrelated_documentation_and_frontend_skip_both(self):
        for path in ("docs/api/api-spec.md", "README.md", "AI/README.md", "frontend/src/App.tsx"):
            self.commit_file(path)
        self.assertFalse(self.selected("ai"))
        self.assertFalse(self.selected("backend"))

    def test_failed_or_cancelled_ai_is_rechecked_after_backend_and_document_push(self):
        pending_ai = self.commit_file("AI/main.py")
        be_deployed = self.commit_file("backend/src/Main.java")
        self.commit_file("docs/guide.md")
        self.assertTrue(self.selected("ai"))
        self.assertFalse(self.selected("backend", baseline=be_deployed))
        self.assertFalse(self.selected("ai", baseline=pending_ai))

    def test_replaced_waiting_same_service_runs_keep_all_pending_changes(self):
        self.commit_file("AI/main.py")
        self.commit_file("AI/matching/nodes.py")
        self.commit_file("backend/src/Main.java")
        self.assertTrue(self.selected("ai"))

    def test_first_deployment_missing_commit_and_force_require_full_deploy(self):
        for service in ("ai", "backend"):
            self.assertTrue(record.changed(service, None, self.head(), cwd=self.root)[0])
            self.assertTrue(self.selected(service, baseline="e" * 40))
            self.assertTrue(self.selected(service, force=True))

    def test_nonancestor_commit_requires_full_deploy(self):
        old = self.commit_file("AI/main.py")
        self.git("reset", "--hard", self.baseline)
        self.commit_file("docs/new.md")
        self.assertTrue(self.selected("ai", baseline=old))
        self.assertTrue(self.selected("backend", baseline=old))

    def test_rename_from_ai_to_unrelated_directory_is_still_ai_change(self):
        baseline = self.commit_file("AI/main.py")
        self.git("mv", "AI/main.py", "moved.py")
        self.git("commit", "-m", "move source")
        self.assertTrue(self.selected("ai", baseline=baseline))
