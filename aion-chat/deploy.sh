#!/bin/bash
# AionsHome Termux 一键部署脚本
set -e
BASE="/data/data/com.termux/files/home/AionsHome/aion-chat"

echo "[1/6] 修复 context_builder.py..."
# 追加所有缺失的 stub 函数和常量
cat >> "$BASE/context_builder.py" << 'STUB'

def build_ability_block(*a,**k): return ""
def build_memory_blocks(*a,**k): return []
def fetch_merged_timeline(*a,**k): return []
def render_merged_timeline(*a,**k): return ""
def strip_tool_commands(*a,**k): return ""
def health_summary(*a,**k): return {}
def draw_oracle_card(*a,**k): return None
def read_nightmare(*a,**k): return ""
MUSIC_CMD_PATTERN=None
SONG_CMD_PATTERN=None
NEWS_CMD_PATTERN=None
WEATHER_CMD_PATTERN=None
SEARCH_CMD_PATTERN=None
LUCK_CMD_PATTERN=None
RECIPE_CMD_PATTERN=None
TRANSLATE_CMD_PATTERN=None
STOCK_CMD_PATTERN=None
calc_expr=None
WEEKLY_CMD_PATTERN=None
MOMENT_CMD_PATTERN=None
MEMORY_CMD_PATTERN=None
WISH_CMD_PATTERN=None
DRAW_CMD_PATTERN=None
STUB
echo "  done"

echo "[2/6] 注释掉 main.py 中的问题路由..."
sed -i 's/^from routes import book as book_routes$/# from routes import book as book_routes/' "$BASE/main.py"
sed -i "s/^app.include_router(book_routes.router,/# app.include_router(book_routes.router,/" "$BASE/main.py"
sed -i 's/^from routes import fund as fund_routes$/# from routes import fund as fund_routes/' "$BASE/main.py"
sed -i "s/^app.include_router(fund_routes.router,/# app.include_router(fund_routes.router,/" "$BASE/main.py"
echo "  done"

echo "[3/6] 修复 mcp_client.py..."
sed -i 's/^from mcp import ClientSession$/# from mcp import ClientSession/' "$BASE/mcp_client.py"
sed -i 's/^from mcp.client.streamable_http import/# from mcp.client.streamable_http import/' "$BASE/mcp_client.py"
sed -i 's/^from mcp.client.stdio import/# from mcp.client.stdio import/' "$BASE/mcp_client.py"
sed -i 's/^from mcp.client.sse import/# from mcp.client.sse import/' "$BASE/mcp_client.py"
echo "  done"

echo "[4/6] 修复 chatroom.py..."
sed -i 's/^from context_builder import ($/# from context_builder import (/' "$BASE/chatroom.py"
echo "  done"

echo "[5/6] 修复 moments.py..."
sed -i 's/^from context_builder import/# from context_builder import/' "$BASE/moments.py"
echo "  done"

echo "[6/6] 创建 vendor fake 模块..."
mkdir -p "$BASE/vendor"
echo "# fake ebooklib" > "$BASE/vendor/ebooklib.py"
echo "# fake akshare" > "$BASE/vendor/akshare.py"
echo "class ak: pass" >> "$BASE/vendor/akshare.py"
echo "  done"

echo ""
echo "========================================="
echo "  修复完成！现在进入容器启动后端："
echo ""
echo "  proot-distro login --bind=/data/data/com.termux/files/home:/root/termux_home debian-trixie"
echo "  cd /root/termux_home/AionsHome/aion-chat && python3 main.py"
echo "========================================="
