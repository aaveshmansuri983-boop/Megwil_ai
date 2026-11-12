"""Conservative phrase-to-native Android action adapter."""
from __future__ import annotations
import json
import re
from typing import Any


def parse_voice_command(text: str) -> dict[str, str] | None:
    value = " ".join(str(text or "").strip().split())
    if not value:
        return None
    match = re.fullmatch(r"(?:please\s+)?open\s+(whatsapp(?:\s+business)?|chrome|youtube|instagram|camera|settings)", value, re.IGNORECASE)
    if match:
        return {"action": "open_app", "target": match.group(1).lower()}
    match = re.fullmatch(r"(?:please\s+)?(?:call|dial)\s+([+0-9][0-9\s().-]{5,})", value, re.IGNORECASE)
    if match:
        number = re.sub(r"[^+0-9]", "", match.group(1))
        if len(number.lstrip("+")) >= 6:
            return {"action": "dial", "target": number}
    match = re.fullmatch(r"(?:please\s+)?(?:send|compose)\s+(?:a\s+)?whatsapp\s+message\s+to\s+([+0-9][0-9\s().-]{5,})(?:\s+(?:saying|that says)\s+(.+))?", value, re.IGNORECASE)
    if match:
        number = re.sub(r"[^+0-9]", "", match.group(1))
        return {"action": "whatsapp", "target": number, "value": match.group(2) or ""}
    match = re.fullmatch(r"(?:please\s+)?(?:send|compose)\s+(?:a\s+)?(?:text|sms|message)\s+to\s+([+0-9][0-9\s().-]{5,})(?:\s+(?:saying|that says)\s+(.+))?", value, re.IGNORECASE)
    if match:
        number = re.sub(r"[^+0-9]", "", match.group(1))
        return {"action": "sms", "target": number, "value": match.group(2) or ""}
    match = re.fullmatch(r"(?:please\s+)?(?:turn|switch)\s+(on|off)\s+(?:the\s+)?(?:flashlight|torch)", value, re.IGNORECASE)
    if match:
        return {"action": "flashlight", "value": match.group(1).lower()}
    match = re.fullmatch(r"(?:please\s+)?(?:set|change)\s+(?:the\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?", value, re.IGNORECASE)
    if match:
        percent = int(match.group(1))
        if 0 <= percent <= 100:
            return {"action": "volume", "value": str(percent)}
    match = re.fullmatch(r"(?:please\s+)?open\s+(wifi|wi-fi|bluetooth|location)\s+settings", value, re.IGNORECASE)
    if match:
        return {"action": "settings", "target": match.group(1).lower().replace("wi-fi", "wifi")}
    return None


def execute_voice_command(text: str) -> dict[str, Any] | None:
    command = parse_voice_command(text)
    if command is None:
        return None
    try:
        from java import jclass
        router = jclass("com.jarvis.android.host.AndroidActionRouter")
        raw = router.execute(command["action"], command.get("target"), command.get("value"))
        result = json.loads(str(raw))
        return result if isinstance(result, dict) else {"ok": False, "message": "Invalid Android action result"}
    except Exception as error:
        return {"ok": False, "message": "Android action bridge failed: " + str(error)}
