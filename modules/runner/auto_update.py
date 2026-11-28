#!/usr/bin/env python3
"""
auto_update.py - Automated upstream Antigravity Linux ARM64 checker, extractor,
patch verifier, and metadata updater for CI and local automation.
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from typing import Dict, Optional, Tuple

RUNNER_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(RUNNER_DIR)
PROJECT_ROOT = os.path.dirname(MODULES_DIR)

sys.path.insert(0, os.path.join(MODULES_DIR, "patches"))
import pipeline  # noqa: E402

DOWNLOAD_PAGE_URL = "https://antigravity.google/download"
UPSTREAM_URL_RE = re.compile(
    r"https://storage\.googleapis\.com/antigravity-public/antigravity-hub/"
    r"([0-9]+\.[0-9]+\.[0-9]+)-([0-9]+)/linux-arm/Antigravity\.tar\.gz"
)

# Custom Android web files that must never be overwritten by upstream web assets.
CUSTOM_WEB_FILES = {
    "index.html",
    "README.md",
    "antigravity-icon.png",
    "build.sh",
    "patch_main.py",
    "src.manifest",
    "test_harness.html",
}


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1048576):
            h.update(chunk)
    return h.hexdigest().lower()


def parse_env_file(path: str) -> Dict[str, str]:
    vals: Dict[str, str] = {}
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            vals[k.strip()] = v.strip()
    return vals


def semver_tuple(ver: str) -> Tuple[int, int, int]:
    parts = [int(p) for p in ver.strip().split(".")[:3]]
    while len(parts) < 3:
        parts.append(0)
    return (parts[0], parts[1], parts[2])


def compute_next_app_version(
    upstream_ver: str, current_app_ver: str, current_app_code: int
) -> Tuple[str, int]:
    """Compute next APP_VERSION_NAME and strictly increasing APP_VERSION_CODE."""
    up = semver_tuple(upstream_ver)
    cur = semver_tuple(current_app_ver)
    if up > cur:
        code = up[0] * 10000 + up[1] * 100 + up[2]
        return upstream_ver, max(code, current_app_code + 1)
    # Same semver (e.g. rebuild with new build_id) or local patch ahead of core:
    bumped = (cur[0], cur[1], cur[2] + 1)
    ver_str = f"{bumped[0]}.{bumped[1]}.{bumped[2]}"
    code = bumped[0] * 10000 + bumped[1] * 100 + bumped[2]
    return ver_str, max(code, current_app_code + 1)


def fetch_upstream_release(url: str = DOWNLOAD_PAGE_URL) -> Tuple[str, str, str, str]:
    """Return (version, build_id, build_tag, tarball_url) from antigravity.google/download."""
    req = urllib.request.Request(
        url, headers={"User-Agent": "Antigravity-Android-AutoUpdater/1.0"}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        html = resp.read().decode("utf-8", errors="replace")
    m = UPSTREAM_URL_RE.search(html)
    if not m:
        raise RuntimeError(f"Could not find linux-arm Antigravity.tar.gz URL in {url}")
    ver, build_id = m.group(1), m.group(2)
    return ver, build_id, f"{ver}-{build_id}", m.group(0)


def extract_embedded_web_zip(core_bytes: bytes, dest_dir: str) -> int:
    """Locate and extract the embedded web asset ZIP inside language_server."""
    pos = len(core_bytes)
    while True:
        eocd = core_bytes.rfind(b"PK\x05\x06", 0, pos)
        if eocd < 0:
            raise RuntimeError("Embedded web asset ZIP EOCD not found in language_server")
        if eocd + 22 <= len(core_bytes):
            cd_size, cd_off = struct.unpack_from("<II", core_bytes, eocd + 12)
            zip_start = eocd - cd_size - cd_off
            if 0 <= zip_start < eocd:
                try:
                    zf = zipfile.ZipFile(io.BytesIO(core_bytes[zip_start:eocd + 22]))
                    names = zf.namelist()
                    if "main.js" in names and "compiled_tailwind.css" in names:
                        zf.extractall(dest_dir)
                        return len(names)
                except zipfile.BadZipFile:
                    pass
        pos = eocd
    raise RuntimeError("No valid embedded web asset ZIP found in language_server")


def sync_web_assets(extracted_web_dir: str, web_dir: str) -> int:
    """Copy upstream web assets into web/, preserving custom Android files."""
    updated = 0
    for root, _, files in os.walk(extracted_web_dir):
        rel_dir = os.path.relpath(root, extracted_web_dir)
        for fn in files:
            if rel_dir == "." and fn in CUSTOM_WEB_FILES:
                continue
            src = os.path.join(root, fn)
            dst_dir = web_dir if rel_dir == "." else os.path.join(web_dir, rel_dir)
            os.makedirs(dst_dir, exist_ok=True)
            dst = os.path.join(dst_dir, fn)
            shutil.copy2(src, dst)
            updated += 1
    return updated


def repack_pristine_core(core_bin: str, dest_tar_gz: str) -> str:
    """Create pristine language_server.tar.gz containing a flat 0700 language_server entry."""
    tmp_tar = dest_tar_gz + ".tmp"
    with tarfile.open(tmp_tar, "w:gz") as tf:
        info = tf.gettarinfo(core_bin, arcname="language_server")
        info.mode = 0o700
        info.uid = 0
        info.gid = 0
        info.uname = "root"
        info.gname = "root"
        with open(core_bin, "rb") as f:
            tf.addfile(info, f)
    os.chmod(tmp_tar, 0o644)
    os.replace(tmp_tar, dest_tar_gz)
    return sha256_file(dest_tar_gz)


def replace_regex_once(path: str, pattern: str, repl: str, flags: int = 0) -> None:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    new_text, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"Expected 1 match for {pattern!r} in {path}, got {count}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_text)


def update_metadata_files(
    root: str,
    app_ver: str,
    app_code: int,
    core_ver: str,
    build_tag: str,
    archive_sha: str,
    raw_sha: str,
    raw_size: int,
    final_sha_full: str,
    final_sha_nogates: str,
) -> None:
    """Update all version specs, checksums, test expectations, and release docs."""
    app_env = os.path.join(root, "config", "app.env")
    replace_regex_once(app_env, r"^APP_VERSION_NAME=.*$", f"APP_VERSION_NAME={app_ver}", re.M)
    replace_regex_once(app_env, r"^APP_VERSION_CODE=.*$", f"APP_VERSION_CODE={app_code}", re.M)
    replace_regex_once(app_env, r"^CORE_VERSION=.*$", f"CORE_VERSION={core_ver}", re.M)

    manifest = os.path.join(root, "app", "src", "main", "AndroidManifest.xml")
    replace_regex_once(manifest, r'android:versionCode="[^"]+"', f'android:versionCode="{app_code}"')
    replace_regex_once(manifest, r'android:versionName="[^"]+"', f'android:versionName="{app_ver}"')

    ver_env = os.path.join(root, "modules", "core", "spec", "version.env")
    replace_regex_once(ver_env, r"^CORE_VERSION=.*$", f"CORE_VERSION={core_ver}", re.M)
    replace_regex_once(ver_env, r"^CORE_BUILD_TAG=.*$", f"CORE_BUILD_TAG={build_tag}", re.M)

    checksums_path = os.path.join(root, "modules", "core", "spec", "checksums.json")
    with open(checksums_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "archive": {"filename": "language_server.tar.gz", "sha256": archive_sha},
                "binary": {"filename": "language_server", "sha256": raw_sha, "size": raw_size},
            },
            f,
            indent=2,
        )
        f.write("\n")

    for py_path in (
        os.path.join(root, "modules", "core", "tests", "test_elf_validator.py"),
        os.path.join(root, "modules", "core", "validate", "verify_pipeline.py"),
    ):
        replace_regex_once(
            py_path,
            r'expected_archive_sha = "[0-9a-f]{64}"',
            f'expected_archive_sha = "{archive_sha}"',
        )

    test_full = os.path.join(root, "modules", "patches", "tests", "test_full_pipeline.py")
    replace_regex_once(
        test_full,
        r'EXPECTED_FINAL_SHA = "[0-9a-f]{64}"',
        f'EXPECTED_FINAL_SHA = "{final_sha_full}"',
    )
    replace_regex_once(
        test_full,
        r'EXPECTED_NO_GATES_SHA = "[0-9a-f]{64}"',
        f'EXPECTED_NO_GATES_SHA = "{final_sha_nogates}"',
    )

    prep = os.path.join(root, "modules", "runner", "prepare_runtime.py")
    replace_regex_once(
        prep,
        r'TARGET_SHA_FULL = "[0-9a-f]{64}"',
        f'TARGET_SHA_FULL = "{final_sha_full}"',
    )
    replace_regex_once(
        prep,
        r'TARGET_SHA_NO_BYPASS = "[0-9a-f]{64}"',
        f'TARGET_SHA_NO_BYPASS = "{final_sha_nogates}"',
    )

    readme = os.path.join(root, "README.md")
    replace_regex_once(readme, r"Version-[0-9]+\.[0-9]+\.[0-9]+", f"Version-{app_ver}")

    notes = os.path.join(root, "RELEASE_NOTES.md")
    with open(notes, "r", encoding="utf-8") as f:
        old_notes = f.read()
    header = f"# Antigravity for Android v{app_ver}\n"
    if header not in old_notes:
        new_section = (
            f"# Antigravity for Android v{app_ver}\n\n"
            "## Release Packages\n\n"
            "| Package | Application ID | Runtime Port | Description |\n"
            "| :--- | :--- | :--- | :--- |\n"
            "| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |\n"
            "| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |\n"
            "| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |\n\n"
            f"## Changes & Fixes (v{app_ver})\n\n"
            f"### 1. Upstream Antigravity {core_ver} Core & Web UI\n"
            f"* Automated upstream upgrade to `language_server` (`{build_tag}`) and latest embedded web UI assets.\n"
            "* Verified all ELF compatibility patches (`crash_suppressor`, `pidfd`, `resolv_redirect`, `sigill_handler`, `sigprocmask`, `faccessat`, `fchmodat`, `pty_shell`, gates) and `web/build.sh` bundles.\n\n"
            "---\n\n"
        )
        with open(notes, "w", encoding="utf-8") as f:
            f.write(new_section + old_notes)

    wf = os.path.join(root, ".github", "workflows", "build-and-release.yml")
    replace_regex_once(
        wf,
        r"description: 'Release Tag \(e\.g\. v[^']+\)'",
        f"description: 'Release Tag (e.g. v{app_ver})'",
    )
    replace_regex_once(wf, r"default: 'v[0-9]+\.[0-9]+\.[0-9]+'", f"default: 'v{app_ver}'")
    replace_regex_once(wf, r"### What's New in v[0-9]+\.[0-9]+\.[0-9]+", f"### What's New in v{app_ver}")
    replace_regex_once(
        wf,
        r"\*\*Upstream Antigravity [0-9]+\.[0-9]+\.[0-9]+:\*\*[^\n]*",
        f"**Upstream Antigravity {core_ver}:** Updated `language_server` (`{build_tag}`) and embedded web UI assets with automated patch verification.",
    )
    replace_regex_once(wf, r"APP_VERSION_CODE=[0-9]+", f"APP_VERSION_CODE={app_code}")


def patch_and_verify_core(raw_bin: str, skip_gates: bool) -> Tuple[bool, str, str]:
    with tempfile.TemporaryDirectory(prefix="agy_patch_") as tmp:
        staged = os.path.join(tmp, "language_server")
        shutil.copyfile(raw_bin, staged)
        ok_apply = pipeline.run_pipeline(staged, allow_missing=False, skip_gates=skip_gates)
        if not ok_apply:
            return False, "", f"Patch apply failed (skip_gates={skip_gates})"
        ok_verify = pipeline.run_pipeline(staged, verify=True, allow_missing=False, skip_gates=skip_gates)
        if not ok_verify:
            return False, "", f"Patch verify failed (skip_gates={skip_gates})"
        return True, sha256_file(staged), ""


def write_github_output(path: str, data: Dict[str, str]) -> None:
    if not path:
        return
    with open(path, "a", encoding="utf-8") as f:
        for k, v in data.items():
            f.write(f"{k}={v}\n")


def cmd_check(args: argparse.Namespace) -> int:
    app_env = parse_env_file(os.path.join(PROJECT_ROOT, "config", "app.env"))
    ver_env = parse_env_file(os.path.join(PROJECT_ROOT, "modules", "core", "spec", "version.env"))
    cur_tag = ver_env.get("CORE_BUILD_TAG", "")
    cur_app_ver = app_env.get("APP_VERSION_NAME", "0.0.0")
    cur_app_code = int(app_env.get("APP_VERSION_CODE", "0"))

    up_ver, up_build, up_tag, up_url = fetch_upstream_release(args.download_url)
    needs_update = (up_tag != cur_tag) or args.force
    new_app_ver, new_app_code = compute_next_app_version(up_ver, cur_app_ver, cur_app_code)

    result = {
        "needs_update": "true" if needs_update else "false",
        "current_build_tag": cur_tag,
        "upstream_version": up_ver,
        "upstream_build_id": up_build,
        "upstream_build_tag": up_tag,
        "upstream_url": up_url,
        "new_app_version": new_app_ver,
        "new_app_code": str(new_app_code),
    }
    write_github_output(args.github_output, result)
    print(json.dumps(result, indent=2))
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    app_env = parse_env_file(os.path.join(PROJECT_ROOT, "config", "app.env"))
    ver_env = parse_env_file(os.path.join(PROJECT_ROOT, "modules", "core", "spec", "version.env"))
    cur_tag = ver_env.get("CORE_BUILD_TAG", "")
    cur_app_ver = app_env.get("APP_VERSION_NAME", "0.0.0")
    cur_app_code = int(app_env.get("APP_VERSION_CODE", "0"))

    if args.tarball:
        up_ver = args.upstream_version or cur_app_ver
        up_build = args.upstream_build_id or "0"
        up_tag = f"{up_ver}-{up_build}"
        up_url = args.tarball
    else:
        up_ver, up_build, up_tag, up_url = fetch_upstream_release(args.download_url)

    if up_tag == cur_tag and not args.force:
        print(f"[auto-update] Already at latest upstream build: {cur_tag}")
        return 0

    new_app_ver, new_app_code = compute_next_app_version(up_ver, cur_app_ver, cur_app_code)
    summary: Dict[str, object] = {
        "upstream_version": up_ver,
        "upstream_build_tag": up_tag,
        "upstream_url": up_url,
        "new_app_version": new_app_ver,
        "new_app_code": new_app_code,
        "status": "pending",
        "errors": [],
    }

    with tempfile.TemporaryDirectory(prefix="agy_autoupdate_") as tmp:
        tar_path = args.tarball or os.path.join(tmp, "Antigravity.tar.gz")
        if not args.tarball:
            print(f"[auto-update] Downloading {up_url}...")
            req = urllib.request.Request(
                up_url, headers={"User-Agent": "Antigravity-Android-AutoUpdater/1.0"}
            )
            with urllib.request.urlopen(req, timeout=300) as resp, open(tar_path, "wb") as out:
                shutil.copyfileobj(resp, out, 1048576)

        print("[auto-update] Extracting language_server...")
        raw_bin = os.path.join(tmp, "language_server")
        with tarfile.open(tar_path, "r:gz") as tf:
            members = [m for m in tf.getmembers() if m.isfile() and m.name.endswith("resources/bin/language_server")]
            if not members:
                raise RuntimeError("resources/bin/language_server not found in upstream tarball")
            src_f = tf.extractfile(members[0])
            assert src_f is not None
            with open(raw_bin, "wb") as out:
                shutil.copyfileobj(src_f, out, 1048576)

        raw_sha = sha256_file(raw_bin)
        raw_size = os.path.getsize(raw_bin)
        pristine_tar = os.path.join(PROJECT_ROOT, "modules", "core", "pristine", "language_server.tar.gz")
        archive_sha = repack_pristine_core(raw_bin, pristine_tar)
        print(f"[auto-update] Packed language_server.tar.gz (sha256={archive_sha[:12]}...)")

        web_ext_dir = os.path.join(tmp, "web_extracted")
        with open(raw_bin, "rb") as f:
            core_bytes = f.read()
        count = extract_embedded_web_zip(core_bytes, web_ext_dir)
        synced = sync_web_assets(web_ext_dir, os.path.join(PROJECT_ROOT, "web"))
        print(f"[auto-update] Synced {synced}/{count} upstream web files into web/")

        errors = []
        ok_full, sha_full, err_full = patch_and_verify_core(raw_bin, skip_gates=False)
        if not ok_full:
            errors.append(err_full)
        ok_ng, sha_ng, err_ng = patch_and_verify_core(raw_bin, skip_gates=True)
        if not ok_ng:
            errors.append(err_ng)

        web_res = subprocess.run(
            ["bash", os.path.join(PROJECT_ROOT, "web", "build.sh")],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
        )
        if web_res.returncode != 0:
            errors.append(f"web/build.sh failed:\n{web_res.stderr or web_res.stdout}")
        else:
            selftest_res = subprocess.run(
                ["bash", os.path.join(PROJECT_ROOT, "web", "tests", "selftest.sh")],
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
            )
            if selftest_res.returncode != 0:
                errors.append(f"web/tests/selftest.sh failed:\n{selftest_res.stderr or selftest_res.stdout}")

        if errors:
            summary["status"] = "patch_failed"
            summary["errors"] = errors
            if args.summary_out:
                with open(args.summary_out, "w", encoding="utf-8") as f:
                    json.dump(summary, f, indent=2)
            print("[auto-update] PATCH/WEB VALIDATION FAILED:\n" + "\n---\n".join(errors), file=sys.stderr)
            return 2

        update_metadata_files(
            PROJECT_ROOT,
            app_ver=new_app_ver,
            app_code=new_app_code,
            core_ver=up_ver,
            build_tag=up_tag,
            archive_sha=archive_sha,
            raw_sha=raw_sha,
            raw_size=raw_size,
            final_sha_full=sha_full,
            final_sha_nogates=sha_ng,
        )
        summary["status"] = "ok"
        summary["archive_sha256"] = archive_sha
        summary["binary_sha256"] = raw_sha
        summary["final_sha_full"] = sha_full
        summary["final_sha_nogates"] = sha_ng
        if args.summary_out:
            with open(args.summary_out, "w", encoding="utf-8") as f:
                json.dump(summary, f, indent=2)
        print(json.dumps(summary, indent=2))
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Antigravity upstream auto-updater.")
    parser.add_argument("--download-url", default=DOWNLOAD_PAGE_URL)
    parser.add_argument("--force", action="store_true", help="Run even if build tag matches")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_check = sub.add_parser("check", help="Check if upstream has a newer build")
    p_check.add_argument("--github-output", default="")

    p_apply = sub.add_parser("apply", help="Download, extract, patch, and update repo")
    p_apply.add_argument("--tarball", default="", help="Optional local Antigravity.tar.gz path")
    p_apply.add_argument("--upstream-version", default="", help="Override version when using --tarball")
    p_apply.add_argument("--upstream-build-id", default="", help="Override build id when using --tarball")
    p_apply.add_argument("--summary-out", default="", help="Write JSON summary to file")

    args = parser.parse_args()
    if args.cmd == "check":
        return cmd_check(args)
    return cmd_apply(args)


if __name__ == "__main__":
    sys.exit(main())
