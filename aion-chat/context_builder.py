"""Context builder stubs - completed for Render"""
import json
from typing import Any, Optional

# === Real implementations from original file ===
def format_ability_block(abilities: list) -> str:
    if not abilities:
        return ""
    return ", ".join(str(a) for a in abilities)

def strip_tool_commands(text: str) -> str:
    if not text:
        return ""
    import re
    text = re.sub(r'\[.*?\]', '', text)
    return text.strip()

def format_message_time(created_at) -> str:
    if created_at:
        return str(created_at)
    return ""

def append_message_meta(content: str, created_at, label: str = "") -> str:
    return content

def render_merged_timeline(messages: list, **k) -> str:
    if not messages:
        return ""
    lines = []
    for msg in messages[-20:]:
        role = msg.get("role", "user")
        content = msg.get("content", "")
        if content:
            lines.append(f"{role}: {content[:200]}")
    return "\n".join(lines)

# === Missing stubs added below ===
def build_ability_block(*a, **k) -> str:
    return ""

def build_memory_blocks(*a, **k) -> list:
    return []

def fetch_merged_timeline(*a, **k) -> list:
    return []

def build_health_summary(*a, **k) -> str:
    return ""

def _build_recall_query(*a, **k) -> str:
    return ""

WISH_CMD_PATTERN = None
MUSIC_CMD_PATTERN = None
SONG_CMD_PATTERN = None
NEWS_CMD_PATTERN = None
WEATHER_CMD_PATTERN = None
SEARCH_CMD_PATTERN = None
LUCK_CMD_PATTERN = None
RECIPE_CMD_PATTERN = None
TRANSLATE_CMD_PATTERN = None
STOCK_CMD_PATTERN = None
WEEKLY_CMD_PATTERN = None
MOMENT_CMD_PATTERN = None
MEMORY_CMD_PATTERN = None
DRAW_CMD_PATTERN = None\n\n# === Missing items added for Render ===\nWISH_CMD_PATTERN = None\n\ndef _build_recall_query(*a, **k) -> str:\n    return ""\n
