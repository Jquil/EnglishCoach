#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
单词分段（音节）数据批量生成管线（需求 v5：单词分段点击发音）。

流程：
  读 app/src/main/assets/nce/dict.json（内置迷你词典全部词）+ app/src/main/assets/words.json（内置 48 词）
    → 调智谱 API（OpenAI 兼容，BASE_URL=https://open.bigmodel.cn/api/paas/v4，MODEL=glm-5.3-flash）
    → 逐词校验（音节拼接还原 == 原词全小写、主重音下标在范围内）
    → 合并输出 app/src/main/assets/nce/syllables.json，格式：
        { "family": { "s": ["fam", "i", "ly"], "st": 1 }, ... }

约定（与 gen_lesson_svgs.py 一致）：
  - API key 只从环境变量 NCE_SVG_API_KEY 读取，严禁把 key 写进代码或任何文件
  - 断点续跑：syllables.json 已存在的词条直接保留跳过
  - 并发 ≤ 4，单批失败重试 2 次
  - 校验失败丢弃该词条（宁缺勿错）；词表外词运行时走 UI 降级（整词发音），不补拆

用法：
  export NCE_SVG_API_KEY=<your-key>
  python tools/gen_syllables.py [--batch-size 80] [--workers 4]
"""

import argparse
import concurrent.futures
import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

# ---------- 常量 ----------

ROOT = Path(__file__).resolve().parent.parent
DICT_JSON = ROOT / "app" / "src" / "main" / "assets" / "nce" / "dict.json"
WORDS_JSON = ROOT / "app" / "src" / "main" / "assets" / "words.json"
OUT_JSON = ROOT / "app" / "src" / "main" / "assets" / "nce" / "syllables.json"

BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
MODEL = "glm-5.3-flash"
MAX_WORKERS = 4
MAX_RETRIES = 2  # 失败重试 2 次（首次 + 2 重试）

SYSTEM_PROMPT = """You are an English pronunciation assistant. For each English word given, split it into syllables and mark the primary stress.
Rules:
- Output ONLY a JSON object mapping each input word to {"s": [<syllables>], "st": <index of primary-stressed syllable, 0-based>}.
- Concatenating the syllables in "s" must reproduce the word EXACTLY (all lowercase, same letters, no additions or omissions).
- Monosyllabic words: "s" is the whole word, "st" is 0.
- Use standard dictionary syllabification (e.g. family -> fam/i/ly stress 1, excuse -> ex/cuse stress 1).
- Do not output anything except the JSON object (no markdown fence, no explanation)."""


def load_words() -> list[str]:
    """词表 = dict.json 全部词 + words.json 全部词（去重、全小写、去标点）。"""
    words: set[str] = set()

    def clean(w: str) -> str:
        return w.strip().strip(".,!?;:\"'()").lower()

    try:
        data = json.loads(DICT_JSON.read_text(encoding="utf-8"))
        for item in data:
            w = clean(item.get("word", ""))
            if len(w) >= 2 and w.isascii() and w.isalpha():
                words.add(w)
    except Exception as e:  # noqa: BLE001
        print(f"警告：读取 {DICT_JSON} 失败: {e}", file=sys.stderr)

    try:
        data = json.loads(WORDS_JSON.read_text(encoding="utf-8"))
        for item in data:
            w = clean(item.get("word", ""))
            if len(w) >= 2 and w.isascii() and w.isalpha():
                words.add(w)
    except Exception as e:  # noqa: BLE001
        print(f"警告：读取 {WORDS_JSON} 失败: {e}", file=sys.stderr)

    return sorted(words)


def call_zhipu(batch: list[str], api_key: str, timeout: int = 120) -> dict:
    """一批词送智谱，返回模型输出的 {word: {"s": [...], "st": int}} 原始 dict。失败抛异常。"""
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(batch, ensure_ascii=False)},
        ],
        "temperature": 0.2,  # 拆分是确定性任务，低温
    }
    req = urllib.request.Request(
        url=f"{BASE_URL}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    content = body["choices"][0]["message"]["content"].strip()
    if content.startswith("```"):
        content = content.removeprefix("```json").removeprefix("```")
        if content.endswith("```"):
            content = content.removesuffix("```")
    return json.loads(content.strip())


def validate_batch(batch: list[str], raw: dict) -> dict[str, dict]:
    """硬校验：拼接还原 == 原词全小写、st 在下标范围内、s 非空且全小写。不过的丢（宁缺勿错）。"""
    ok: dict[str, dict] = {}
    for word in batch:
        entry = raw.get(word)
        if not isinstance(entry, dict):
            continue
        s = entry.get("s")
        st = entry.get("st")
        if not isinstance(s, list) or not s:
            continue
        if not all(isinstance(seg, str) and seg and seg == seg.lower() for seg in s):
            continue
        if "".join(s) != word:  # 硬校验：拼接还原必须等于原词
            continue
        if not isinstance(st, int) or not (0 <= st < len(s)):
            continue
        ok[word] = {"s": s, "st": st}
    return ok


def process_batch(batch: list[str], api_key: str) -> dict[str, dict]:
    """单批：请求 + 重试 2 次 + 校验。"""
    last_err = ""
    for attempt in range(1 + MAX_RETRIES):
        try:
            raw = call_zhipu(batch, api_key)
            return validate_batch(batch, raw)
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, KeyError, ValueError) as e:
            last_err = f"{type(e).__name__}: {e}"
            time.sleep(2 * (attempt + 1))
        except Exception as e:  # noqa: BLE001
            last_err = f"{type(e).__name__}: {e}"
            time.sleep(2 * (attempt + 1))
    print(f"  批失败（{len(batch)} 词）：{last_err}", file=sys.stderr)
    return {}


def main() -> int:
    parser = argparse.ArgumentParser(description="音节分段数据批量生成")
    parser.add_argument("--batch-size", type=int, default=80, help="每次请求的词数（默认 80）")
    parser.add_argument("--workers", type=int, default=MAX_WORKERS, help="并发数（默认 4）")
    args = parser.parse_args()

    api_key = os.environ.get("NCE_SVG_API_KEY", "").strip()
    if not api_key:
        print("错误：未设置环境变量 NCE_SVG_API_KEY（严禁把 key 写进任何文件）", file=sys.stderr)
        return 1

    all_words = load_words()
    print(f"词表共 {len(all_words)} 词（dict.json + words.json 去重）")

    # 断点续跑：已输出文件里已有的词条保留跳过
    existing: dict[str, dict] = {}
    if OUT_JSON.exists():
        try:
            existing = json.loads(OUT_JSON.read_text(encoding="utf-8"))
            print(f"已有 {len(existing)} 条，断点续跑")
        except Exception:  # noqa: BLE001
            existing = {}

    todo = [w for w in all_words if w not in existing]
    if not todo:
        print("全部词已有数据，无需生成")
        return 0
    print(f"待生成 {len(todo)} 词，批大小 {args.batch_size}，并发 {args.workers}")

    batches = [todo[i : i + args.batch_size] for i in range(0, len(todo), args.batch_size)]
    got: dict[str, dict] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(process_batch, b, api_key): b for b in batches}
        done = 0
        for fut in concurrent.futures.as_completed(futures):
            got.update(fut.result())
            done += 1
            print(f"  进度 {done}/{len(batches)} 批，累计 {len(existing) + len(got)} 条")

    merged = {**existing, **got}
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(merged, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"完成：输出 {len(merged)} 条 → {OUT_JSON}（本次新增 {len(got)}，失败 {len(todo) - len(got)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
