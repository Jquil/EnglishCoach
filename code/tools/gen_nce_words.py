#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NCE 课文词频词池生成（v10.4 需求 4：快捷跟读词池换成《新概念英语》1-4 册高频词汇）。

流程（纯本地统计，不依赖 LLM、不联网、key 无关）：
  解析 app/src/main/assets/nce/**/*.lrc（276 课文）
    → 提取英文行（lrc 行格式 "[mm:ss.xx]english | 中文"）
    → 分词统计词频（全小写）
    → 过滤：非纯字母 token、长度 <3、停用功能词（极短功能词酌情过滤）、
      人名等专有名词靠与 dict.json 求交集自然剔除
    → 与 dict.json（约 1500 词）求交集 → 保证每词有音标/释义（与音节表天然同源）
    → 输出 app/src/main/assets/words.json：WordItem 格式 [{word, phonetic, meaning, chinese}]
      按词频降序（高频先出现），chinese 取 dict 例句英译（exampleZh，缺失退 example）

用法：
  python tools/gen_nce_words.py [--min-freq 2] [--dry-run]
"""

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NCE_DIR = ROOT / "app" / "src" / "main" / "assets" / "nce"
DICT_JSON = ROOT / "app" / "src" / "main" / "assets" / "nce" / "dict.json"
OUT_JSON = ROOT / "app" / "src" / "main" / "assets" / "words.json"

TOKEN_RE = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
LRC_TAG_RE = re.compile(r"\[[^\]]*\]")

# 极短/功能词停用表（快捷跟读练这些没价值；酌情过滤）
STOPWORDS = {
    "the", "and", "that", "have", "for", "not", "with", "you", "this", "but",
    "his", "from", "they", "she", "will", "one", "all", "would", "there",
    "their", "what", "out", "about", "who", "get", "which", "when", "make",
    "can", "like", "time", "just", "him", "know", "take", "into", "year",
    "your", "good", "some", "could", "them", "see", "other", "than", "then",
    "now", "look", "only", "come", "its", "over", "also", "back", "after",
    "use", "two", "how", "our", "way", "even", "new", "want", "any", "these",
    "most", "was", "were", "has", "had", "did", "does", "are", "her", "him",
    "here", "well", "very", "much", "may", "each", "because", "while",
    "where", "who", "why", "yes", "oh", "mr", "mrs", "sir", "ok",
}


def extract_sentences(lrc_path: Path) -> list[str]:
    """lrc → 英文句列表（剥时间戳标签，取 | 前英文部分）。"""
    sentences: list[str] = []
    try:
        for line in lrc_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            text = LRC_TAG_RE.sub("", line).strip()
            if not text:
                continue
            english = text.split("|")[0].strip()
            if english:
                sentences.append(english)
    except Exception as e:  # noqa: BLE001
        print(f"警告：解析 {lrc_path.name} 失败: {e}", file=sys.stderr)
    return sentences


def main() -> int:
    parser = argparse.ArgumentParser(description="NCE 课文词频词池生成")
    parser.add_argument("--min-freq", type=int, default=2, help="最低出现次数（默认 2）")
    parser.add_argument("--dry-run", action="store_true", help="只打印统计不写文件")
    args = parser.parse_args()

    # 1) 全部 lrc → 词频
    freq: Counter[str] = Counter()
    lrc_files = sorted(NCE_DIR.rglob("*.lrc"))
    for path in lrc_files:
        for sentence in extract_sentences(path):
            for token in TOKEN_RE.findall(sentence):
                freq[token.lower()] += 1
    print(f"课文 {len(lrc_files)} 篇，不同词 {len(freq)} 个")

    # 2) 过滤：长度 / 停用表 / 纯数字（token 正则已保证纯字母）
    filtered = {
        w: c
        for w, c in freq.items()
        if len(w) >= 3 and w not in STOPWORDS and c >= args.min_freq
    }
    print(f"过滤后（len≥3 + 停用表 + 出现≥{args.min_freq} 次）：{len(filtered)} 词")

    # 3) 与 dict.json 求交集（保证音标/释义可得；人名等专有名词自然被剔除）
    try:
        dict_entries = json.loads(DICT_JSON.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"错误：读取 {DICT_JSON} 失败: {e}", file=sys.stderr)
        return 1
    dict_by_word = {e.get("word", "").strip().lower(): e for e in dict_entries if isinstance(e, dict)}
    hits = {w: c for w, c in filtered.items() if w in dict_by_word}
    print(f"与 dict.json 交集：{len(hits)} 词")

    # 4) 输出 WordItem 数组（词频降序，同频按字母）
    items = []
    for w in sorted(hits, key=lambda x: (-hits[x], x)):
        d = dict_by_word[w]
        items.append(
            {
                "word": d.get("word", w),
                "phonetic": d.get("phonetic", ""),
                "meaning": d.get("meaning", ""),
                "chinese": d.get("exampleZh") or d.get("example", ""),
            }
        )

    if args.dry_run:
        top = " ".join(f"{w}:{hits[w]}" for w in sorted(hits, key=lambda x: -hits[x])[:30])
        print(f"Top30：{top}")
        print(f"dry-run 共 {len(items)} 词，未写文件")
        return 0
    if not items:
        print("错误：交集为空（dict.json 为空或格式不对），未写文件", file=sys.stderr)
        return 1

    OUT_JSON.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"完成：输出 {len(items)} 词 → {OUT_JSON}（词频降序）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
