#!/usr/bin/env python3
"""NCE 学习目标占位数据生成（可复跑，不需要 API Key）。

从 assets/nce/index.json + lrc 提取每课占位知识点（PM v2 第三节：每课 2~4 个，
"学会什么"句式，不写语法术语）。正式数据用 tools/gen_objectives.py（LLM 预生成）
覆盖同一文件 assets/nce/objectives.json，App 端接口不变。

占位口径：取该课最短的 2~3 句英文（短句多为课内核心句型），生成
"跟读并学会说：「xxx」"；句子过长的课回退取首句。

用法：python tools/gen_placeholder_objectives.py
"""
import json
import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "nce"


# 教材标题头部行（"Lesson 1 | 第1课"），与 App 端 LrcParser 同规则过滤
LESSON_HEADER_EN = re.compile(r"^lesson\s*\d+\s*\.?\s*$", re.I)
LESSON_HEADER_ZH = re.compile(r"^第\s*\d+\s*课")


def load_sentences(lrc_path: Path) -> list[str]:
    text = lrc_path.read_text(encoding="utf-8", errors="replace")
    out = []
    for line in text.splitlines():
        m = re.match(r"^\[\d{1,2}:\d{1,2}(?:\.\d{1,3})?\]\s*(.*)$", line.strip())
        if not m:
            continue
        body = m.group(1).strip()
        if not body or "|" not in body:
            continue
        english, chinese = body.split("|", 1)
        english, chinese = english.strip(), chinese.strip()
        if not english:
            continue
        # 过滤标题头部行（英文侧纯 "Lesson N" 且中文侧为"第N课"或空）
        if LESSON_HEADER_EN.match(english) and (chinese == "" or LESSON_HEADER_ZH.match(chinese)):
            continue
        out.append(english)
    return out


def objectives_for(english_lines: list[str]) -> list[str]:
    # 短句（2~10 词）多为核心句型：去重取前 4（工单 P1-2：去重、限 2~4 条）
    seen, picked = set(), []
    for line in english_lines:
        words = line.split()
        if not (2 <= len(words) <= 10):
            continue
        key = line.lower()
        if key in seen:
            continue
        seen.add(key)
        picked.append(f"跟读并学会说：「{line}」")
        if len(picked) >= 4:
            break
    if not picked and english_lines:
        picked = [f"跟读并学会说：「{english_lines[0][:60]}」"]
    return picked


def main() -> int:
    index = json.loads((ASSETS / "index.json").read_text(encoding="utf-8"))
    result: dict[str, list[str]] = {}
    for lesson in index["lessons"]:
        lrc_path = ASSETS / lesson["lrc"]
        if not lrc_path.exists():
            continue
        result[f"nce_{lesson['no']}"] = objectives_for(load_sentences(lrc_path))
    out = ASSETS / "objectives.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[OK] 占位学习目标已生成 {len(result)} 课 → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
