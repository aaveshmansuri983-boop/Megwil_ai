"""Project-JARVIS STTProvider implemented with Android's native SpeechRecognizer."""
from __future__ import annotations
from queue import Empty, Queue
from typing import Generator, Optional, Tuple

from java import jclass
from jarvis.voice.base import STTProvider
from android_audio import subscribe_text, unsubscribe_text


class AndroidSTT(STTProvider):
    def __init__(self, **kwargs):
        self._queue: Queue = Queue()
        self._running = False
        self._router = jclass("com.jarvis.android.host.AndroidSpeechRouter")

    def _on_text(self, text: str, is_final: bool) -> None:
        if self._running and (text or is_final):
            if is_final and str(text).strip():
                try:
                    from android_actions import execute_voice_command
                    execute_voice_command(str(text))
                except Exception:
                    # Native action failures must not prevent normal JARVIS voice dispatch.
                    pass
            self._queue.put((str(text), bool(is_final)))

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        subscribe_text(self._on_text)
        # Do not fail the entire local JARVIS runtime when microphone permission
        # or Android's speech service is unavailable. Text commands and the local
        # LLM remain usable; the user can enable voice later from Runtime Settings.
        if self._router.isAvailable():
            self._router.listenOnce()

    def stop(self) -> None:
        self._running = False
        unsubscribe_text(self._on_text)
        self._router.stop()
        self._queue.put(None)

    def iter_results(self) -> Generator[Tuple[str, bool], None, None]:
        while self._running:
            item = self.read(timeout=1.0)
            if item is not None and item[0]:
                yield item

    def read(self, timeout: Optional[float] = None) -> Optional[Tuple[str, bool]]:
        if not self._running:
            return None
        try:
            item = self._queue.get(timeout=timeout)
        except Empty:
            return None
        if item is None:
            return None
        if item[1] and self._running:
            self._router.listenOnce()
        return item if item[0] else None

    def is_running(self) -> bool:
        return self._running
