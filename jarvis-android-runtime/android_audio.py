"""Thread-safe bridge from native Android AudioRecord PCM into Project-JARVIS."""
from __future__ import annotations
import threading
from typing import Callable

_lock = threading.RLock()
_callbacks: set[Callable] = set()
_text_callbacks: set[Callable] = set()


class PcmFrame:
    def __init__(self, data: bytes):
        self._data = bytes(data)

    def tobytes(self) -> bytes:
        return self._data


def subscribe(callback: Callable) -> None:
    with _lock:
        _callbacks.add(callback)


def unsubscribe(callback: Callable) -> None:
    with _lock:
        _callbacks.discard(callback)


def feed(pcm16: bytes) -> None:
    """Called by Kotlin with 16 kHz, mono, signed 16-bit PCM frames."""
    frame = PcmFrame(pcm16)
    with _lock:
        listeners = tuple(_callbacks)
    frames = len(pcm16) // 2
    for callback in listeners:
        try:
            callback(frame, frames, None, None)
        except Exception:
            # Audio callback errors must not crash Android's AudioRecord thread.
            continue


def subscribe_text(callback: Callable) -> None:
    with _lock:
        _text_callbacks.add(callback)


def unsubscribe_text(callback: Callable) -> None:
    with _lock:
        _text_callbacks.discard(callback)


def feed_text(text: str, is_final: bool = True) -> None:
    with _lock:
        listeners = tuple(_text_callbacks)
    for callback in listeners:
        try:
            callback(text, is_final)
        except Exception:
            continue
