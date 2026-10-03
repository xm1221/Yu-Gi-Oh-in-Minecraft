"""
生成非卡牌物品/方块的占位贴图（16x16 PNG）

这是**占位资源**，不是最终美术。目的是让模组立刻能跑起来看到东西，
并且每个物品一眼能区分。用户后续直接用同名 png 覆盖即可，不必改任何代码。

卡牌本身的贴图不在这里——卡面由自定义渲染器从数据包的 pics.bin 按需解码绘制。
这里生成的 `card.png` 只是「卡背」，作为物品模型的基础贴图与渲染器的兜底。

用法:  python mkplaceholdertextures.py
"""
import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO_ROOT, "common", "src", "main", "resources", "assets", "ygomc")
ITEM_DIR = os.path.join(RES, "textures", "item")
BLOCK_DIR = os.path.join(RES, "textures", "block")

S = 16          # 原版贴图惯例尺寸
T = (0, 0, 0, 0)


def new():
    return Image.new("RGBA", (S, S), T)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    return path


def card_back():
    """卡背：深靛底 + 金边 + 中央菱形。"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, S - 1, S - 1], fill=(43, 43, 74, 255))
    d.rectangle([0, 0, S - 1, S - 1], outline=(200, 162, 74, 255))
    d.rectangle([2, 2, S - 3, S - 3], outline=(90, 90, 140, 255))
    # 中央菱形
    cx = cy = (S - 1) / 2
    for y in range(S):
        for x in range(S):
            if abs(x - cx) + abs(y - cy) <= 4.5:
                img.putpixel((x, y), (110, 123, 200, 255))
    # 四角小饰点
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        d.point((x, y), fill=(200, 162, 74, 255))
    return img


def duel_disk():
    """决斗盘：环形盘面 + 中心轴。"""
    img = new()
    d = ImageDraw.Draw(img)
    d.ellipse([1, 1, S - 2, S - 2], fill=(70, 78, 96, 255), outline=(150, 160, 180, 255))
    d.ellipse([4, 4, S - 5, S - 5], fill=(38, 42, 52, 255))
    d.ellipse([6, 6, S - 7, S - 7], fill=(120, 140, 200, 255))
    # 盘面上的四个卡位指示
    for (x, y) in ((8, 2), (8, 13), (2, 8), (13, 8)):
        d.point((x, y), fill=(230, 230, 240, 255))
    return img


def deck_box():
    """卡组盒：棕盒 + 深色盒盖 + 金色搭扣。"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle([2, 3, 13, 14], fill=(122, 84, 52, 255), outline=(72, 48, 30, 255))
    d.rectangle([1, 2, 14, 5], fill=(88, 60, 38, 255), outline=(52, 34, 20, 255))
    d.rectangle([6, 5, 9, 8], fill=(200, 162, 74, 255), outline=(140, 110, 40, 255))
    # 盒侧的一道高光
    d.line([3, 7, 3, 13], fill=(150, 106, 68, 255))
    return img


def card_pack():
    """卡包：竖长包装 + 斜向锡箔高光。"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle([3, 1, 12, 14], fill=(60, 90, 160, 255), outline=(30, 46, 92, 255))
    d.rectangle([3, 1, 12, 4], fill=(34, 52, 100, 255))
    # 斜向高光
    for i in range(6):
        d.line([3 + i, 13, 5 + i, 5], fill=(150, 190, 240, 160))
    d.rectangle([5, 7, 10, 10], fill=(220, 200, 90, 255), outline=(150, 130, 40, 255))
    return img


def card_binder():
    """卡册：暗红封面 + 左侧金属环。"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle([1, 1, 14, 14], fill=(140, 44, 48, 255), outline=(80, 22, 26, 255))
    d.rectangle([4, 1, 14, 14], fill=(168, 56, 60, 255))
    d.rectangle([1, 1, 3, 14], fill=(96, 30, 34, 255))
    for y in (4, 8, 12):
        d.ellipse([1, y - 1, 3, y + 1], fill=(190, 195, 205, 255),
                  outline=(120, 126, 136, 255))
    d.rectangle([6, 6, 12, 11], fill=(210, 190, 150, 255), outline=(150, 130, 96, 255))
    return img


def duel_table():
    """决斗台方块：绿绒台面 + 深色边 + 中央纹样。"""
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, S - 1, S - 1], fill=(34, 92, 58, 255))
    d.rectangle([0, 0, S - 1, S - 1], outline=(20, 58, 36, 255))
    d.rectangle([1, 1, S - 2, S - 2], outline=(52, 120, 78, 255))
    # 中央分区暗示（卡位网格）
    d.rectangle([4, 4, 11, 11], outline=(70, 150, 100, 255))
    d.line([7, 4, 7, 11], fill=(70, 150, 100, 255))
    d.line([4, 7, 11, 7], fill=(70, 150, 100, 255))
    return img


def main():
    items = {
        "card": card_back(),          # 卡背，作为物品模型基础贴图与渲染器兜底
        "duel_disk": duel_disk(),
        "deck_box": deck_box(),
        "card_pack": card_pack(),
        "card_binder": card_binder(),
    }
    blocks = {
        "duel_table": duel_table(),
    }

    print("物品贴图 ->", ITEM_DIR)
    for name, img in items.items():
        p = save(img, os.path.join(ITEM_DIR, name + ".png"))
        print("   %-14s %6d B" % (name + ".png", os.path.getsize(p)))

    print("方块贴图 ->", BLOCK_DIR)
    for name, img in blocks.items():
        p = save(img, os.path.join(BLOCK_DIR, name + ".png"))
        print("   %-14s %6d B" % (name + ".png", os.path.getsize(p)))

    print()
    print("这些是占位资源，直接用同名 png 覆盖即可，不需要改代码。")


if __name__ == "__main__":
    main()
