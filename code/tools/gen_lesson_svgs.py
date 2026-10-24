#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
276 关全量 NCE 课题 SVG 插画管线（第二轮真机反馈需求 6，只写不运行）。

流程：
  读 app/src/main/assets/nce/index.json（每课 no/title/主题）
    → 调智谱 API（OpenAI 兼容，BASE_URL=https://open.bigmodel.cn/api/paas/v4，MODEL=glm-5.3-flash）
    → 输出 app/src/main/assets/nce/art/nce_{no}.svg

风格系统（写死的 system prompt）：白色单色、几何简约、无文字、构图主体偏右上、左下留白、viewBox 统一 0 0 200 200。

脚本要求：
  - 断点续跑：目标 SVG 已存在则跳过（nce_1/2/3.svg 为手绘版，自动跳过）
  - 并发 ≤ 4（ThreadPoolExecutor）
  - 每个任务失败重试 2 次（共最多 3 次请求）
  - SVG 校验：XML 可解析、不含 <text>、体积 < 20KB；校验不过视为失败重试

用法：
  export NCE_SVG_API_KEY=<your-key>   # 从环境变量读 key，严禁写进代码/文件
  python tools/gen_lesson_svgs.py [--limit N] [--workers 4]
"""

import argparse
import concurrent.futures
import json
import os
import sys
import time
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
from pathlib import Path

# ---------- 常量（工程相对本脚本位置） ----------

ROOT = Path(__file__).resolve().parent.parent
INDEX_JSON = ROOT / "app" / "src" / "main" / "assets" / "nce" / "index.json"
ART_DIR = ROOT / "app" / "src" / "main" / "assets" / "nce" / "art"

BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
MODEL = "glm-5.3-flash"
MAX_WORKERS = 4
MAX_RETRIES = 2          # 失败重试 2 次（首次 + 2 重试 = 最多 3 次请求）
MAX_SVG_BYTES = 20 * 1024

STYLE_SYSTEM_PROMPT = """You are an illustration generator. Produce ONE minimalist SVG illustration for an English textbook lesson cover.
Hard rules:
- White monochrome only: all strokes/fills must be black or white or gray (e.g. #000000, #555555, #FFFFFF). No color.
- Geometric minimalism: simple shapes (circles, lines, arcs, rects, paths), clean and elegant, like a line-art logo.
- Absolutely NO text: no <text>, no <tspan>, no letters or numbers rendered as shapes.
- Composition: the main subject sits in the upper-right region of the canvas; leave the lower-left area mostly empty (negative space).
- Use viewBox="0 0 200 200" exactly.
- Output ONLY the raw SVG source starting with <svg and ending with </svg>. No markdown, no explanation, no XML declaration."""

USER_PROMPT_TEMPLATE = (
    "Lesson title: \"{title}\" (from New Concept English, lesson {no}). "
    "Draw one minimalist white-monochrome geometric line illustration evoking this lesson's theme. "
    'Output the complete SVG now: <svg viewBox="0 0 200 200" xmlns="http://www.w3.org/2000/svg"> ... </svg>'
)


# ---------- 智谱 API 调用 ----------

def call_zhipu(title: str, no: int, api_key: str, timeout: int = 120) -> str:
    """调智谱 chat/completions（OpenAI 兼容），返回模型输出的 SVG 文本。失败抛异常。"""
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": STYLE_SYSTEM_PROMPT},
            {"role": "user", "content": USER_PROMPT_TEMPLATE.format(title=title, no=no)},
        ],
        "temperature": 0.7,
    }
    req = urllib.request.Request(
        url=f"{BASE_URL}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    content = body["choices"][0]["message"]["content"].strip()
    return extract_svg(content)


def extract_svg(content: str) -> str:
    """剥掉可能的 markdown 围栏 / 前后杂文，截取 <svg ...>...</svg>。"""
    start = content.find("<svg")
    end = content.rfind("</svg>")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("输出中未找到完整 <svg>...</svg>")
    return content[start : end + len("</svg>")]


# ---------- SVG 校验 ----------

def validate_svg(svg: str) -> None:
    """校验失败抛 ValueError：XML 可解析、不含 <text>、体积 < 20KB、viewBox 正确。"""
    if len(svg.encode("utf-8")) >= MAX_SVG_BYTES:
        raise ValueError(f"SVG 体积超过 {MAX_SVG_BYTES // 1024}KB")
    root = ET.fromstring(svg)  # 不可解析抛 ParseError（ValueError 的子类不是，单独处理）
    if root.tag != "{http://www.w3.org/2000/svg}svg" and not root.tag.endswith("svg"):
        raise ValueError("根元素不是 <svg>")
    vb = root.get("viewBox", "")
    if vb != "0 0 200 200":
        # 宽容处理：viewBox 缺失则补上；数值不对则强制替换
        pass
    for elem in root.iter():
        tag = elem.tag.split("}")[-1].lower()
        if tag in ("text", "tspan"):
            raise ValueError("SVG 中包含 <text>/<tspan>（风格规则禁止文字）")
    if "viewBox" not in root.attrib:
        root.set("viewBox", "0 0 200 200")


def normalize_svg(svg: str) -> str:
    """校验后统一补 viewBox 与 xmlns 声明。"""
    root = ET.fromstring(svg)
    root.set("viewBox", "0 0 200 200")
    root.set("xmlns", "http://www.w3.org/2000/svg")
    return ET.tostring(root, encoding="unicode")


# ---------- 单课任务 ----------

def generate_one(no: int, title: str, api_key: str) -> tuple[int, bool, str]:
    """生成单课 SVG（含重试与校验）。返回 (no, ok, message)。"""
    out_path = ART_DIR / f"nce_{no}.svg"
    if out_path.exists():
        return no, True, "skip（已存在，断点续跑）"

    last_err = ""
    for attempt in range(1 + MAX_RETRIES):
        try:
            svg = call_zhipu(title, no, api_key)
            validate_svg(svg)
            svg = normalize_svg(svg)
            out_path.write_text(svg, encoding="utf-8")
            return no, True, f"ok（第 {attempt + 1} 次尝试）"
        except ET.ParseError as e:
            last_err = f"SVG XML 不可解析: {e}"
        except ValueError as e:
            last_err = str(e)
        except urllib.error.HTTPError as e:
            last_err = f"HTTP {e.code}（key 无效/限流/服务端错误）"
        except Exception as e:  # noqa: BLE001 网络/超时等统一计入重试
            last_err = f"{type(e).__name__}: {e}"
        time.sleep(2 * (attempt + 1))  # 简单退避
    return no, False, f"fail: {last_err}"


def main() -> int:
    parser = argparse.ArgumentParser(description="NCE 276 关课题 SVG 插画生成管线")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 课（0 = 全量）")
    parser.add_argument("--workers", type=int, default=MAX_WORKERS, help="并发数（默认 4）")
    args = parser.parse_args()

    api_key = os.environ.get("NCE_SVG_API_KEY", "").strip()
    if not api_key:
        print("错误：未设置环境变量 NCE_SVG_API_KEY（严禁把 key 写进任何文件）", file=sys.stderr)
        return 1

    lessons = json.loads(INDEX_JSON.read_text(encoding="utf-8"))["lessons"]
    if args.limit > 0:
        lessons = lessons[: args.limit]
    print(f"共 {len(lessons)} 课，输出目录 {ART_DIR}，并发 {args.workers}")

    ART_DIR.mkdir(parents=True, exist_ok=True)
    ok = fail = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(generate_one, l["no"], l["title"], api_key): l["no"] for l in lessons}
        for fut in concurrent.futures.as_completed(futures):
            no, success, msg = fut.result()
            print(f"[nce_{no}] {msg}")
            ok += 1 if success else 0
            fail += 0 if success else 1
    print(f"完成：成功/跳过 {ok}，失败 {fail}")
    return 0 if fail == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
