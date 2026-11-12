"""Validate public Android OAuth configuration without handling client secrets."""
from __future__ import annotations
import os
import re

CLIENT_ID = re.compile(r"^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$")


def check() -> dict[str, str | bool]:
    web = os.environ.get("JARVIS_GOOGLE_WEB_CLIENT_ID", "").strip()
    android = os.environ.get("JARVIS_GOOGLE_ANDROID_CLIENT_ID", "").strip()
    package = os.environ.get("JARVIS_ANDROID_APPLICATION_ID", "").strip()
    sha1 = os.environ.get("JARVIS_ANDROID_STABLE_SIGNING_SHA1", "").replace(":", "").strip().upper()
    return {
        "web_client_id_present": bool(CLIENT_ID.fullmatch(web)),
        "android_client_id_present": bool(CLIENT_ID.fullmatch(android)),
        "package_name_present": bool(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package)),
        "stable_signing_sha1_present": bool(re.fullmatch(r"[0-9A-F]{40}", sha1)),
        "client_secret_required_in_apk": False,
        "ready": bool(CLIENT_ID.fullmatch(web) and CLIENT_ID.fullmatch(android)
                      and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package)
                      and re.fullmatch(r"[0-9A-F]{40}", sha1)),
    }


if __name__ == "__main__":
    import json
    print(json.dumps(check(), indent=2))
