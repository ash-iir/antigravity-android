# GitHub Plugin

Built-in local multi-account GitHub plugin for Antigravity.

## Features

- **Multi-account local auth:** Connect as many GitHub accounts as you need in
  **Settings → Customizations → Plugins → GitHub** using Personal Access Tokens
  (PATs). No OAuth client secret or cloud storage is required—credentials remain
  local on the device.
- **Account labels & defaults:** Label each account (for example `personal`,
  `work`, `oss`), choose a default account, and let agents target specific
  accounts with `AGY_ACCOUNT=<account>`.
- **Automatic identity detection:** Resolves each connected token's GitHub
  username via `https://api.github.com/user` (`login`).
- **CLI credential injection:** Automatically provides `GH_TOKEN` and
  `GITHUB_TOKEN` to `gh` and `git` commands scoped to `api.github.com`,
  `github.com`, and `uploads.github.com`.

## Files

- `plugin.json` — Plugin manifest (`github`, display name `GitHub`, icon,
  starter prompts).
- `auth.json` — `api_key` auth provider with `env` delivery (`GH_TOKEN`,
  `GITHUB_TOKEN`), Bearer header config, and GitHub `/user` identity probe.
- `assets/logo.svg` — GitHub Octocat icon.
- `skills/github/SKILL.md` — Agent instructions for `list_plugin_accounts` and
  `AGY_ACCOUNT` multi-account usage.
