"""Android SpeechRecognizer wake-word provider for Project-JARVIS."""
from __future__ import annotations
from typing import Callable, List, Optional

from java import jclass
from jarvis.voice.base import ActivationProvider
from android_audio import subscribe_text, unsubscribe_text


class AndroidActivation(ActivationProvider):
    def __init__(self, wake_words: Optional[List[str]] = None, **kwargs):
        configured_words = [wake_words] if isinstance(wake_words, str) else (wake_words or ["jarvis", "hey jarvis"])
        self.wake_words = [str(word).lower() for word in configured_words]
        self.on_wake_word: Optional[Callable[[], None]] = None
        self._router = jclass("com.jarvis.android.host.AndroidSpeechRouter")
        self._running = False

    def _on_text(self, text: str, is_final: bool) -> None:
        if not self._running or not is_final:
            return
        normalized = (text or "").lower().strip()
        match = next((word for word in self.wake_words if word in normalized), None)
        if match:
            self._running = False
            unsubscribe_text(self._on_text)
            self._router.stop()
            callback = self.on_wake_word
            if callback is not None:
                callback()
        else:
            self._router.listenOnce()

    def start_listening(self) -> bool:
        if self._running:
            return True
        if not self._router.isAvailable():
            return False
        self._running = True
        subscribe_text(self._on_text)
        self._router.listenOnce()
        return True

    def stop_listening(self) -> None:
        self._running = False
        unsubscribe_text(self._on_text)
        self._router.stop()

    def is_listening(self) -> bool:
        return self._running

    def cleanup(self) -> None:
        self.stop_listening()
