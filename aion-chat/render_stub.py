"""Render startup patch - adds missing stubs before app loads"""
import sys
from types import ModuleType
import re

# Create context_builder stub module
class _CtxBuilderStub(ModuleType):
    pass

ctx = _CtxBuilderStub('context_builder')

# Add command patterns as compiled regex (they're used with .findall / .sub)
ctx.WISH_CMD_PATTERN = re.compile(r'\[许愿[：:]\s*([^\]]+)\]')
ctx.LUCK_CMD_PATTERN = re.compile(r'\[今日运势[：:]([^\]]*)\]')
ctx.RECIPE_CMD_PATTERN = re.compile(r'\[食谱[：:]\s*([^\]]+)\]')
ctx.TRANSLATE_CMD_PATTERN = re.compile(r'\[翻译[：:]\s*([^\]]+)\]')
ctx.STOCK_CMD_PATTERN = re.compile(r'\[股票[：:]\s*([^\]]+)\]')
ctx.WEEKLY_CMD_PATTERN = re.compile(r'\[周记[：:]\s*([^\]]+)\]')
ctx.NEWS_CMD_PATTERN = re.compile(r'\[新闻[：:]\s*([^\]]+)\]')
ctx.WEATHER_CMD_PATTERN = re.compile(r'\[天气[：:]\s*([^\]]+)\]')
ctx.SEARCH_CMD_PATTERN = re.compile(r'\[搜索[：:]\s*([^\]]+)\]')
ctx.SONG_CMD_PATTERN = re.compile(r'\[点歌[：:]\s*([^\]]+)\]')
ctx.MUSIC_CMD_PATTERN = re.compile(r'\[MUSIC:([^\]]+)\]')
ctx.MOMENT_CMD_PATTERN = re.compile(r'\[MOMENT:([^\]]+)\]')
ctx.MEMORY_CMD_PATTERN = re.compile(r'\[MEMORY:([^\]]+)\]')
ctx.DRAW_CMD_PATTERN = re.compile(r'\[绘图[：:]\s*([^\]]+)\]')
ctx.CALC_EXPR = re.compile(r'\[计算[：:]\s*([^\]]+)\]')

# Add missing functions
ctx._build_recall_query = lambda *a, **k: ""

# Add to sys.modules BEFORE any other import
sys.modules['context_builder'] = ctx
