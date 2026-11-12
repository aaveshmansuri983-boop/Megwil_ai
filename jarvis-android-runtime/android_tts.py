"""Chaquopy adapter implementing Project-JARVIS TTSProvider via Android TextToSpeech."""
from __future__ import annotations

from java import jclass
from jarvis.voice.base import TTSProvider


class AndroidTTS(TTSProvider):
    def __init__(self, **kwargs):
        self._router = jclass("com.jarvis.android.host.AndroidTtsRouter")

    def say(self, text: str) -> None:
        if text and text.strip():
            self._router.speak(text)

    def stop(self) -> None:
        self._router.stop()
