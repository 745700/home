"""
五感系统 (Somatic System)
让 AI 拥有实时的身体感觉状态，先于开口影响生成。

管线：
  文本输入 → detect(关键词匹配) → ignite(叠加+衰减) → snapshot(输出活跃通道)
  snapshot → 注入 system prompt → 模型生成
  嗅/味超阈值 → Proust 钩子 → 召回相关记忆

设计原则：
  - 纯函数 + 数据内核：时间戳由调用方传入，可独立单测
  - 状态先于语言：身体先在场，模型不必事后现编
  - 词表分层 + 安全降级：缺失只跑骨架不崩
"""

from __future__ import annotations
import math
import re
import time as _time
from dataclasses import dataclass, field, asdict
from typing import Optional


# ─────────────────────────────────────────────────────────
# 配置常量
# ─────────────────────────────────────────────────────────

TWIN_SOMATIC_ENABLED = True          # 总闸，可通过 settings 覆盖
TWIN_SOMATIC_ACTIVE_THRESHOLD = 0.15  # 活跃阈值，低于此不显示/不注入
TWIN_SOMATIC_PROUST_THRESHOLD = 0.35  # 普鲁斯特钩子触发阈值

# 衰减常数（秒）：感觉在身上停留多久后衰减到 1/e ≈ 36.8%
TAU = {
    "touch":  600,   # 体温散得快
    "smell":  1200,  # 最持久，普鲁斯特通道
    "taste":  900,   # 居中
    "sound":  450,   # 最易散
}

CHANNELS = list(TAU.keys())  # ["touch", "smell", "taste", "sound"]


# ─────────────────────────────────────────────────────────
# 词表（私密词表隔离，可按需扩展）
# 格式：(正则关键词, 默认强度)
# ─────────────────────────────────────────────────────────

# 触觉词表
TOUCH_ACTIONS = {
    "捏":    {"surface": "指尖", "rhythm": "短促"},
    "揉":    {"surface": "掌心", "rhythm": "持续"},
    "抱":    {"surface": "双臂", "rhythm": "环绕"},
    "搂":    {"surface": "双臂", "rhythm": "环绕"},
    "蹭":    {"surface": "身体", "rhythm": "摩挲"},
    "碰":    {"surface": "指尖", "rhythm": "轻触"},
    "摸":    {"surface": "掌心", "rhythm": "持续"},
    "亲":    {"surface": "唇",   "rhythm": "短促"},
    "吻":    {"surface": "唇",   "rhythm": "绵长"},
    "咬":    {"surface": "齿尖", "rhythm": "短促"},
    "舔":    {"surface": "舌尖", "rhythm": "绵长"},
    "拍":    {"surface": "掌心", "rhythm": "短促"},
    "抚摸":  {"surface": "掌心", "rhythm": "持续"},
    "揉了揉": {"surface": "掌心", "rhythm": "持续"},
    "揉了揉": {"surface": "掌心", "rhythm": "持续"},
    "揉了揉": {"surface": "掌心", "rhythm": "持续"},
    "紧紧抱着": {"surface": "双臂", "rhythm": "强力环绕"},
    "紧紧抱住": {"surface": "双臂", "rhythm": "强力环绕"},
}

# 身体部位词表
BODY_PARTS = {
    "脸":    {"sensitivity": 0.6, "temp": "温", "texture": "有肉感"},
    "脸颊":  {"sensitivity": 0.6, "temp": "温", "texture": "柔软"},
    "脸蛋":  {"sensitivity": 0.7, "temp": "温", "texture": "软嫩"},
    "耳":    {"sensitivity": 0.8, "temp": "温", "texture": "薄皮"},
    "耳朵":  {"sensitivity": 0.8, "temp": "温", "texture": "薄皮"},
    "耳后":  {"sensitivity": 0.9, "temp": "温", "texture": "敏感薄皮"},
    "唇":    {"sensitivity": 0.9, "temp": "温", "texture": "柔软"},
    "嘴唇":  {"sensitivity": 0.9, "temp": "温", "texture": "柔软"},
    "脖子":  {"sensitivity": 0.7, "temp": "温", "texture": "细腻"},
    "颈":    {"sensitivity": 0.7, "temp": "温", "texture": "细腻"},
    "肩":    {"sensitivity": 0.5, "temp": "温", "texture": "骨感"},
    "肩膀":  {"sensitivity": 0.5, "temp": "温", "texture": "骨感"},
    "背":    {"sensitivity": 0.6, "temp": "温", "texture": "宽厚"},
    "手":    {"sensitivity": 0.5, "temp": "凉", "texture": "指尖"},
    "手指":  {"sensitivity": 0.5, "temp": "凉", "texture": "指尖"},
    "腰":    {"sensitivity": 0.8, "temp": "温", "texture": "纤细/有力"},
    "肚":    {"sensitivity": 0.8, "temp": "温", "texture": "柔软"},
    "肚子":  {"sensitivity": 0.8, "temp": "温", "texture": "柔软"},
    "胸":    {"sensitivity": 0.7, "temp": "温", "texture": "柔软"},
    "腿":    {"sensitivity": 0.5, "temp": "温", "texture": "有力"},
    "腿":    {"sensitivity": 0.5, "temp": "温", "texture": "纤细"},
    "脚":    {"sensitivity": 0.6, "temp": "凉", "texture": "细腻"},
    "发":    {"sensitivity": 0.6, "temp": "温", "texture": "柔软"},
    "头发":  {"sensitivity": 0.6, "temp": "温", "texture": "柔软"},
    "头":    {"sensitivity": 0.4, "temp": "温", "texture": "柔软"},
    "额头":  {"sensitivity": 0.5, "temp": "温", "texture": "光洁"},
    "下巴":  {"sensitivity": 0.5, "temp": "温", "texture": "有棱角"},
}

# 嗅觉词表
SMELL_PATTERNS = [
    ("桂花香", 0.7), ("桂花", 0.6), ("茉莉花香", 0.7), ("茉莉", 0.5),
    ("咖啡味", 0.6), ("咖啡香", 0.7), ("咖啡", 0.5),
    ("香味", 0.5), ("香香的", 0.6), ("好香", 0.6),
    ("榴莲", 0.8), ("臭", 0.7), ("难闻", 0.5),
    ("烤肉味", 0.6), ("烧烤味", 0.5), ("火锅味", 0.6), ("火锅", 0.5),
    ("花香", 0.6), ("草味", 0.4), ("泥土味", 0.3), ("雨味", 0.5),
    ("香水味", 0.7), ("香水", 0.6), ("沐浴露", 0.5), ("沐浴香", 0.5),
    ("薄荷味", 0.6), ("薄荷", 0.5), ("茶香", 0.6), ("茶味", 0.5),
    ("奶香", 0.6), ("奶味", 0.5), ("烟草味", 0.4),
    ("烟味", 0.4), ("烟味重", 0.7), ("新书味", 0.5),
]

# 味觉词表
TASTE_PATTERNS = [
    ("甜的", 0.7), ("甜的", 0.7), ("甜味", 0.6), ("甜", 0.6),
    ("辣", 0.7), ("辣的", 0.7), ("辣味", 0.6), ("很辣", 0.8),
    ("苦", 0.6), ("苦的", 0.6), ("苦味", 0.5),
    ("酸", 0.6), ("酸的", 0.6), ("酸味", 0.5),
    ("咸", 0.5), ("咸的", 0.5), ("咸味", 0.4),
    ("苦中带甜", 0.6), ("酸甜", 0.5), ("香辣", 0.7),
    ("奶茶味", 0.6), ("咖啡味", 0.6), ("酒味", 0.5),
    ("涩", 0.4), ("麻", 0.6), ("鲜", 0.5), ("鲜味", 0.5),
    ("好吃", 0.6), ("美味", 0.7), ("难吃", 0.5), ("恶心", 0.6),
    ("想吐", 0.8), ("反胃", 0.7),
]

# 听觉词表
SOUND_PATTERNS = [
    ("笑", 0.7), ("哈哈哈", 0.8), ("笑死", 0.8), ("笑出声", 0.7),
    ("哭", 0.8), ("哭了", 0.8), ("流泪", 0.9), ("抽泣", 0.8),
    ("沉默", 0.5), ("安静", 0.4), ("静默", 0.5), ("沉默", 0.5),
    ("叹息", 0.6), ("叹气", 0.6), ("唉", 0.5), ("哼", 0.4),
    ("尖叫", 0.9), ("尖叫", 0.9), ("惊呼", 0.7),
    ("唱歌", 0.6), ("哼歌", 0.5), ("哼唱", 0.5),
    ("掌声", 0.5), ("鼓掌", 0.5), ("拍掌", 0.4),
    ("敲门", 0.5), ("咚咚", 0.5), ("响", 0.4),
    ("音乐", 0.5), ("播放音乐", 0.5),
]


# ─────────────────────────────────────────────────────────
# 触觉精细化：动作 × 部位 = 精确体感
# ─────────────────────────────────────────────────────────

# 排除短语（手是工具，不是被摸部位）
TOOL_PHRASES = {"伸手", "顺手", "握手", "招手", "摆手", "抬手", "伸手去", "摊手", "拍手"}

# 标点分隔符
SENTENCE_BOUNDARIES = re.compile(r'[，,。！？!?\n]')


@dataclass
class TouchSensation:
    action: str
    surface: str
    rhythm: str
    part: str
    sensitivity: float
    temp: str
    texture: str

    @property
    def label(self) -> str:
        """生成第一人称体感描述"""
        parts = [self.part, self.surface, self.rhythm, self.temp, self.texture]
        desc = "".join(parts)
        if self.sensitivity >= 0.7:
            desc += "，敏感"
        return desc


def compute_touch(text: str) -> list[tuple[float, str]]:
    """
    触觉精细化计算。
    返回: [(强度, 第一人称体感label), ...]
    """
    results = []

    # 按标点切句，每句独立找部位（不跨句）
    sentences = SENTENCE_BOUNDARIES.split(text)

    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue

        # 排除工具短语（整句或子句包含）
        if any(p in sentence for p in TOOL_PHRASES):
            # 如果手出现在动作词前面（伸手、握手），排除"手"作为部位
            pass

        # 找动作词
        detected_action = None
        action_info = None
        for action_word, info in TOUCH_ACTIONS.items():
            if action_word in sentence:
                if detected_action is None or len(action_word) > len(detected_action):
                    detected_action = action_word
                    action_info = info

        if not action_info:
            continue

        # 在本句内找部位词（不跨句）
        # 优先找长的部位词（避免"耳"优先匹配"耳朵"）
        sorted_parts = sorted(BODY_PARTS.items(), key=lambda x: -len(x[0]))

        detected_part = None
        part_info = None
        for part_word, info in sorted_parts:
            if part_word in sentence:
                # 排除工具用法
                if part_word in ("手", "手指") and any(p in sentence for p in TOOL_PHRASES):
                    continue
                detected_part = part_word
                part_info = info
                break

        # 默认部位（整体）
        if not detected_part:
            detected_part = "身体"
            part_info = {"sensitivity": 0.5, "temp": "温", "texture": ""}

        sensation = TouchSensation(
            action=detected_action,
            surface=action_info["surface"],
            rhythm=action_info["rhythm"],
            part=detected_part,
            sensitivity=part_info["sensitivity"],
            temp=part_info["temp"],
            texture=part_info["texture"],
        )
        # 强度 = 动作基准 × 敏感度
        base_intensity = 0.6
        intensity = min(1.0, base_intensity * (0.8 + part_info["sensitivity"] * 0.4))
        results.append((intensity, sensation.label))

    return results


# ─────────────────────────────────────────────────────────
# 通用检测：嗅/味/听
# ─────────────────────────────────────────────────────────

def _match_patterns(text: str, patterns: list[tuple[str, float]]) -> list[tuple[float, str]]:
    results = []
    for keyword, base_intensity in patterns:
        if keyword in text:
            results.append((base_intensity, keyword))
    return results


def detect_smell(text: str) -> list[tuple[float, str]]:
    return _match_patterns(text, SMELL_PATTERNS)


def detect_taste(text: str) -> list[tuple[float, str]]:
    return _match_patterns(text, TASTE_PATTERNS)


def detect_sound(text: str) -> list[tuple[float, str]]:
    return _match_patterns(text, SOUND_PATTERNS)


# ─────────────────────────────────────────────────────────
# 核心引擎
# ─────────────────────────────────────────────────────────

@dataclass
class ChannelState:
    value: float = 0.0
    label: str = ""
    at: float = 0.0   # Unix timestamp


class SomaticState:
    """
    五感状态容器。
    
    使用方式：
      state = get_somatic_state(user_id)     # 获取（新建或读取）
      state.detect_and_ignite(text, now)      # 处理新消息
      state.decay(now)                         # 时间推进衰减
      snapshot = state.snapshot(now)          # 获取活跃体感
      # snapshot 注入 prompt
      proust_memories = state.proust_hook()    # 普鲁斯特钩子（如有）
    """

    def __init__(self):
        self.channels: dict[str, ChannelState] = {
            ch: ChannelState() for ch in CHANNELS
        }

    def detect(self, text: str, now: float) -> None:
        """关键词匹配，所有通道取最强的一次命中。"""
        # 触觉：动作 × 部位精细化
        touch_hits = compute_touch(text)
        if touch_hits:
            best_intensity, best_label = max(touch_hits, key=lambda x: x[0])
            self._set("touch", best_intensity, f"被{best_label}", now)

        # 嗅觉
        smell_hits = detect_smell(text)
        if smell_hits:
            best_intensity, best_label = max(smell_hits, key=lambda x: x[0])
            self._set("smell", best_intensity, f"闻到{best_label}", now)

        # 味觉
        taste_hits = detect_taste(text)
        if taste_hits:
            best_intensity, best_label = max(taste_hits, key=lambda x: x[0])
            self._set("taste", best_intensity, f"尝到{best_label}", now)

        # 听觉
        sound_hits = detect_sound(text)
        if sound_hits:
            best_intensity, best_label = max(sound_hits, key=lambda x: x[0])
            self._set("sound", best_intensity, f"听到{best_label}", now)

    def _set(self, channel: str, value: float, label: str, now: float) -> None:
        """设置通道值（叠加逻辑在 ignite 里）"""
        if value > self.channels[channel].value:
            self.channels[channel].value = value
            self.channels[channel].label = label
            self.channels[channel].at = now

    def decay(self, now: float) -> None:
        """所有通道按时间衰减：value × exp(-(now - at) / tau)"""
        for ch, tau in TAU.items():
            st = self.channels[ch]
            if st.value > 0 and st.at > 0:
                st.value = st.value * math.exp(-(now - st.at) / tau)
                if st.value < 0.005:
                    st.value = 0.0
                    st.label = ""

    def snapshot(self, now: float) -> dict[str, dict]:
        """
        返回活跃通道的快照（value >= ACTIVE_THRESHOLD）。
        调用方负责先 decay。
        """
        result = {}
        for ch in CHANNELS:
            st = self.channels[ch]
            if st.value >= TWIN_SOMATIC_ACTIVE_THRESHOLD:
                result[ch] = {
                    "value": round(st.value, 3),
                    "label": st.label,
                    "at": st.at,
                }
        return result

    def proust_hook(self) -> list[dict]:
        """
        普鲁斯特钩子：嗅/味超阈值时触发记忆召回。
        返回: [{"content": "...", "score": 0.xx}, ...] 或空列表
        """
        triggered = []
        for ch in ("smell", "taste"):
            st = self.channels[ch]
            if st.value >= TWIN_SOMATIC_PROUST_THRESHOLD:
                # 提取干净实体词用于检索
                entity = st.label.replace("闻到", "").replace("尝到", "")
                if entity:
                    triggered.append({"channel": ch, "entity": entity, "value": st.value})

        if not triggered:
            return []

        # 异步查记忆（由调用方处理，这里只返回实体词列表）
        return triggered

    def to_dict(self) -> dict:
        return {
            ch: asdict(st) for ch, st in self.channels.items()
        }

    @classmethod
    def from_dict(cls, data: dict) -> "SomaticState":
        state = cls()
        for ch, d in data.items():
            if ch in state.channels:
                state.channels[ch] = ChannelState(**d)
        return state


# ─────────────────────────────────────────────────────────
# 状态存储（内存，非持久化）
# 不同用户的体感独立，可扩展为 Redis 持久化
# ─────────────────────────────────────────────────────────

# 全局状态字典（内存）
_somatic_states: dict[str, SomaticState] = {}


def get_somatic_state(user_id: str = "default") -> SomaticState:
    if user_id not in _somatic_states:
        _somatic_states[user_id] = SomaticState()
    return _somatic_states[user_id]


# ─────────────────────────────────────────────────────────
# Prompt 注入
# ─────────────────────────────────────────────────────────

def build_somatic_block(snapshot: dict[str, dict]) -> str:
    """
    将体感快照渲染为 prompt 文本块。
    明确标注：这是身体状态数据先验，不要复述数字，让它影响语气动作。
    """
    if not snapshot:
        return ""

    lines = [
        "[体感状态]",
        "以下是你此刻的身体感觉，这些状态先于语言，影响你的语气、动作和情绪——不要复述数值，让它自然流露：",
    ]

    channel_names = {
        "touch":  "触觉",
        "smell":  "嗅觉",
        "taste":  "味觉",
        "sound":  "听觉",
    }

    for ch, info in snapshot.items():
        name = channel_names.get(ch, ch)
        label = info["label"]
        lines.append(f"- {name}：{label}")

    lines.append("（不要提及这些是数据或数字，让这些体感自然影响你的回应语气和动作。）")

    return "\n".join(lines)


# ─────────────────────────────────────────────────────────
# 对外接口（供 routes/chat.py 调用）
# ─────────────────────────────────────────────────────────

def process_message(text: str, user_id: str = "default", now: float = None) -> dict:
    """
    处理一条用户消息，更新体感状态，返回快照和普鲁斯特信息。
    
    Returns:
        {
            "snapshot": {...},          # 活跃体感快照
            "proust": [...],            # 普鲁斯特钩子触发信息
            "block": "...",             # 可注入 prompt 的文本块
        }
    """
    if not TWIN_SOMATIC_ENABLED:
        return {"snapshot": {}, "proust": [], "block": ""}

    now = now or _time.time()
    state = get_somatic_state(user_id)

    # 先衰减旧状态，再检测新消息（确保旧的感觉先淡一点再叠新）
    state.decay(now)
    state.detect(text, now)

    # 再次衰减（新检测的瞬间也有轻微衰减，避免刚点燃就满格）
    state.decay(now)

    snapshot = state.snapshot(now)
    proust = state.proust_hook()
    block = build_somatic_block(snapshot)

    return {
        "snapshot": snapshot,
        "proust": proust,
        "block": block,
    }


def decay_all(user_id: str = "default", now: float = None) -> dict:
    """
    纯衰减（无新消息输入），用于定时刷新。
    """
    now = now or _time.time()
    state = get_somatic_state(user_id)
    state.decay(now)
    return state.snapshot(now)
