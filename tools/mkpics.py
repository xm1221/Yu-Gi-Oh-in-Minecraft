"""
pics/*.jpg -> pics.bin（卡图数据包）

## 为什么要转

原始卡图是 15,017 个 400x580 的 JPEG，共 1.13 GB。

1. **太大**：缩到 200x290 后约 295 MB（实测）。JPEG 是必须的——同尺寸 PNG 要 1569 MB。
2. **15017 个散文件太碎**：随机读一张要在 Windows 上翻目录。打成一个文件后，
   按卡号二分查到偏移，直接 seek 读那一段，不碰其它任何数据。

产物**不进版本控制**，落在 `local-data/datapack/`，与 cards.bin / texts.bin 并列。

## 两档尺寸

| 档位 | 尺寸 | 用途 |
|---|---|---|
| 0 FULL | 200x290 q85 | 对局界面里 1:1 显示、卡牌详情大图 |
| 1 ICON | 64x93 q78 | 物品栏图标；世界里的小尺寸绘制 |

物品栏里一张卡最多占一两格，200x290 的图纯属浪费显存与解码时间，所以单独再压一档。
两档都按「宽度取整、高度按 580/400 比例」算，保持卡面比例不失真。

## 文件格式（与 Java 侧 CardImageDb 严格对应）

```
0   magic "YGOMCPIC" (8)
8   u32 version = 1
12  u32 count          卡图张数
16  u32 tierCount      档位数量
20  u32 entriesOffset  = 32
24  u32 dataOffset     = 32 + count * (4 + tierCount * 8)
28  u32 dataBytes
entries: count 项，每项 (4 + tierCount*8) 字节，**按卡号严格升序**：
    u32 code
    每档 { u32 offset, u32 length }   offset 相对 dataOffset
data:    各档 JPEG 字节首尾拼接
```

卡号升序是为了 Java 侧能二分查表。某张卡缺图时该档 `length = 0`，
渲染端据此回退到通用卡背。

用法:
    python mkpics.py [pics 目录] [输出目录] [--limit N] [--workers N]
"""
import argparse
import io
import os
import struct
import sys
import time
from concurrent.futures import ProcessPoolExecutor

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

MAGIC = b"YGOMCPIC"
_VERSION = 1
HEADER_BYTES = 32

CARD_W, CARD_H = 400, 580          # 原始尺寸，用于算比例

# (档位名, 宽, 高, JPEG 质量)
TIERS = [
    ("FULL", 200, 290, 85),
    ("ICON", 64, 93, 78),
]

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PICS = r"D:\MyCardLibrary\ygopro\pics"
DEFAULT_OUT = os.path.join(REPO_ROOT, "local-data", "datapack")

# 传给子进程的全局，避免每张图都 pickle 一遍配置（Windows 是 spawn，会很痛）
_TIERS = None


def _init_worker(tiers):
    global _TIERS
    _TIERS = tiers


def encode_card(job):
    """把一张卡编码成各档 JPEG 字节。返回 (code, [bytes, ...])；缺图返回 None。"""
    code, path = job
    try:
        src = Image.open(path)
        # draft 让 libjpeg 在 DCT 域直接按比例降采样，比全解码后再缩快好几倍。
        # 注意它只能按 1/1,1/2,1/4,1/8 缩，所以之后通常还要补一次 resize。
        src.draft("RGB", (_TIERS[0][1], _TIERS[0][2]))
        src = src.convert("RGB")
    except Exception as e:                                  # 坏图不该中断整批
        return code, None, str(e)

    out = []
    for _name, w, h, q in _TIERS:
        im = src
        if im.size != (w, h):
            im = im.resize((w, h), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=q, optimize=True, progressive=False)
        out.append(buf.getvalue())
    return code, out, None


def find_pics(pics_dir):
    """扫描卡图目录，返回 [(code, path)]，按卡号升序。文件名就是卡号。"""
    jobs = []
    skipped = []
    for name in os.listdir(pics_dir):
        stem, ext = os.path.splitext(name)
        if ext.lower() not in (".jpg", ".jpeg", ".png"):
            continue
        try:
            code = int(stem)
        except ValueError:
            skipped.append(name)                            # 例如 -1.jpg 之类的特殊名
            continue
        jobs.append((code, os.path.join(pics_dir, name)))
    jobs.sort()
    return jobs, skipped


def build(pics_dir, out_dir, limit=None, workers=None):
    jobs, skipped = find_pics(pics_dir)
    if limit:
        jobs = jobs[:limit]
    if not jobs:
        sys.exit("在 %s 里没找到任何卡图" % pics_dir)

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "pics.bin")

    tier_count = len(TIERS)
    entry_bytes = 4 + tier_count * 8
    count = len(jobs)
    entries_offset = HEADER_BYTES
    data_offset = entries_offset + count * entry_bytes

    if workers is None:
        workers = max(1, (os.cpu_count() or 4) - 1)

    print("卡图 %d 张，档位 %s，%d 进程" %
          (count, " / ".join("%s %dx%d q%d" % t for t in TIERS), workers))
    if skipped:
        print("跳过非数字文件名 %d 个：%s" % (len(skipped), skipped[:5]))

    t0 = time.time()
    entries = {}                      # code -> [off, len, off, len, ...]
    data_bytes = 0
    failed = []
    done = 0

    with open(out_path, "wb") as f:
        # 占位头部，最后回填 dataBytes
        f.write(MAGIC)
        f.write(struct.pack("<IIIIII", _VERSION, count, tier_count,
                            entries_offset, data_offset, 0))
        # 索引区先占位，数据区从 data_offset 开始
        f.seek(data_offset)

        # 数据按「完成的先后」顺序写，顺序无所谓——索引里存的是绝对偏移。
        # 这样可以把内存占用压在 1 MB 量级（只留索引），不必把 15k 张图全攒在内存里。
        with ProcessPoolExecutor(max_workers=workers, initializer=_init_worker,
                                 initargs=(TIERS,)) as ex:
            for code, blobs, err in ex.map(encode_card, jobs, chunksize=32):
                done += 1
                if err is not None:
                    failed.append((code, err))
                    entries[code] = [0] * (tier_count * 2)
                else:
                    rec = []
                    for b in blobs:
                        rec.append(data_bytes)
                        rec.append(len(b))
                        f.write(b)
                        data_bytes += len(b)
                    entries[code] = rec
                if done % 2000 == 0 or done == count:
                    el = time.time() - t0
                    print("  %5d/%d  %.0f%%  已写 %.0f MB  用时 %.0fs  预计总 %.0fs"
                          % (done, count, done * 100.0 / count, data_bytes / 1048576,
                             el, el * count / done), flush=True)

        # 回填 dataBytes
        f.seek(28)
        f.write(struct.pack("<I", data_bytes))

        # 写索引（严格按卡号升序，Java 侧要二分）
        f.seek(entries_offset)
        for code in sorted(entries):
            f.write(struct.pack("<I", code))
            f.write(struct.pack("<%dI" % (tier_count * 2), *entries[code]))

    total = os.path.getsize(out_path)
    print()
    print("已写出 %s" % out_path)
    print("  %d 张, 数据区 %.0f MB, 文件共 %.0f MB, 耗时 %.0fs"
          % (count, data_bytes / 1048576, total / 1048576, time.time() - t0))
    for i, (name, w, h, q) in enumerate(TIERS):
        s = sum(entries[c][i * 2 + 1] for c in entries)
        print("  档 %d %-4s %dx%d q%d: 合计 %.0f MB, 平均 %.1f KB"
              % (i, name, w, h, q, s / 1048576, s / max(1, count) / 1024))
    missing = [c for c in entries if entries[c][3] == 0]
    if missing:
        print("  缺图 %d 张（渲染端会回退到通用卡背）：%s%s"
              % (len(missing), missing[:8], " ..." if len(missing) > 8 else ""))
    if failed:
        print("  编码失败 %d 张：%s" % (len(failed), failed[:5]))

    # 卡背单独放一个小文件，不塞进 pics.bin。
    #
    # 理由：pics.bin 有 334 MB，为了一张 130 KB 的卡背把它整个重打一遍不值得；
    # 而卡背是「一张图一个用途」，没有按卡号索引的需求。
    # 来源是用户自己 ygopro 目录下的 textures/cover.jpg —— 那是 KONAMI 的美术，
    # 和卡图一样【不随模组分发】，只从本地数据包读（见 THIRD_PARTY_NOTICES.md）。
    cover = os.path.join(os.path.dirname(os.path.abspath(pics_dir)),
                         "textures", "cover.jpg")
    back_out = os.path.join(out_dir, "back.jpg")
    if os.path.isfile(cover):
        with open(cover, "rb") as src, open(back_out, "wb") as dst:
            dst.write(src.read())
        print("  卡背 %s -> back.jpg (%.0f KB)"
              % (cover, os.path.getsize(back_out) / 1024))
    else:
        print("  [注意] 没找到卡背 %s" % cover)
        print("         没有组件或查不到的卡会退化成占位框而不是卡背")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="卡图打包成 pics.bin")
    ap.add_argument("pics_dir", nargs="?", default=DEFAULT_PICS)
    ap.add_argument("out_dir", nargs="?", default=DEFAULT_OUT)
    ap.add_argument("--limit", type=int, default=None, help="只处理前 N 张（试跑用）")
    ap.add_argument("--workers", type=int, default=None)
    a = ap.parse_args()
    build(a.pics_dir, a.out_dir, a.limit, a.workers)
