"""Exercise the real Caddyfile with a local CA and isolated Docker upstreams."""

import importlib.util
import http.server
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("proxy", ROOT / "infra/scripts/verify-proxy.py")
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)

ECHO = '''import http.server, json, os
from urllib.parse import urlencode
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_OPTIONS(self):
        # Like Spring behind Caddy: same-origin requests get no CORS headers, other origins are rejected.
        own = self.headers.get("X-Forwarded-Proto", "http") + "://" + self.headers.get("Host", "")
        self.send_response(200 if self.headers.get("Origin") == own else 403)
        self.end_headers()
    def do_GET(self):
        if self.path == "/oauth2/authorization/kakao":
            self.send_response(302)
            self.send_header("Location", "https://kauth.kakao.com/oauth/authorize?" + urlencode({"redirect_uri": "https://localhost/login/oauth2/code/kakao"}))
            self.end_headers()
            return
        self.respond()
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.respond()
    def respond(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "public, max-age=60")
        self.end_headers()
        result = {"status": "ok"} if self.path == "/api/health" else {"service": os.environ["ROLE"], "instance": os.uname().nodename, "path": self.path, "headers": dict(self.headers)}
        self.wfile.write(json.dumps(result).encode())
http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ["PORT"])), Handler).serve_forever()
'''


class CaddyProxyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        available = shutil.which("docker") and subprocess.run(
            ["docker", "info"], capture_output=True, timeout=15).returncode == 0
        if not available:
            if os.environ.get("REQUIRE_DOCKER_TESTS") == "true":
                raise RuntimeError("Docker proxy tests are required in CI")
            raise unittest.SkipTest("Docker is unavailable")
        cls.temporary = tempfile.TemporaryDirectory(prefix="caddy-proxy-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        cls.project = "caddy-test-" + uuid.uuid4().hex[:12]
        # Validate the actual application Compose with dummy settings only.
        fixture = cls.directory / "application"
        (fixture / "infra/docker").mkdir(parents=True)
        (fixture / "infra/caddy").mkdir()
        (fixture / "AI").mkdir()
        (fixture / "AI/.env").touch()
        shutil.copy(ROOT / "infra/docker/compose.yaml", fixture / "infra/docker/compose.yaml")
        shutil.copy(ROOT / "infra/docker/.env.example", fixture / "infra/docker/.env")
        shutil.copy(ROOT / "infra/caddy/Caddyfile", fixture / "infra/caddy/Caddyfile")
        app_compose = ["docker", "compose", "--project-name", cls.project + "-config",
                       "--env-file", str(fixture / "infra/docker/.env"),
                       "-f", str(fixture / "infra/docker/compose.yaml")]
        for origin, secure in [("http://localhost", "false"), ("https://iitda.duckdns.org", "true")]:
            environment = dict(os.environ, PUBLIC_ORIGIN=origin, AUTH_COOKIE_SECURE=secure)
            result = subprocess.run(app_compose + ["config", "--format", "json"],
                                    capture_output=True, text=True, env=environment, check=True)
            config = json.loads(result.stdout)
            proxy.check_settings(config, origin, origin)
            assert config["services"]["backend"]["ports"][0]["host_ip"] == "127.0.0.1"
            cls.image = config["services"]["caddy"]["image"]
            subprocess.run(["docker", "run", "--rm", "-e", "PUBLIC_ORIGIN=" + origin,
                            "-v", str(ROOT / "infra/caddy") + ":/etc/caddy:ro", cls.image,
                            "caddy", "validate", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"],
                           capture_output=True, text=True, check=True, timeout=180)
        (cls.directory / "echo.py").write_text(ECHO)
        services = {}
        for role, port in [("backend", 8080), ("frontend", 3000)]:
            services[role] = {"image": "python:3.13-alpine", "command": ["python", "/echo.py"],
                              "environment": {"ROLE": role, "PORT": str(port)},
                              "volumes": [str(cls.directory / "echo.py") + ":/echo.py:ro"]}
        services["caddy"] = {
            "image": cls.image, "environment": {"PUBLIC_ORIGIN": "https://localhost"},
            "ports": ["127.0.0.1::80", "127.0.0.1::443"],
            "volumes": [str(ROOT / "infra/caddy") + ":/etc/caddy:ro", "data:/data", "config:/config"],
        }
        cls.compose_path = cls.directory / "compose.json"
        cls.compose_path.write_text(json.dumps({"services": services, "volumes": {"data": {}, "config": {}}}))
        cls.addClassCleanup(cls.cleanup_containers)
        cls.compose("up", "-d", timeout=240)
        cls.https_port = int(cls.compose("port", "caddy", "443").strip().rsplit(":", 1)[1])
        cls.http_port = int(cls.compose("port", "caddy", "80").strip().rsplit(":", 1)[1])
        cls.ca = cls.directory / "root.crt"
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                cls.compose("cp", "caddy:/data/caddy/pki/authorities/local/root.crt", str(cls.ca))
                proxy.check_proxy("https://localhost", local=True, port=cls.https_port, ca_bundle=cls.ca)
                break
            except (subprocess.CalledProcessError, ValueError):
                time.sleep(0.5)
        else:
            raise RuntimeError("Caddy did not become ready: " + cls.compose("logs", "--no-color", "caddy"))
        cls.cert = cls.directory / "localhost.crt"
        cls.key = cls.directory / "localhost.key"
        for path in [cls.cert, cls.key]:
            cls.compose("cp", "caddy:/data/caddy/certificates/local/localhost/" + path.name, str(path))
        cls.key.chmod(0o600)

    @classmethod
    def compose(cls, *args, timeout=30):
        result = subprocess.run(["docker", "compose", "--project-name", cls.project,
                                 "-f", str(cls.compose_path), *args],
                                capture_output=True, text=True, check=True, timeout=timeout)
        return result.stdout

    @classmethod
    def cleanup_containers(cls):
        cls.compose("down", "--volumes", "--remove-orphans", timeout=60)

    def get(self, path, **kwargs):
        return proxy.request("https://localhost", path, local=True,
                             port=self.https_port, ca_bundle=self.ca, **kwargs)

    def test_active_config_matches_adapted_caddyfile(self):
        active = self.compose("exec", "-T", "caddy", "wget", "-qO-", "http://127.0.0.1:2019/config/")
        candidate = self.compose("exec", "-T", "caddy", "caddy", "adapt", "--config",
                                 "/etc/caddy/Caddyfile", "--adapter", "caddyfile", "--validate")
        self.assertEqual(json.loads(active), json.loads(candidate))

    def test_routes_preserve_path_and_query(self):
        for path in ["/api/example?a=1", "/login/oauth2/code/kakao?code=test",
                     "/swagger-ui", "/swagger-ui/index.html", "/swagger-ui.html",
                     "/v3/api-docs", "/v3/api-docs/swagger-config"]:
            with self.subTest(path=path):
                status, _, body = self.get(path)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)["service"], "backend")
                self.assertEqual(json.loads(body)["path"], path)
        for path in ["/", "/assets/app.js", "/parent/home"]:
            self.assertEqual(json.loads(self.get(path)[2])["service"], "frontend")

    def test_forwarded_headers_cannot_be_spoofed(self):
        _, _, body = self.get("/api/example", headers={"X-Forwarded-Proto": "http", "X-Forwarded-Host": "evil.test"})
        headers = {key.lower(): value for key, value in json.loads(body)["headers"].items()}
        self.assertEqual(headers["host"], "localhost")
        self.assertEqual(headers["x-forwarded-proto"], "https")
        self.assertEqual(headers["x-forwarded-host"], "localhost")

    def test_http_redirects_to_https(self):
        status, headers, _ = proxy.request("http://localhost", "/api/example?q=1", local=True, port=self.http_port)
        self.assertEqual(status, 308)
        self.assertEqual(headers["Location"], "https://localhost/api/example?q=1")

    def test_cache_headers_pass_through_without_http3_advertisement(self):
        _, headers, _ = self.get("/assets/app.js")
        self.assertEqual(headers.get_all("Cache-Control"), ["public, max-age=60"])
        self.assertIsNone(headers.get("Alt-Svc"))

    def test_tls_rejects_untrusted_certificate(self):
        with self.assertRaises(proxy.ProxyConnectionError) as error:
            proxy.check_health("https://localhost", local=True, port=self.https_port)
        self.assertEqual(error.exception.returncode, 60)

    def test_tls_rejects_wrong_hostname(self):
        # Caddy may reject unknown SNI before serving a certificate. This server
        # always serves Caddy's trusted localhost certificate, including for wrong.test.
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"status":"ok"}')

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert, self.key)
        received_names = []
        context.set_servername_callback(lambda _socket, name, _context: received_names.append(name))
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            options = dict(local=True, port=server.server_port, ca_bundle=self.ca)
            proxy.check_health("https://localhost", **options)
            with self.assertRaises(proxy.ProxyConnectionError) as error:
                proxy.check_health("https://wrong.test", **options)
            self.assertIn("wrong.test", received_names)
            self.assertEqual(error.exception.returncode, 60)
            self.assertRegex(error.exception.stderr.lower(), r"no alternative certificate|does not match|doesn't match")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_backend_recreation_recovers_without_caddy_reload(self):
        previous_instance = json.loads(self.get("/api/example")[2])["instance"]
        caddy_id = self.compose("ps", "--quiet", "caddy")
        self.compose("up", "-d", "--no-deps", "--force-recreate", "backend")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                status, _, body = self.get("/api/example")
                if status == 200 and json.loads(body)["instance"] != previous_instance:
                    break
            except (ValueError, KeyError):
                pass
            time.sleep(0.5)
        else:
            self.fail("Caddy did not reach the recreated backend without reload")
        self.assertEqual(self.compose("ps", "--quiet", "caddy"), caddy_id)

    def test_health_cors_and_oauth_use_verified_tls(self):
        proxy.check_proxy("https://localhost", local=True, port=self.https_port, ca_bundle=self.ca)

    def test_upload_limit(self):
        payload = self.directory / "payload.bin"
        for size, expected in [(25 * 1024 * 1024, 200), (25 * 1024 * 1024 + 1, 413)]:
            with payload.open("wb") as output:
                output.truncate(size)
            result = subprocess.run(["curl", "--silent", "--show-error", "--noproxy", "*",
                                     "--max-time", "30", "--cacert", str(self.ca),
                                     "--connect-to", f"localhost:443:127.0.0.1:{self.https_port}",
                                     "--data-binary", "@" + str(payload), "--output", os.devnull,
                                     "--write-out", "%{http_code}", "https://localhost/api/upload"],
                                    capture_output=True, text=True, check=True)
            self.assertEqual(int(result.stdout), expected)


if __name__ == "__main__":
    unittest.main()
