#!/usr/bin/env python3
"""Automated upstream sync, safety verification, version bump, and release trigger.

Syncs `upstream/main` (salmanbappi/antigravity-android) into the fork's `main`
branch, resolves only safe release-metadata / regenerable-asset conflicts,
runs verification gates, bumps the patch version, pushes `main` + tag, and
dispatches `.github/workflows/build-and-release.yml`.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_UPSTREAM_URL = "https://github.com/salmanbappi/antigravity-android.git"

ALLOWED_CONFLICT_EXACT = {
    "config/app.env",
    "app/src/main/AndroidManifest.xml",
    "README.md",
    "RELEASE_NOTES.md",
    ".github/workflows/build-and-release.yml",
}
ALLOWED_CONFLICT_PREFIXES = ("web/generated/",)

REQUIRED_CUSTOM_FILES = [
    "plugins/github/auth.json",
    "app/src/main/java/com/agy/oauth/GitHubDeviceAuth.java",
    "web/src/patches/back_navigation/install.js",
    "web/src/patches/swarm_pane_chip/install.js",
    "env/seed-packages.txt",
]

SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def run(
    cmd: list[str],
    *,
    check: bool = True,
    capture: bool = True,
    cwd: Path = REPO_ROOT,
) -> subprocess.CompletedProcess[str]:
    print(f"+ {' '.join(cmd)}", flush=True)
    return subprocess.run(
        cmd,
        cwd=str(cwd),
        text=True,
        capture_output=capture,
        check=check,
    )


def parse_semver(raw: str) -> Optional[Tuple[int, int, int]]:
    m = SEMVER_RE.match(raw.strip())
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def format_semver(ver: Tuple[int, int, int]) -> str:
    return f"{ver[0]}.{ver[1]}.{ver[2]}"


def compute_version_code(ver: Tuple[int, int, int], current_code: int) -> int:
    code = ver[0] * 10000 + ver[1] * 100 + ver[2]
    if code <= current_code:
        code = current_code + 1
    return code


def is_allowed_conflict(path: str) -> bool:
    if path in ALLOWED_CONFLICT_EXACT:
        return True
    return any(path.startswith(prefix) for prefix in ALLOWED_CONFLICT_PREFIXES)


def ensure_upstream_remote(upstream_url: str) -> None:
    res = run(["git", "remote", "get-url", "upstream"], check=False)
    if res.returncode != 0:
        run(["git", "remote", "add", "upstream", upstream_url])
    elif res.stdout.strip() != upstream_url:
        run(["git", "remote", "set-url", "upstream", upstream_url])

    run(["git", "fetch", "upstream", "main"])
    # Fetch new upstream tags without failing if a fork tag shares a name.
    tag_fetch = run(["git", "fetch", "upstream", "--tags"], check=False)
    if tag_fetch.returncode != 0:
        print(
            "[sync] Note: git fetch upstream --tags exited non-zero "
            "(likely existing local tag collision); using ls-remote for upstream tags.",
            flush=True,
        )


def get_latest_remote_v_tag(remote: str) -> Tuple[Optional[str], Tuple[int, int, int]]:
    res = run(["git", "ls-remote", "--tags", remote, "refs/tags/v*"], check=False)
    best_tag: Optional[str] = None
    best_ver: Tuple[int, int, int] = (0, 0, 0)
    if res.returncode != 0:
        return best_tag, best_ver

    for line in res.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        ref = parts[1].strip()
        if ref.endswith("^{}"):
            ref = ref[:-3]
        if not ref.startswith("refs/tags/"):
            continue
        tag = ref[len("refs/tags/") :]
        ver = parse_semver(tag)
        if ver and ver > best_ver:
            best_ver = ver
            best_tag = tag
    return best_tag, best_ver


def get_latest_local_v_tag() -> Tuple[Optional[str], Tuple[int, int, int]]:
    res = run(["git", "tag", "-l", "v*"], check=False)
    best_tag: Optional[str] = None
    best_ver: Tuple[int, int, int] = (0, 0, 0)
    if res.returncode != 0:
        return best_tag, best_ver

    for line in res.stdout.splitlines():
        tag = line.strip()
        ver = parse_semver(tag)
        if ver and ver > best_ver:
            best_ver = ver
            best_tag = tag
    return best_tag, best_ver


def read_app_env_values(content: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        k, v = stripped.split("=", 1)
        values[k.strip()] = v.strip()
    return values


def abort_merge_and_clean() -> None:
    merge_head = REPO_ROOT / ".git" / "MERGE_HEAD"
    if merge_head.exists():
        run(["git", "merge", "--abort"], check=False)
    run(["git", "reset", "--hard", "HEAD"], check=False)


def upsert_github_issue(title: str, body: str, *, dry_run: bool = False) -> None:
    if dry_run:
        print(f"[dry-run] Would open/update GitHub Issue: {title}\n{body}", flush=True)
        return

    list_res = run(
        [
            "gh",
            "issue",
            "list",
            "--state",
            "open",
            "--limit",
            "50",
            "--json",
            "number,title",
        ],
        check=False,
    )
    existing_number: Optional[int] = None
    if list_res.returncode == 0 and list_res.stdout.strip():
        try:
            issues = json.loads(list_res.stdout)
            for item in issues:
                if item.get("title") == title:
                    existing_number = int(item["number"])
                    break
        except (ValueError, KeyError, TypeError):
            existing_number = None

    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime(
        "%Y-%m-%d %H:%M UTC"
    )
    full_body = f"{body.rstrip()}\n\n_Last checked by Auto-Sync: {timestamp}_\n"

    if existing_number is not None:
        print(f"[sync] Updating existing issue #{existing_number}: {title}", flush=True)
        run(
            ["gh", "issue", "edit", str(existing_number), "--body", full_body],
            check=False,
        )
    else:
        print(f"[sync] Creating new issue: {title}", flush=True)
        run(
            ["gh", "issue", "create", "--title", title, "--body", full_body],
            check=False,
        )


def resolve_allowed_conflicts(files: Iterable[str]) -> None:
    # Inspect upstream CORE_VERSION before overwriting config/app.env with --ours.
    upstream_core_ver: Optional[str] = None
    if "config/app.env" in files:
        up_env = run(["git", "show", "upstream/main:config/app.env"], check=False)
        if up_env.returncode == 0:
            upstream_core_ver = read_app_env_values(up_env.stdout).get("CORE_VERSION")

    for path in files:
        # Check if file exists in HEAD (ours); if not, remove it cleanly.
        in_head = run(["git", "cat-file", "-e", f"HEAD:{path}"], check=False)
        if in_head.returncode == 0:
            run(["git", "checkout", "--ours", "--", path])
            run(["git", "add", "--", path])
        else:
            run(["git", "rm", "-f", "--", path], check=False)

    # Preserve higher upstream CORE_VERSION if upstream bumped it.
    if upstream_core_ver:
        env_path = REPO_ROOT / "config/app.env"
        content = env_path.read_text(encoding="utf-8")
        ours_core = read_app_env_values(content).get("CORE_VERSION", "")
        up_parsed = parse_semver(upstream_core_ver)
        our_parsed = parse_semver(ours_core)
        if up_parsed and (our_parsed is None or up_parsed > our_parsed):
            updated = re.sub(
                r"^CORE_VERSION=.*$",
                f"CORE_VERSION={upstream_core_ver}",
                content,
                flags=re.MULTILINE,
            )
            env_path.write_text(updated, encoding="utf-8")
            run(["git", "add", "--", "config/app.env"])


def rebuild_web_generated() -> Tuple[bool, str]:
    res1 = run(["bash", "web/build.sh"], check=False)
    if res1.returncode != 0:
        return False, f"bash web/build.sh failed (exit {res1.returncode}):\n{res1.stdout}\n{res1.stderr}"

    main_src = REPO_ROOT / "web/main.js"
    main_dst = REPO_ROOT / "web/generated/main.js"
    main_dst.parent.mkdir(parents=True, exist_ok=True)
    main_dst.write_bytes(main_src.read_bytes())

    res2 = run(["python3", "web/patch_main.py", "web/generated/main.js"], check=False)
    if res2.returncode != 0:
        return False, (
            f"python3 web/patch_main.py web/generated/main.js failed "
            f"(exit {res2.returncode}):\n{res2.stdout}\n{res2.stderr}"
        )
    return True, ""


def run_verification_gates() -> Tuple[bool, str]:
    missing = [p for p in REQUIRED_CUSTOM_FILES if not (REPO_ROOT / p).is_file()]
    if missing:
        return False, "Missing required custom fork files:\n" + "\n".join(
            f"- `{p}`" for p in missing
        )

    gates = [
        ("env selftest", ["bash", "env/tests/selftest"]),
        ("web selftest", ["bash", "web/tests/selftest.sh"]),
        (
            "node syntax check",
            [
                "node",
                "--check",
                "web/generated/main.js",
                "plugins/swarm/sidecars/swarm_board/hive.js",
                "plugins/swarm/sidecars/swarm_board/app.js",
            ],
        ),
    ]

    for label, cmd in gates:
        res = run(cmd, check=False)
        if res.returncode != 0:
            combined = (res.stdout + "\n" + res.stderr).strip()
            tail = "\n".join(combined.splitlines()[-80:])
            return (
                False,
                f"Gate **{label}** (`{' '.join(cmd)}`) failed with exit code `{res.returncode}`.\n\n```\n{tail}\n```",
            )

    return True, ""


def bump_version_files(
    new_version: str,
    new_code: int,
    upstream_label: str,
    upstream_commits: list[str],
    merged_upstream: bool,
) -> None:
    # 1. config/app.env
    env_path = REPO_ROOT / "config/app.env"
    env_text = env_path.read_text(encoding="utf-8")
    env_text = re.sub(
        r"^APP_VERSION_NAME=.*$",
        f"APP_VERSION_NAME={new_version}",
        env_text,
        flags=re.MULTILINE,
    )
    env_text = re.sub(
        r"^APP_VERSION_CODE=.*$",
        f"APP_VERSION_CODE={new_code}",
        env_text,
        flags=re.MULTILINE,
    )
    env_path.write_text(env_text, encoding="utf-8")

    # 2. app/src/main/AndroidManifest.xml
    manifest_path = REPO_ROOT / "app/src/main/AndroidManifest.xml"
    manifest_text = manifest_path.read_text(encoding="utf-8")
    manifest_text = re.sub(
        r'android:versionCode="\d+"',
        f'android:versionCode="{new_code}"',
        manifest_text,
        count=1,
    )
    manifest_text = re.sub(
        r'android:versionName="[^"]+"',
        f'android:versionName="{new_version}"',
        manifest_text,
        count=1,
    )
    manifest_path.write_text(manifest_text, encoding="utf-8")

    # 3. README.md
    readme_path = REPO_ROOT / "README.md"
    readme_text = readme_path.read_text(encoding="utf-8")
    readme_text = re.sub(
        r"Version-\d+\.\d+\.\d+-blueviolet",
        f"Version-{new_version}-blueviolet",
        readme_text,
    )
    readme_path.write_text(readme_text, encoding="utf-8")

    # 4. RELEASE_NOTES.md
    notes_path = REPO_ROOT / "RELEASE_NOTES.md"
    notes_text = notes_path.read_text(encoding="utf-8")
    if merged_upstream:
        bullets = "\n".join(f"* `{line}`" for line in upstream_commits[:10])
        summary_block = (
            f"### 1. Automated Upstream Sync ({upstream_label})\n"
            f"* Merged latest upstream changes from `salmanbappi/antigravity-android` while preserving all fork features and passing all verification gates.\n"
            + (f"{bullets}\n" if bullets else "")
        )
    else:
        summary_block = (
            "### 1. Scheduled / Manual Release Rebuild\n"
            "* Triggered via `sync-upstream.yml` with `force_release=true` after passing all verification gates.\n"
        )

    new_section = (
        f"# Antigravity for Android v{new_version}\n\n"
        "## Release Packages\n\n"
        "| Package | Application ID | Runtime Port | Description |\n"
        "| :--- | :--- | :--- | :--- |\n"
        "| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |\n"
        "| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |\n"
        "| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |\n\n"
        f"## Changes & Fixes (v{new_version})\n\n"
        f"{summary_block}\n"
        "---\n\n"
    )
    notes_path.write_text(new_section + notes_text, encoding="utf-8")

    # 5. .github/workflows/build-and-release.yml
    wf_path = REPO_ROOT / ".github/workflows/build-and-release.yml"
    wf_text = wf_path.read_text(encoding="utf-8")
    wf_text = re.sub(
        r"description: 'Release Tag \(e\.g\. v\d+\.\d+\.\d+\)'",
        f"description: 'Release Tag (e.g. v{new_version})'",
        wf_text,
    )
    wf_text = re.sub(
        r"default: 'v\d+\.\d+\.\d+'",
        f"default: 'v{new_version}'",
        wf_text,
        count=1,
    )
    wf_text = re.sub(
        r"### What's New in v\d+\.\d+\.\d+",
        f"### What's New in v{new_version}",
        wf_text,
        count=1,
    )
    wf_text = re.sub(
        r"APP_VERSION_CODE=\d+",
        f"APP_VERSION_CODE={new_code}",
        wf_text,
    )
    wf_path.write_text(wf_text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Auto-sync upstream and release.")
    parser.add_argument("--upstream-url", default=DEFAULT_UPSTREAM_URL)
    parser.add_argument(
        "--force-release",
        default="false",
        help="Whether to force a release bump even if upstream has no new commits (true/false).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Run checks without pushing, tagging, creating issues, or dispatching workflow.",
    )
    args = parser.parse_args()
    force_release = str(args.force_release).strip().lower() in ("1", "true", "yes")

    ensure_upstream_remote(args.upstream_url)

    upstream_sha = run(["git", "rev-parse", "--short", "upstream/main"]).stdout.strip()
    ahead_count = int(
        run(["git", "rev-list", "--count", "HEAD..upstream/main"]).stdout.strip()
    )
    upstream_tag, upstream_ver = get_latest_remote_v_tag("upstream")
    upstream_label = f"{upstream_tag} ({upstream_sha})" if upstream_tag else upstream_sha

    print(
        f"[sync] upstream/main={upstream_sha}, latest_upstream_tag={upstream_tag}, "
        f"ahead_commits={ahead_count}, force_release={force_release}",
        flush=True,
    )

    if ahead_count == 0 and not force_release:
        print("[sync] No new upstream commits and force_release=false. Nothing to do.", flush=True)
        return 0

    upstream_commits: list[str] = []
    merged_upstream = False

    if ahead_count > 0:
        log_res = run(
            ["git", "log", "--oneline", "-n", "10", "HEAD..upstream/main"],
            check=False,
        )
        if log_res.returncode == 0:
            upstream_commits = [
                line.strip() for line in log_res.stdout.splitlines() if line.strip()
            ]

        merge_res = run(
            ["git", "merge", "--no-commit", "--no-ff", "upstream/main"],
            check=False,
        )
        if merge_res.returncode != 0:
            unmerged_res = run(
                ["git", "diff", "--name-only", "--diff-filter=U"],
                check=False,
            )
            unmerged = [
                p.strip() for p in unmerged_res.stdout.splitlines() if p.strip()
            ]
            allowed = [p for p in unmerged if is_allowed_conflict(p)]
            disallowed = [p for p in unmerged if not is_allowed_conflict(p)]

            if disallowed or not unmerged:
                abort_merge_and_clean()
                conflict_list = "\n".join(f"- `{p}`" for p in disallowed) or "- _(unknown merge error)_"
                body = (
                    f"Automated upstream sync against **`{upstream_label}`** encountered merge conflicts "
                    "in non-metadata code files and was cleanly aborted.\n\n"
                    "### Conflicting Files\n"
                    f"{conflict_list}\n\n"
                    "### Next Steps\n"
                    "1. Fetch `upstream/main` locally and run `git merge --no-ff upstream/main`.\n"
                    "2. Resolve the conflicting code files while preserving fork features.\n"
                    "3. Rebuild `web/generated/**` (`bash web/build.sh && cp -f web/main.js web/generated/main.js && python3 web/patch_main.py web/generated/main.js`).\n"
                    "4. Run `bash env/tests/selftest` and `bash web/tests/selftest.sh`, then push to `main`.\n"
                )
                upsert_github_issue(
                    f"[Auto-Sync] Manual merge needed for upstream {upstream_label}",
                    body,
                    dry_run=args.dry_run,
                )
                print("[sync] Merge aborted due to real code conflicts. Exiting cleanly.", flush=True)
                return 0

            print(
                f"[sync] Auto-resolving allowed metadata/generated conflicts: {allowed}",
                flush=True,
            )
            resolve_allowed_conflicts(allowed)

        merged_upstream = True

        ok_web, web_err = rebuild_web_generated()
        if not ok_web:
            abort_merge_and_clean()
            upsert_github_issue(
                f"[Auto-Sync] Manual merge needed for upstream {upstream_label}",
                (
                    f"Automated upstream sync against **`{upstream_label}`** merged cleanly, "
                    "but regenerating `web/generated/**` failed and the merge was aborted.\n\n"
                    f"### Error Details\n```\n{web_err[-3000:]}\n```\n"
                ),
                dry_run=args.dry_run,
            )
            print("[sync] Web regeneration failed; merge aborted. Exiting cleanly.", flush=True)
            return 0

    ok_gates, gate_err = run_verification_gates()
    if not ok_gates:
        abort_merge_and_clean()
        upsert_github_issue(
            f"[Auto-Sync] Verification gate failed for upstream {upstream_label}",
            (
                f"Automated upstream sync against **`{upstream_label}`** failed safety verification "
                "gates and was cleanly aborted (nothing was committed or pushed).\n\n"
                f"### Failure Details\n{gate_err}\n"
            ),
            dry_run=args.dry_run,
        )
        print("[sync] Verification gate failed; merge aborted. Exiting cleanly.", flush=True)
        return 0

    # Compute next version strictly greater than current APP_VERSION_NAME,
    # latest upstream v* tag, and latest local v* tag.
    env_values = read_app_env_values((REPO_ROOT / "config/app.env").read_text(encoding="utf-8"))
    current_ver = parse_semver(env_values.get("APP_VERSION_NAME", "0.0.0")) or (0, 0, 0)
    current_code = int(env_values.get("APP_VERSION_CODE", "0") or "0")
    _, local_tag_ver = get_latest_local_v_tag()

    base_ver = max(current_ver, upstream_ver, local_tag_ver)
    new_ver = (base_ver[0], base_ver[1], base_ver[2] + 1)
    new_ver_str = format_semver(new_ver)
    new_code = compute_version_code(new_ver, current_code)
    new_tag = f"v{new_ver_str}"

    print(
        f"[sync] Bumping version: current={format_semver(current_ver)} ({current_code}), "
        f"upstream={format_semver(upstream_ver)}, next={new_ver_str} ({new_code})",
        flush=True,
    )

    bump_version_files(
        new_version=new_ver_str,
        new_code=new_code,
        upstream_label=upstream_label,
        upstream_commits=upstream_commits,
        merged_upstream=merged_upstream,
    )

    if args.dry_run:
        print(f"[dry-run] Would commit, tag {new_tag}, push, and dispatch build-and-release.yml.", flush=True)
        abort_merge_and_clean()
        return 0

    run(["git", "add", "-A"])
    commit_msg = (
        f"chore(sync): merge upstream {upstream_label} and bump to {new_tag}"
        if merged_upstream
        else f"chore(release): force release bump to {new_tag}"
    )
    run(["git", "commit", "-m", commit_msg])
    run(["git", "tag", "-a", new_tag, "-m", f"Release {new_tag}"])
    run(["git", "push", "origin", "main"])
    run(["git", "push", "origin", new_tag])

    # Explicit workflow_dispatch is required because GITHUB_TOKEN pushes do not
    # trigger other on:push workflows in GitHub Actions.
    run(
        [
            "gh",
            "workflow",
            "run",
            "build-and-release.yml",
            "--ref",
            "main",
            "-f",
            f"tag_name={new_tag}",
            "-f",
            "draft=false",
            "-f",
            "prerelease=false",
        ]
    )
    print(f"[sync] Successfully synced, pushed {new_tag}, and triggered build-and-release.yml.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
