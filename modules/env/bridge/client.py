"""
client.py - Lightweight IPC client sending command envelopes to the Android System Gateway.
"""
import json
import os
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional


def _gateway_file_candidates(filename: str) -> List[str]:
    candidates: List[str] = []
    home = os.environ.get("HOME")
    if home:
        candidates.append(os.path.join(home, "logs", filename))

    try:
        from ..context import path_resolver
        app_root = path_resolver.load_paths().get("app_root")
        if app_root:
            candidates.append(os.path.join(app_root, "logs", filename))
    except Exception:
        pass

    candidates.extend([
        os.path.join("/data/user/0/com.agy.dev/files/logs", filename),
        os.path.join("/data/user/0/com.agy/files/logs", filename),
    ])
    return candidates


def resolve_gateway_url() -> str:
    if "AGY_GATEWAY_URL" in os.environ:
        return os.environ["AGY_GATEWAY_URL"]

    for pfile in _gateway_file_candidates("gateway.port"):
        if os.path.isfile(pfile):
            try:
                with open(pfile, "r", encoding="utf-8") as f:
                    p = f.read().strip()
                    if p.isdigit():
                        return f"http://127.0.0.1:{p}/gateway"
            except Exception:
                pass

    is_dev = (
        os.environ.get("DEV_MODE") == "1"
        or os.environ.get("AGY_APPLICATION_ID") == "com.agy.dev"
        or "com.agy.dev" in (os.environ.get("HOME") or "")
    )
    return "http://127.0.0.1:45161/gateway" if is_dev else "http://127.0.0.1:45160/gateway"


def resolve_gateway_token(gateway_token: Optional[str] = None) -> str:
    if gateway_token:
        return gateway_token.strip()
    env_tok = os.environ.get("AGY_GATEWAY_TOKEN", "").strip()
    if env_tok:
        return env_tok
    for tfile in _gateway_file_candidates("gateway.token"):
        if os.path.isfile(tfile):
            try:
                with open(tfile, "r", encoding="utf-8") as f:
                    tok = f.read().strip()
                    if tok:
                        return tok
            except Exception:
                pass
    return ""


def gateway_headers(gateway_token: Optional[str] = None, content_type: Optional[str] = None) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    if content_type:
        headers["Content-Type"] = content_type
    token = resolve_gateway_token(gateway_token)
    if token:
        headers["X-AGY-Token"] = token
        headers["Authorization"] = f"Bearer {token}"
    return headers


DEFAULT_GATEWAY_URL = resolve_gateway_url()


def send_command(
    command: str,
    payload: Optional[Dict[str, Any]] = None,
    gateway_url: Optional[str] = None,
    gateway_token: Optional[str] = None,
) -> Dict[str, Any]:
    url = gateway_url or resolve_gateway_url()
    packet = {
        "command": command,
        "payload": payload or {},
    }
    data = json.dumps(packet).encode("utf-8")
    headers = gateway_headers(gateway_token=gateway_token, content_type="application/json")
    req = urllib.request.Request(url, data=data, headers=headers)

    try:
        with urllib.request.urlopen(req, timeout=1.0) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, OSError) as e:
        # If default url was used, try the alternate port (45160 <-> 45161)
        if gateway_url is None:
            alt_url = "http://127.0.0.1:45160/gateway" if ":45161/" in url else "http://127.0.0.1:45161/gateway"
            try:
                alt_req = urllib.request.Request(alt_url, data=data, headers=headers)
                with urllib.request.urlopen(alt_req, timeout=1.0) as resp:
                    return json.loads(resp.read().decode("utf-8"))
            except Exception:
                pass
        return {"ok": False, "fallback": True, "error": str(e), "packet": packet}
