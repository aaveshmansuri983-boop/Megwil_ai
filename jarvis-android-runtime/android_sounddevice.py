"""Minimal sounddevice compatibility shim backed by native Android AudioRecord.

Project-JARVIS input streams retain the sounddevice callback interface. The
first active stream starts Android AudioRecord; the final close stops it.
"""
from __future__ import annotations
import threading
from types import SimpleNamespace

from android_audio import subscribe, unsubscribe

try:
    from java import jclass
    _audio_router = jclass("com.jarvis.android.host.AndroidAudioRouter")
except Exception:
    _audio_router = None

_stream_lock = threading.RLock()
_stream_count = 0

default = SimpleNamespace(device=[0, None])


def query_devices():
    return [{
        "name": "Android AudioRecord",
        "max_input_channels": 1,
        "max_output_channels": 0,
        "default_samplerate": 16000,
    }]


class InputStream:
    def __init__(self, *, callback, samplerate=16000, channels=1, dtype="int16",
                 blocksize=0, device=None, **kwargs):
        if samplerate != 16000 or channels != 1 or dtype != "int16":
            raise ValueError("Android native audio supports 16 kHz mono PCM16 only")
        self.callback = callback
        self.started = False

    def start(self):
        global _stream_count
        if self.started:
            return
        if _audio_router is None:
            raise RuntimeError("Android AudioRecord host bridge is unavailable")
        with _stream_lock:
            subscribe(self.callback)
            if _stream_count == 0 and not _audio_router.startRecording():
                unsubscribe(self.callback)
                raise RuntimeError("Android AudioRecord could not start; check microphone permission")
            _stream_count += 1
            self.started = True

    def stop(self):
        global _stream_count
        if not self.started:
            return
        with _stream_lock:
            unsubscribe(self.callback)
            _stream_count = max(0, _stream_count - 1)
            if _stream_count == 0 and _audio_router is not None:
                _audio_router.stopRecording()
            self.started = False

    def close(self):
        self.stop()
