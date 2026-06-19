# Voice stub for Render - no audio hardware on cloud servers
import sys
class _VoiceStub:
    def __getattr__(self, *a): return lambda * **k: None
voice = _VoiceStub()

