# routes/music.py stub for Render
import re
from fastapi import APIRouter
from fastapi.responses import JSONResponse

router = APIRouter()

MUSIC_CMD_PATTERN = re.compile(r'\[MUSIC:([^\]]+)\]')

@router.get("/api/music/search")
async def search(keyword: str, limit: int = 10):
    return JSONResponse({"error": "music service unavailable on this deployment"})

@router.get("/api/music/song/{song_id}")
async def song_detail(song_id: str):
    return JSONResponse({"error": "music service unavailable"})

@router.get("/api/music/url/{song_id}")
async def audio_url(song_id: str):
    return JSONResponse({"error": "music service unavailable"})
