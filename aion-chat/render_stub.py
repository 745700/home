"""Render startup patch - adds missing stubs before app loads"""
import sys
from types import ModuleType

# Create missing context_builder items
class _CtxBuilderStub(ModuleType):
    pass

ctx = _CtxBuilderStub('context_builder')

# Add all command patterns as None (they're checked with `if pattern:`)
for _name in ['WISH_CMD_PATTERN', 'LUCK_CMD_PATTERN', 'RECIPE_CMD_PATTERN',
              'TRANSLATE_CMD_PATTERN', 'STOCK_CMD_PATTERN', 'WEEKLY_CMD_PATTERN',
              'NEWS_CMD_PATTERN', 'WEATHER_CMD_PATTERN', 'SEARCH_CMD_PATTERN',
              'SONG_CMD_PATTERN', 'MUSIC_CMD_PATTERN', 'CALC_EXPR']:
    setattr(ctx, _name, None)

# Add missing functions
ctx.WISH_CMD_PATTERN = None
ctx._build_recall_query = lambda *a, **k: ""

# Add to sys.modules BEFORE any other import
sys.modules['context_builder'] = ctx
