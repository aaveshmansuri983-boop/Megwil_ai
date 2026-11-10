"""Minimal sounddevice compatibility shim backed by native Android AudioRecord.

Project-JARVIS Vosk input modules already consume sounddevice.InputStream callbacks.
This shim preserves that interface without PortAudio or desktop audio libraries.
"""
from __future__ import annotations
from types import SimpleNamespace
from android_audio import subscribe, unsubscribe


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
        if not self.started:
            subscribe(self.callback)
            self.started = True

    def stop(self):
        if self.started:
            unsubscribe(self.callback)
            self.started = False

    def close(self):
        self.stop()
