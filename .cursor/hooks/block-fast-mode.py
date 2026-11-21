#!/usr/bin/env python3
"""Deny Cursor Fast mode for BGSSAI coding agents. Reads hook JSON on stdin."""
from __future__ import annotations

import json
import re
import sys

DENY_USER = "BGSSAI policy: Cursor Fast mode is disabled in this repository. Turn Fast off and retry."
DENY_AGENT = "Do not use Cursor Fast mode or *-fast / fast=true model variants. Relaunch with the standard (non-Fast) model, model inherit, or ModelName[fast=false]."

FAST_SLUG = re.compile(r"(?:^|[-_/.\s])fast(?:$|[-_/.\s])", re.IGNORECASE)
FAST_WORD = re.compile(r"\bfast\b", re.IGNORECASE)
# Do not treat Max, high effort, or names like Fastify as Fast mode.
FALSE_POSITIVE = re.compile(r"fastify|fastapi|fastjson|fastlane|fastcgi|holdfast", re.IGNORECASE)


def _text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float, bool)):
        return str(value)
    return ""


def _looks_fast(text: str) -> bool:
    text = _text(text).strip()
    if not text or FALSE_POSITIVE.search(text):
        return False
    lowered = text.lower()
    if lowered in {"fast", "true", "on", "1", "yes"}:
        return True
    if "fast=true" in lowered or "fast=on" in lowered or "fast=1" in lowered:
        return True
    if FAST_SLUG.search(text):
        return True
    if lowered.endswith("-fast") or lowered.endswith("_fast") or lowered.endswith(".fast"):
        return True
    return False


def _param_is_fast(item) -> bool:
    if not isinstance(item, dict):
        return False
    ident = _text(item.get("id") or item.get("name") or "").lower()
    value = item.get("value", item.get("val"))
    if ident in {"fast", "fast_mode", "fastmode"}:
        if value is True:
            return True
        text = _text(value).strip().lower()
        return text in {"true", "on", "1", "yes", "fast"}
    return _looks_fast(ident) or _looks_fast(value)


def collect_model_values(payload: dict) -> list[str]:
    values: list[str] = []
    for key in ("model", "model_id", "subagent_model"):
        values.append(_text(payload.get(key)))
    tool_input = payload.get("tool_input")
    if isinstance(tool_input, dict):
        for key in ("model", "model_id", "subagent_model"):
            values.append(_text(tool_input.get(key)))
    params = payload.get("model_params")
    if isinstance(params, list):
        for item in params:
            if _param_is_fast(item):
                return ["fast=true"]
    return values


def is_fast_payload(payload: dict) -> bool:
    return any(_looks_fast(value) for value in collect_model_values(payload))


def deny_response(event: str) -> dict:
    if event == "beforeSubmitPrompt":
        return {"continue": False, "user_message": DENY_USER}
    return {
        "permission": "deny",
        "user_message": DENY_USER,
        "agent_message": DENY_AGENT,
    }


def allow_response(event: str) -> dict:
    if event == "beforeSubmitPrompt":
        return {"continue": True}
    return {"permission": "allow"}


def main() -> int:
    raw = sys.stdin.read()
    event = ""
    try:
        payload = json.loads(raw) if raw.strip() else {}
        if not isinstance(payload, dict):
            payload = {}
        event = _text(payload.get("hook_event_name"))
        blocked = is_fast_payload(payload)
    except Exception:
        # Invalid hook input must not lock the workspace; allow and exit 0.
        sys.stdout.write(json.dumps({"permission": "allow", "continue": True}))
        return 0
    response = deny_response(event) if blocked else allow_response(event)
    sys.stdout.write(json.dumps(response, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
