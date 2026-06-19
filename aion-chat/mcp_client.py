"""MCP stub for Render - MCP not available on cloud servers"""
import logging
logger = logging.getLogger("mcp_client")

class ClientSession:
    def __init__(self, *a, **k): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *a): pass
    async def initialize(self, *a, **k): return {}
    async def tools(self): return []

async def streamablehttp_client(*a, **k): yield None
async def stdio_client(*a, **k): yield None
async def sse_client(*a, **k): yield None
class StdioServerParameters: pass
