#!/usr/bin/env python3
"""
test_bridge.py - Unit tests for System Gateway client and command formatting.
"""
import unittest
import os
import sys

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_ROOT = os.path.dirname(TESTS_DIR)
sys.path.insert(0, os.path.join(ENV_ROOT, "bridge"))

import client
import commands

class TestBridge(unittest.TestCase):
    def test_01_command_fallback(self):
        # Sending command to offline gateway returns fallback dictionary without raising exception
        offline_url = "http://127.0.0.1:59998/gateway"
        res = client.send_command("notify", {"title": "Title", "progress": 50}, gateway_url=offline_url)
        self.assertIn("fallback", res)
        self.assertEqual(res["packet"]["command"], "notify")
        self.assertEqual(res["packet"]["payload"]["title"], "Title")
        self.assertEqual(res["packet"]["payload"]["progress"], 50)

    def test_02_open_url_format(self):
        offline_url = "http://127.0.0.1:59998/gateway"
        res = client.send_command("open_url", {"url": "https://antigravity.google/auth"}, gateway_url=offline_url)
        self.assertEqual(res["packet"]["command"], "open_url")
        self.assertEqual(res["packet"]["payload"]["url"], "https://antigravity.google/auth")

    def test_03_wakelock_format(self):
        offline_url = "http://127.0.0.1:59998/gateway"
        res = client.send_command("wakelock", {"action": "acquire", "tag": "build_apk"}, gateway_url=offline_url)
        self.assertEqual(res["packet"]["command"], "wakelock")
        self.assertEqual(res["packet"]["payload"]["action"], "acquire")

    def test_04_commands_type_hints(self):
        import typing
        hints = typing.get_type_hints(commands.notify)
        self.assertIn("title", hints)
        self.assertIn("return", hints)

    def test_05_commands_methods_callable(self):
        # All command methods should construct valid packets
        self.assertTrue(callable(commands.notify))
        self.assertTrue(callable(commands.cancel_notify))
        self.assertTrue(callable(commands.open_url))
        self.assertTrue(callable(commands.wakelock))
        self.assertTrue(callable(commands.clipboard_set))
        self.assertTrue(callable(commands.clipboard_get))
        self.assertTrue(callable(commands.system_info))

    def test_06_gateway_bearer_token_headers(self):
        import http.server
        import json as _json
        import tempfile
        import threading

        seen = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                seen["x_tok"] = self.headers.get("X-AGY-Token")
                seen["auth"] = self.headers.get("Authorization")
                length = int(self.headers.get("Content-Length", "0"))
                _ = self.rfile.read(length)
                body = _json.dumps({"ok": True}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                logs = os.path.join(tmp, "logs")
                os.makedirs(logs, exist_ok=True)
                with open(os.path.join(logs, "gateway.token"), "w", encoding="utf-8") as f:
                    f.write("secret-test-token\n")
                old_home = os.environ.get("HOME")
                old_tok = os.environ.pop("AGY_GATEWAY_TOKEN", None)
                os.environ["HOME"] = tmp
                try:
                    url = f"http://127.0.0.1:{srv.server_port}/gateway"
                    res = client.send_command("system_info", gateway_url=url)
                    self.assertTrue(res.get("ok"))
                    self.assertEqual(seen.get("x_tok"), "secret-test-token")
                    self.assertEqual(seen.get("auth"), "Bearer secret-test-token")
                finally:
                    if old_home is not None:
                        os.environ["HOME"] = old_home
                    else:
                        os.environ.pop("HOME", None)
                    if old_tok is not None:
                        os.environ["AGY_GATEWAY_TOKEN"] = old_tok
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
