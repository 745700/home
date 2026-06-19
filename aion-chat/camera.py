# Camera stub for Render - no camera hardware on cloud servers
def cam(): pass
class _CamStub:
    def __getattr__(self, *a): return lambda * **k: None
cam = _CamStub()
def detect_cameras(): return []
def read_monitor_logs(*a, **k): return []

