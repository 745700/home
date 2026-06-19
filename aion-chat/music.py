# music.py stub for Render - pyncm not available on cloud servers
import re
import sys

# Stub pyncm so imports don't crash
class _pyncm:
    class apis:
        class login:
            @staticmethod
            def LoginViaAnonymousAccount(): pass
            @staticmethod
            def LoginViaCookie(*a, **k): pass
        class cloudsearch:
            @staticmethod
            def GetSearchResult(*a, **k): return {}
        class track:
            @staticmethod
            def GetTrackDetail(*a, **k): return {}
            @staticmethod
            def GetTrackAudio(*a, **k): return {}

sys.modules['pyncm'] = _pyncm()
sys.modules['pyncm.apis'] = _pyncm.apis
sys.modules['pyncm.apis.login'] = _pyncm.apis.login
sys.modules['pyncm.apis.cloudsearch'] = _pyncm.apis.cloudsearch
sys.modules['pyncm.apis.track'] = _pyncm.apis.track

import logging
import httpx

log = logging.getLogger(__name__)

# These are imported by routes/chat.py and routes/music.py
MUSIC_CMD_PATTERN = re.compile(r'\[MUSIC:([^\]]+)\]') if False else None
SONG_CMD_PATTERN = None

def search_songs(keyword, limit=10, offset=0, cookie=None):
    log.warning("music search unavailable on Render")
    return []

def get_song_detail(song_id, cookie=None):
    return {}

def get_audio_url(song_id, bitrate=320000, cookie=None):
    return None
