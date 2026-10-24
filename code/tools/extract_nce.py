#!/usr/bin/env python3
"""NCE 教材资源提取脚本（可复跑）。

从 deliverables/software-company/NCE-main.zip 提取：
  - static/data.json  → app/src/main/assets/nce/data.json（原始索引，留档）
  - NCE1~4/*.lrc      → app/src/main/assets/nce/NCE{book}/nce{book}_{seq:03d}.lrc
  - NCE1~4/*.mp3      → app/src/main/assets/nce/NCE{book}/nce{book}_{seq:03d}.mp3

文件重命名原因：原始文件名含全角－、&、空格等，Android assets 路径访问存在
兼容风险；统一改为安全 ASCII 名，顺序信息由本脚本生成的 index.json 承载：
  assets/nce/index.json = {"lessons": [{"no","book","lessonInBook","title","lrc","mp3"},...]}

用法（工程根目录运行）：
  python tools/extract_nce.py [--zip <zip路径>]
默认 zip 路径：../deliverables/software-company/NCE-main.zip（相对本脚本）
"""
import json
import re
import sys
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ZIP = PROJECT_ROOT.parent / "deliverables" / "software-company" / "NCE-main.zip"
ASSETS_OUT = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "nce"


def main() -> int:
    zip_path = Path(sys.argv[sys.argv.index("--zip") + 1]) if "--zip" in sys.argv else DEFAULT_ZIP
    if not zip_path.exists():
        print(f"[ERROR] zip 不存在: {zip_path}")
        return 1

    ASSETS_OUT.mkdir(parents=True, exist_ok=True)
    z = zipfile.ZipFile(zip_path)
    names = set(z.namelist())

    # 1) data.json 留档
    data = json.loads(z.read("NCE-main/static/data.json").decode("utf-8"))
    (ASSETS_OUT / "data.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")

    # 2) 逐册逐课提取 lrc + mp3，重命名为安全文件名
    lessons = []
    no = 0
    total_mp3_bytes = 0
    for book in ["1", "2", "3", "4"]:
        book_dir = ASSETS_OUT / f"NCE{book}"
        book_dir.mkdir(exist_ok=True)
        for seq, lesson in enumerate(data[book], start=1):
            no += 1
            src_base = f"NCE-main/NCE{book}/{lesson['filename']}"
            dst_base = f"nce{book}_{seq:03d}"
            lrc_src, mp3_src = f"{src_base}.lrc", f"{src_base}.mp3"
            if lrc_src not in names:
                print(f"[WARN] 缺少 lrc: {lrc_src}")
                continue
            if mp3_src not in names:
                print(f"[WARN] 缺少 mp3: {mp3_src}")
            # lrc
            (book_dir / f"{dst_base}.lrc").write_bytes(z.read(lrc_src))
            # mp3（存在才写；统计体积）
            if mp3_src in names:
                payload = z.read(mp3_src)
                (book_dir / f"{dst_base}.mp3").write_bytes(payload)
                total_mp3_bytes += len(payload)
            lessons.append({
                "no": no,
                "book": int(book),
                "lessonInBook": seq,
                "title": lesson["title"],
                "lrc": f"NCE{book}/{dst_base}.lrc",
                "mp3": f"NCE{book}/{dst_base}.mp3" if mp3_src in names else "",
            })

    # 3) index.json（统一关卡顺序 = 本脚本生成的 lessons 顺序）
    (ASSETS_OUT / "index.json").write_text(
        json.dumps({"lessons": lessons}, ensure_ascii=False, indent=1), encoding="utf-8"
    )

    print(f"[OK] 提取完成：{len(lessons)} 课")
    print(f"[OK] mp3 总体积: {total_mp3_bytes / 1024 / 1024:.1f} MB")
    print(f"[OK] 输出目录: {ASSETS_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
