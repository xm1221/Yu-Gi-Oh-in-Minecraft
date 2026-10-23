"""
从 YGOJSON 生成卡包数据包：card_set / booster / booster_box。

只取带 jp 地区的系列（OCG）。没有抽法数据的系列只写 card_set，不写 booster。
产物落在 local-data/sets/，是一份可以直接放进存档 datapacks/ 的数据包，不入库。

用法和产物说明见 tools/sets.md。

  python mksets.py --from ygojson
  python mksets.py --from ygojson --zip path/to/individual.zip
"""
import argparse
import hashlib
import json
import math
import os
import re
import sys
import urllib.request
import zipfile

# 与 Java PackColors.PALETTE 逐项相同。按卡号前缀的 SHA-256 前 4 字节取模。
PACK_COLORS = (
    "#1B3A4B", "#3D2B1F", "#1F3D2B", "#3B1F3D",
    "#2C3E50", "#4A2C2A", "#1E3A2F", "#2A2E4A",
    "#3E2A1E", "#243B2F", "#3A2430", "#1F2A44",
    "#3F2D1A", "#1A3330", "#33241A", "#2A1F3A",
)

ZIP_URL = "https://github.com/iconmaster5326/YGOJSON/releases/download/v1/individual.zip"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "local-data", "sets")

# YGOJSON 的罕贵比模组的五档细。对得上的归到最近的一档；表外的记警告并当成 common。
RARITY = {
    "common": "common", "shortprint": "common", "kccommon": "common",
    "rare": "rare", "rare-red": "rare", "rare-copper": "rare", "rare-green": "rare",
    "rare-wedgewood": "rare", "rare-blue": "rare", "rare-purple": "rare",
    "kcrare": "rare", "parallel": "rare", "commonparallel": "rare", "rareparallel": "rare",
    "super": "super", "superparallel": "super", "kcsuper": "super", "milleniumsuper": "super",
    "ultra": "ultra", "ultra-green": "ultra", "ultra-blue": "ultra", "ultra-purple": "ultra",
    "ultimate": "ultra", "ultraparallel": "ultra", "kcultra": "ultra", "milleniumultra": "ultra",
    "millenium": "ultra", "gold": "ultra", "premiumgold": "ultra", "platinum": "ultra",
    "starfoil": "ultra", "mosaic": "ultra", "shatterfoil": "ultra", "collectors": "ultra",
    "secret": "secret", "secret-red": "secret", "secret-blue": "secret", "ultrasecret": "secret",
    "prismaticsecret": "secret", "ghost": "secret", "10000secret": "secret", "20thsecret": "secret",
    "25thsecret": "secret", "extrasecret": "secret", "extrasecretparallel": "secret",
    "goldghost": "secret", "goldsecret": "secret", "ghostparallel": "secret",
    "platinumsecret": "secret", "secretparallel": "secret", "starlight": "secret",
    "pharaohs": "secret", "kcsecret": "secret", "milleniumsecret": "secret", "milleniumgold": "secret",
    "dtpc": "common", "dtpsp": "super", "dtrpr": "rare", "dtspr": "super", "dtupr": "ultra",
    "dtscpr": "secret",
}
FALLBACK = ["secret", "ultra", "super", "rare", "common"]


def slug(text):
    text = text.lower()
    text = re.sub(r"[^a-z0-9]+", "_", text).strip("_")
    return text[:48] or "set"


def map_rarity(name, warnings):
    mapped = RARITY.get(name)
    if mapped is None:
        warnings.append(name)
        return "common"
    return mapped


def read_json(zf, name):
    with zf.open(name) as handle:
        return json.load(handle)


def find(zf, suffix):
    """zip 里同一份文件可能在 individual/ 下，也可能在根上。"""
    for name in zf.namelist():
        if name.endswith(suffix) and not name.endswith("/"):
            return name
    return None


def passwords(zf):
    listing = find(zf, "/cards.json") or find(zf, "cards.json")
    ids = read_json(zf, listing)
    root = listing[: -len("cards.json")]
    out = {}
    for card_id in ids:
        data = read_json(zf, root + "cards/" + card_id + ".json")
        for password in data.get("passwords") or []:
            if str(password).isdigit():
                out[card_id] = int(password)
                break
    return out


def weights_of(entries, warnings):
    """YGOJSON 的 chance 是 1:X。权重取倒数，再通分到整数。"""
    parsed = []
    for entry in entries:
        chance = int(entry.get("chance") or 1)
        if chance <= 0:
            chance = 1
        rarities = entry.get("rarities") or ["common"]
        parsed.append((chance, rarities))
    if not parsed:
        return {"common": 1}
    scale = 1
    for chance, _ in parsed:
        scale = math.lcm(scale, chance)
    totals = {}
    for chance, rarities in parsed:
        share = max(1, (scale // chance) // len(rarities))
        for rarity in rarities:
            key = map_rarity(rarity, warnings)
            totals[key] = totals.get(key, 0) + share
    return totals


def slot_json(slot, warnings):
    # YGOJSON 用 qty 表示这个卡位出几张。没有这个字段时才是 1。
    count = int(slot.get("qty") or slot.get("count") or 1)
    table = weights_of(slot.get("rarity") or [], warnings)
    if len(table) == 1 and next(iter(table.values())) == 1:
        rarity = next(iter(table))
    else:
        rarity = table
    return {"count": max(1, count), "rarity": rarity}


def adapt_tcg_sheet(name, slots):
    """日文系列上挂的常是 TCG 的九张表。罕贵比例留下，张数收成 OCG 的五张。"""
    if not (name or "").startswith("TCG"):
        return slots
    slots = [dict(slot) for slot in slots]
    extra = sum(slot["count"] for slot in slots) - 5
    if extra <= 0:
        return slots
    order = sorted(range(len(slots)), key=lambda i: slots[i]["count"], reverse=True)
    for index in order:
        room = slots[index]["count"] - 1
        if room <= 0:
            continue
        take = min(extra, room)
        slots[index]["count"] -= take
        extra -= take
        if extra <= 0:
            break
    return [slot for slot in slots if slot["count"] > 0]


def whole_set_pulls(cards):
    """预组：每个稀有度的池有几张，这个卡位就抽几张，同包不重复，等于整盒都给。"""
    slots = []
    for rarity in FALLBACK:
        count = len(cards.get(rarity) or [])
        if count:
            slots.append({"count": count, "rarity": rarity})
    if not slots:
        return None
    return [{"id": "deck", "weight": 1, "slots": slots}]


def default_ocg_pulls(cards):
    """没有抽法时：五张，每张按「这个稀有度有多少张印刷」抽。

    不能因为池里只剩一张秘稀，就把那一档写成必出。那会把资料缺口变成 100% 秘稀。
    """
    pools = {rarity: list(cards.get(rarity) or []) for rarity in FALLBACK}
    total = sum(len(pool) for pool in pools.values())
    if total <= 5:
        return whole_set_pulls(cards)
    weights = {rarity: len(pools[rarity]) for rarity in FALLBACK if pools[rarity]}
    rarity = next(iter(weights)) if len(weights) == 1 else weights
    return [{"id": "pack", "weight": 1, "slots": [{"count": 5, "rarity": rarity}]}]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(obj, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def pack_color(prefix):
    key = prefix if prefix else "ygomc"
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    return PACK_COLORS[int.from_bytes(digest[:4], "big") % len(PACK_COLORS)]


def main():
    parser = argparse.ArgumentParser(description="从 YGOJSON 生成 ygomc 卡包数据包")
    parser.add_argument("--from", dest="source", required=True, choices=["ygojson"])
    parser.add_argument("--zip", dest="zip_path", help="已下载的 individual.zip")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    zip_path = args.zip_path or os.path.join(REPO, "local-data", "ygojson", "individual.zip")
    if not os.path.exists(zip_path):
        os.makedirs(os.path.dirname(zip_path), exist_ok=True)
        print("下载", ZIP_URL)
        request = urllib.request.Request(ZIP_URL, headers={"User-Agent": "ygomc-mksets"})
        with urllib.request.urlopen(request, timeout=120) as response, open(zip_path, "wb") as out:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        print("保存", zip_path, os.path.getsize(zip_path), "B")

    unknown = []
    sets_written = 0
    boosters_written = 0
    boxes_written = 0
    from_sheet = 0
    adapted = 0
    decks = 0
    defaulted = 0
    no_password = 0
    booster_ids = {}
    booster_colors = {}

    with zipfile.ZipFile(zip_path) as zf:
        codes = passwords(zf)
        print("卡号", len(codes))
        set_list = find(zf, "/sets.json") or find(zf, "sets.json")
        set_root = set_list[: -len("sets.json")]
        dist_root_name = find(zf, "/distributions.json") or find(zf, "distributions.json")
        dist_root = dist_root_name[: -len("distributions.json")] if dist_root_name else None

        data_root = os.path.join(args.out, "data", "ygomc", "ygomc")
        for set_id in read_json(zf, set_list):
            doc = read_json(zf, set_root + "sets/" + set_id + ".json")
            if "jp" not in (doc.get("locales") or {}):
                continue
            locale = doc["locales"]["jp"]
            file_id = slug(locale.get("prefix") or (doc.get("name") or {}).get("en") or set_id)
            base = file_id
            n = 2
            while os.path.exists(os.path.join(data_root, "card_set", file_id + ".json")):
                file_id = base + "_" + str(n)
                n += 1

            buckets = {rarity: {} for rarity in FALLBACK}
            for block in doc.get("contents") or []:
                locales = block.get("locales") or []
                if locales and "jp" not in locales:
                    continue
                for card in block.get("cards") or []:
                    password = codes.get(card.get("card"))
                    if password is None:
                        no_password += 1
                        continue
                    rarity = map_rarity(card.get("rarity") or "common", unknown)
                    qty = int(card.get("qty") or 1)
                    buckets[rarity][password] = buckets[rarity].get(password, 0) + qty
            cards = {rarity: sorted(pool) for rarity, pool in buckets.items() if pool}
            if not cards:
                continue
            name = (doc.get("name") or {}).get("ja") or (doc.get("name") or {}).get("en") or file_id
            body = {"name": {"text": name}, "code": locale.get("prefix") or file_id.upper(), "cards": cards}
            if locale.get("date"):
                body["release"] = locale["date"]
            uneven = []
            for rarity, pool in buckets.items():
                for password, qty in pool.items():
                    if qty != 1:
                        uneven.append({"code": password, "rarity": rarity, "weight": qty})
            if uneven:
                body["prints"] = uneven
            write(os.path.join(data_root, "card_set", file_id + ".json"), body)
            sets_written += 1

            dist_id = None
            for block in doc.get("contents") or []:
                locales = block.get("locales") or []
                if locales and "jp" not in locales:
                    continue
                if block.get("distrobution"):
                    dist_id = block["distrobution"]
            pulls = None
            odds_origin = None
            if dist_id == "preconstructed":
                pulls = whole_set_pulls(cards)
                if pulls:
                    decks += 1
                    odds_origin = "preconstructed"
            elif dist_id and dist_root is not None:
                dist_path = dist_root + "distributions/" + dist_id + ".json"
                if dist_path in zf.namelist():
                    dist = read_json(zf, dist_path)
                    slots = []
                    for slot in dist.get("slots") or []:
                        if slot.get("type") not in (None, "pool"):
                            continue
                        slots.append(slot_json(slot, unknown))
                    before = sum(slot["count"] for slot in slots)
                    slots = adapt_tcg_sheet(dist.get("name") or "", slots)
                    if slots:
                        pulls = [{"id": "pack", "weight": 1, "slots": slots}]
                        from_sheet += 1
                        if sum(slot["count"] for slot in slots) != before:
                            adapted += 1
                            odds_origin = "sheet_adapted"
                        else:
                            odds_origin = "sheet"
            if pulls is None:
                pulls = default_ocg_pulls(cards)
                if pulls:
                    defaulted += 1
                    odds_origin = "print_weight"
            if not pulls:
                continue
            prefix = locale.get("prefix") or file_id.upper()
            color = pack_color(prefix)
            display = {"name": {"text": name}, "color": color, "rarity": "common"}
            face = locale.get("image") or ""
            if isinstance(face, str) and face.strip():
                display["face"] = face.strip()
            write(os.path.join(data_root, "booster", file_id + ".json"), {
                "set": "ygomc:" + file_id,
                "display": display,
                "pulls": pulls,
                "odds_origin": odds_origin,
                "duplicates": "avoid_in_pack",
                "fallback": FALLBACK,
            })
            booster_ids[set_id] = file_id
            booster_colors[file_id] = color
            boosters_written += 1

        product_list = find(zf, "/sealedProducts.json") or find(zf, "sealedProducts.json")
        if product_list:
            product_root = product_list[: -len("sealedProducts.json")]
            for product_id in read_json(zf, product_list):
                doc = read_json(zf, product_root + "sealedProducts/" + product_id + ".json")
                if "jp" not in (doc.get("locales") or {}):
                    continue
                packs = []
                for block in doc.get("contents") or []:
                    locales = block.get("locales") or []
                    if locales and "jp" not in locales:
                        continue
                    packs.extend(block.get("packs") or [])
                if len(packs) != 1:
                    continue
                booster_id = booster_ids.get(packs[0].get("set"))
                if booster_id is None:
                    continue
                qty = int(packs[0].get("qty") or 0)
                if qty <= 0:
                    continue
                name = (doc.get("name") or {}).get("ja") or (doc.get("name") or {}).get("en") or booster_id
                write(os.path.join(data_root, "booster_box", booster_id + ".json"), {
                    "booster": "ygomc:" + booster_id,
                    "packs": qty,
                    "mode": "independent",
                    "display": {"name": {"text": name}, "color": booster_colors.get(booster_id) or pack_color(booster_id), "rarity": "uncommon"},
                })
                boxes_written += 1

    write(os.path.join(args.out, "pack.mcmeta"), {"pack": {"pack_format": 48, "description": "ygomc sets"}})
    print("系列", sets_written, "卡包", boosters_written, "盒装", boxes_written)
    print("抽法来自源数据", from_sheet, "（其中 TCG 九张改成 OCG 五张", adapted, "）")
    print("预组，开出全套", decks)
    print("没有抽法，按印刷张数抽五张", defaulted)
    print("没有卡号、未进池的印刷", no_password)
    if unknown:
        print("未识别的罕贵（已按 common 计入）:", ", ".join(sorted(set(unknown))))
    print("输出", args.out)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        sys.exit(str(exc))
