#!/usr/bin/env python3
"""
test_auto_update.py - Offline unit tests for modules/runner/auto_update.py.
"""
import io
import os
import struct
import sys
import tempfile
import unittest
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
import auto_update


class TestAutoUpdate(unittest.TestCase):
    def test_upstream_url_regex(self):
        html = (
            '<a href="https://storage.googleapis.com/antigravity-public/'
            'antigravity-hub/2.37.1-9988776655443322/linux-arm/Antigravity.tar.gz">dl</a>'
        )
        m = auto_update.UPSTREAM_URL_RE.search(html)
        self.assertIsNotNone(m)
        self.assertEqual(m.group(1), "2.37.1")
        self.assertEqual(m.group(2), "9988776655443322")

    def test_compute_next_app_version(self):
        # Upstream minor bump (2.36.0 -> 2.37.0) bumps app minor independently of core version
        self.assertEqual(
            auto_update.compute_next_app_version("2.37.0", "3.0.2", 30002, current_core_ver="2.36.0"),
            ("3.1.0", 30100),
        )
        # Upstream patch bump (2.36.0 -> 2.36.1) or build-id rebuild bumps app patch
        self.assertEqual(
            auto_update.compute_next_app_version("2.36.1", "3.0.0", 30000, current_core_ver="2.36.0"),
            ("3.0.1", 30001),
        )
        self.assertEqual(
            auto_update.compute_next_app_version("2.36.0", "3.0.3", 30003, current_core_ver="2.36.0"),
            ("3.0.4", 30004),
        )

    def test_bump_app_semver(self):
        self.assertEqual(auto_update.bump_app_semver("3.0.0", 30000, "patch"), ("3.0.1", 30001))
        self.assertEqual(auto_update.bump_app_semver("3.0.4", 30004, "minor"), ("3.1.0", 30100))
        self.assertEqual(auto_update.bump_app_semver("3.1.2", 30102, "major"), ("4.0.0", 40000))
        # Guarantees strictly monotonic versionCode even if existing code is ahead
        self.assertEqual(auto_update.bump_app_semver("3.0.0", 30500, "patch"), ("3.0.1", 30501))

    def test_extract_embedded_web_zip_and_sync_preserves_custom_files(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("main.js", "console.log('new');")
            zf.writestr("compiled_tailwind.css", "body{}")
            zf.writestr("index.html", "<html>upstream</html>")
            zf.writestr("prism/components/prism-latex.min.js", "/*latex*/")

        fake_bin = b"\x7fELF" + b"\x00" * 128 + buf.getvalue() + b"\x7fELF" + b"\x00" * 64
        with tempfile.TemporaryDirectory() as tmp:
            ext_dir = os.path.join(tmp, "ext")
            web_dir = os.path.join(tmp, "web")
            os.makedirs(web_dir)
            with open(os.path.join(web_dir, "index.html"), "w", encoding="utf-8") as f:
                f.write("<html>custom-android</html>")
            with open(os.path.join(web_dir, "patch_main.py"), "w", encoding="utf-8") as f:
                f.write("# custom")

            n = auto_update.extract_embedded_web_zip(fake_bin, ext_dir)
            self.assertEqual(n, 4)
            synced = auto_update.sync_web_assets(ext_dir, web_dir)
            self.assertEqual(synced, 3)

            with open(os.path.join(web_dir, "index.html"), "r", encoding="utf-8") as f:
                self.assertEqual(f.read(), "<html>custom-android</html>")
            with open(os.path.join(web_dir, "main.js"), "r", encoding="utf-8") as f:
                self.assertEqual(f.read(), "console.log('new');")
            self.assertTrue(os.path.isfile(os.path.join(web_dir, "prism", "components", "prism-latex.min.js")))


if __name__ == "__main__":
    unittest.main()
