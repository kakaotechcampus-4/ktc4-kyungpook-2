#!/usr/bin/env python3
"""Verify resolved deployment settings and HTTP/TLS without disabling trust checks."""

import argparse
from email.parser import Parser
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import parse_qs, urlparse

# .invalid is reserved and can never be an allowed frontend origin.
CORS_PROBE_ORIGIN = "https://cors-check.invalid"


class ProxyConnectionError(ValueError):
    def __init__(self, result):
        super().__init__(f"Proxy connection or certificate verification failed (curl exit {result.returncode})")
        self.returncode = result.returncode
        self.stderr = result.stderr


def parse_origin(value):
    if not re.fullmatch(r"https?://[a-zA-Z0-9.-]+(?::[0-9]+)?", value):
        raise ValueError("Invalid public origin")
    parsed = urlparse(value)
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    if not 1 <= port <= 65535:
        raise ValueError("Invalid public port")
    return parsed, port


def check_settings(config, expected, running_origin):
    parse_origin(expected)
    services = config["services"]
    origin = services["caddy"]["environment"]["PUBLIC_ORIGIN"]
    backend = services["backend"]["environment"]
    if origin != expected or running_origin != origin:
        raise ValueError("PUBLIC_ORIGIN mismatch; update .env and recreate Caddy before deployment")
    if origin not in [item.strip() for item in backend["CORS_ALLOWED_ORIGINS"].split(",")]:
        raise ValueError("CORS does not allow the public origin")
    if origin.startswith("https://") and str(backend["AUTH_COOKIE_SECURE"]).lower() != "true":
        raise ValueError("HTTPS requires AUTH_COOKIE_SECURE=true")
    if backend["AUTH_SUCCESS_REDIRECT"] != origin + "/oauth/success" or \
            backend["AUTH_FAILURE_REDIRECT"] != origin + "/login":
        raise ValueError("OAuth frontend redirects must use the public origin")
    return origin


def request(origin, path, *, method="GET", headers=None, local=False, port=None, ca_bundle=None):
    parsed, public_port = parse_origin(origin)
    command = ["curl", "--silent", "--show-error", "--noproxy", "*",
               "--connect-timeout", "2", "--max-time", "10",
               "--proto", "=" + parsed.scheme, "--request", method]
    if local:
        command += ["--resolve", f"{parsed.hostname}:{public_port}:127.0.0.1"]
        if port:
            # Test ports change the TCP destination, preserving URL, Host and TLS SNI.
            command += ["--connect-to", f"{parsed.hostname}:{public_port}:127.0.0.1:{port}"]
    if ca_bundle:
        command += ["--cacert", str(ca_bundle)]
    for key, value in (headers or {}).items():
        command += ["--header", f"{key}: {value}"]
    with tempfile.TemporaryDirectory(prefix="proxy-check-") as directory:
        header_path = Path(directory) / "headers"
        body_path = Path(directory) / "body"
        command += ["--dump-header", str(header_path), "--output", str(body_path),
                    "--write-out", "%{http_code}", origin + path]
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode:
            raise ProxyConnectionError(result)
        # curl can emit informational responses before the final response headers.
        blocks = header_path.read_text().strip().split("\n\n")
        response_headers = Parser().parsestr(blocks[-1].split("\n", 1)[1])
        return int(result.stdout), response_headers, body_path.read_text()


def check_health(origin, **options):
    status, _, body = request(origin, "/api/health", **options)
    if status != 200 or json.loads(body) != {"status": "ok"}:
        raise ValueError("Proxy health verification failed")


def check_proxy(origin, **options):
    check_health(origin, **options)
    # The frontend and API share the public origin, so browsers do not use CORS and Spring adds
    # no CORS headers to same-origin requests. Check that a foreign origin is rejected instead;
    # check_settings verifies that the public origin is in the allowed list.
    status, headers, _ = request(origin, "/api/health", method="OPTIONS", headers={
        "Origin": CORS_PROBE_ORIGIN, "Access-Control-Request-Method": "GET",
    }, **options)
    if status != 403 or "Access-Control-Allow-Origin" in headers:
        raise ValueError("CORS verification failed")
    status, headers, _ = request(origin, "/oauth2/authorization/kakao", **options)
    callback = parse_qs(urlparse(headers.get("Location", "")).query).get("redirect_uri", [""])[0]
    if status != 302 or callback != origin + "/login/oauth2/code/kakao":
        raise ValueError("OAuth redirect URI verification failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["settings", "health", "verify"])
    parser.add_argument("--origin", required=True)
    parser.add_argument("--running-origin")
    parser.add_argument("--local", action="store_true")
    parser.add_argument("--port", type=int)
    parser.add_argument("--ca-bundle")
    args = parser.parse_args()
    try:
        if args.port is not None and not 1 <= args.port <= 65535:
            raise ValueError("Invalid proxy port")
        if args.command == "settings":
            print(check_settings(json.load(sys.stdin), args.origin, args.running_origin))
        else:
            check = check_health if args.command == "health" else check_proxy
            check(args.origin, local=args.local, port=args.port, ca_bundle=args.ca_bundle)
    except (ValueError, KeyError, TypeError, OSError) as error:
        # Do not print resolved Compose settings: they contain credentials.
        print(str(error) if isinstance(error, ValueError) else "Invalid proxy configuration", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
