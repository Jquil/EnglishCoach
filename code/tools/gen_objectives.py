#!/usr/bin/env python3
"""NCE 学习目标 LLM 批量预生成脚本（PM v2 第三节·方案 A）。

对 276 课逐课调用 OpenAI 兼容 API，生成 2~4 条"通过本关卡，你将学会什么"句式
的知识点（核心句型/语法点/关键词，禁止干巴巴的语法术语），输出
app/src/main/assets/nce/objectives.json（key = "nce_1".."nce_276"）。

⚠️ 实际运行由 team-lead 安排（需要用户提供 API Key）。运行方式：
  set NCE_LLM_API_KEY=sk-xxx            # 必填
  set NCE_LLM_BASE_URL=https://api.deepseek.com/v1   # 可选，默认 DeepSeek
  set NCE_LLM_MODEL=deepseek-chat       # 可选
  python tools/gen_objectives.py [--workers 4] [--force]

特性：断点续跑（已有输出保留，--force 全量重跑）；失败自动重试 1 次；
输出与占位数据同 schema，App 端无需改代码。
"""
import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import urllib.request

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "nce"

BASE_URL = os.environ.get("NCE_LLM_BASE_URL", "https://api.deepseek.com/v1")
MODEL = os.environ.get("NCE_LLM_MODEL", "deepseek-chat")
API_KEY = os.environ.get("NCE_LLM_API_KEY", "")

PROMPT = """你是一名英语教研老师。下面是《新概念英语》一课的课文句子（英文|中文）。
请生成 2~4 条"通过本关卡，你将学会什么"式学习目标：
- 用"学会用英语……"/"听懂别人……"的输出导向句式，面向中国初中级学习者
- 禁止干巴巴的语法术语（如"一般疑问句""There be 句型"），要写成能力描述
- 每条 ≤ 30 字，中文

课文：
{lesson}

只输出 JSON 数组，如：["学会用英语礼貌地引起别人注意", "听懂并回答 Is this your...? 这类确认问句"]"""


def load_sentences(lrc_path: Path) -> str:
    text = lrc_path.read_text(encoding="utf-8", errors="replace")
    lines = []
    for line in text.splitlines():
        m = re.match(r"^\[\d{1,2}:\d{1,2}(?:\.\d{1,3})?\]\s*(.*)$", line.strip())
        if m:
            lines.append(m.group(1).strip())
    return "\n".join(lines[:60])  # 截断防超长（NCE3/4 长课）


def call_llm(lesson_text: str) -> list[str]:
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": "You are an English curriculum designer. Reply with ONLY a JSON array of 2-4 short Chinese strings."},
            {"role": "user", "content": PROMPT.format(lesson=lesson_text[:4000])},
        ],
        "temperature": 0.3,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content = data["choices"][0]["message"]["content"].strip()
    content = re.sub(r"^```(json)?|```$", "", content, flags=re.M).strip()
    items = json.loads(content)
    return [str(x).strip() for x in items if str(x).strip()][:4]


def main() -> int:
    import sys

    if not API_KEY:
        print("[ERROR] 请先设置环境变量 NCE_LLM_API_KEY")
        return 1
    out_path = ASSETS / "objectives.json"
    result: dict[str, list[str]] = {}
    if out_path.exists():
        result = json.loads(out_path.read_text(encoding="utf-8"))
    force = "--force" in sys.argv
    workers = int(sys.argv[sys.argv.index("--workers") + 1]) if "--workers" in sys.argv else 4
    index = json.loads((ASSETS / "index.json").read_text(encoding="utf-8"))

    def lesson_key(lesson: dict) -> str:
        return f"nce_{lesson['no']}"

    def run_one(lesson: dict) -> None:
        key = lesson_key(lesson)
        if key in result and not force:
            return
        lrc_path = ASSETS / lesson["lrc"]
        if not lrc_path.exists():
            return
        lesson_text = load_sentences(lrc_path)
        for attempt in range(2):
            try:
                items = call_llm(lesson_text)
                if items:
                    result[key] = items
                    print(f"[OK] {key} {lesson['title']}: {len(items)} 条")
                return
            except Exception as e:  # noqa: BLE001
                print(f"[WARN] {key} 第 {attempt + 1} 次失败: {e}")
                time.sleep(2)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(run_one, index["lessons"]))

    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[OK] 写出 {len(result)} 课 → {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
