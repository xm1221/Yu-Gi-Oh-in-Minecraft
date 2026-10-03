"""
cards.cdb -> 数据包文件（cards.bin + texts.bin）

为什么要转：Java 侧要在启动时把整张卡表一次性推进 native 内存缓存。
若在运行时读 SQLite 就得给 mod 塞一个原生 SQLite 依赖（约 12 MB，且跨平台打包麻烦），
而实际只需要一次性顺序读。转成定长记录后，Java 侧是一次 read + 一次 memcpy 进 native。

产出两个文件：

  cards.bin   给引擎用的 card_data（80 字节结构体），映射规则照官方
              DataManager（data_manager.cpp:40-71），逐字对齐。
  texts.bin   给人看的卡名与卡文，Java 侧按需惰性解码（不在启动时造 15019 个 String）。

用法:  python mkdatapack.py [cards.cdb] [输出目录]
"""
import ctypes as C
import os
import sqlite3
import struct
import sys

TYPE_LINK = 0x4000000

DEFAULT_CDB = r"D:\MyCardLibrary\ygopro\cards.cdb"
# 产物一律落在 local-data/ 下（该目录已被 .gitignore 挡掉）。
# 早先默认是「脚本所在目录」，脚本挪进 tools/ 之后那会把生成物写进版本控制，故改为显式定位。
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(REPO_ROOT, "local-data", "datapack")

CARDS_MAGIC = b"YGOMCCD1"
TEXTS_MAGIC = b"YGOMCTX1"
_VERSION = 1


class CardData(C.Structure):
    """与内核 card_data.h 逐字段对应，恰好 80 字节。"""
    _fields_ = [
        ("code", C.c_uint32), ("alias", C.c_uint32),
        ("setcode", C.c_uint16 * 16),
        ("type", C.c_uint32), ("level", C.c_uint32),
        ("attribute", C.c_uint32), ("race", C.c_uint32),
        ("attack", C.c_int32), ("defense", C.c_int32),
        ("lscale", C.c_uint32), ("rscale", C.c_uint32),
        ("link_marker", C.c_uint32), ("rule_code", C.c_uint32),
    ]


assert C.sizeof(CardData) == 80, C.sizeof(CardData)


def write_setcode(cd, setcode):
    """官方 write_setcode：64 位按 16 位一组写进 u16[16]，跳过 0 组。"""
    v = setcode & ((1 << 64) - 1)
    i = 0
    while v and i < 16:
        if v & 0xFFFF:
            cd.setcode[i] = v & 0xFFFF
            i += 1
        v >>= 16


def connect(cdb):
    return sqlite3.connect("file:%s?mode=ro" % cdb.replace("\\", "/"), uri=True)


def build_cards(con, out_path):
    rows = list(con.execute(
        "select id,ot,alias,setcode,type,atk,def,level,race,attribute,category "
        "from datas order by id"))
    if not rows:
        raise SystemExit("cards.cdb 的 datas 表没有数据")

    with open(out_path, "wb") as f:
        f.write(CARDS_MAGIC)
        f.write(struct.pack("<III", _VERSION, len(rows), 84))  # 84 = 4 字节卡号 + 80 字节结构体
        for (cid, ot, alias, setcode, typ, atk, dfn, lvl, race, attr, cat) in rows:
            cd = CardData()
            cd.code = cid & 0xFFFFFFFF
            cd.alias = (alias or 0) & 0xFFFFFFFF
            write_setcode(cd, setcode)
            cd.type = typ & 0xFFFFFFFF
            cd.attack = atk
            cd.defense = dfn
            if typ & TYPE_LINK:
                cd.link_marker = dfn
                cd.defense = 0
            else:
                cd.link_marker = 0
            cd.level = lvl & 0xFF
            cd.lscale = (lvl >> 24) & 0xFF
            cd.rscale = (lvl >> 16) & 0xFF
            cd.race = race & 0xFFFFFFFF
            cd.attribute = attr & 0xFFFFFFFF
            # rule_code / alias 的官方改写规则（data_manager.cpp:63-97）暂时不做，
            # 先用原始 alias。这一项影响「同卡异画/异判」的取码，M1 前补齐。
            cd.rule_code = 0
            f.write(struct.pack("<I", cid & 0xFFFFFFFF))
            f.write(bytes(C.string_at(C.byref(cd), 80)))
    return len(rows)


def build_texts(con, out_path):
    rows = list(con.execute("select id, name, desc from texts order by id"))
    if not rows:
        raise SystemExit("cards.cdb 的 texts 表没有数据")

    index = bytearray()
    pool = bytearray()
    empty_desc = 0

    for cid, name, desc in rows:
        name = (name or "")
        desc = (desc or "")
        if not desc.strip():
            empty_desc += 1
        # 统一换行：库里存的是 CRLF，渲染端自己会按 \n 断行，先归一化省掉一类 bug
        desc = desc.replace("\r\n", "\n").replace("\r", "\n")

        nb = name.encode("utf-8")
        db = desc.encode("utf-8")
        noff = len(pool)
        pool += nb
        doff = len(pool)
        pool += db
        index += struct.pack("<IIIII", cid & 0xFFFFFFFF, noff, len(nb), doff, len(db))

    # 头部 28 字节 = magic(8) + version/count/indexOffset/poolOffset/poolBytes(5×4)
    header = len(TEXTS_MAGIC) + 4 * 5
    pool_off = header + len(index)
    with open(out_path, "wb") as f:
        f.write(TEXTS_MAGIC)
        f.write(struct.pack("<IIIII", _VERSION, len(rows), header, pool_off, len(pool)))
        f.write(index)
        f.write(pool)

    return len(rows), empty_desc


if __name__ == "__main__":
    cdb = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CDB
    outdir = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT

    con = connect(cdb)
    cards_ids = [r[0] for r in con.execute("select id from datas order by id")]
    texts_ids = [r[0] for r in con.execute("select id from texts order by id")]
    con.close()
    if cards_ids != texts_ids:
        only_d = sorted(set(cards_ids) - set(texts_ids))
        only_t = sorted(set(texts_ids) - set(cards_ids))
        raise SystemExit("datas 与 texts 的 id 集合不一致：仅 datas %s，仅 texts %s"
                         % (only_d[:10], only_t[:10]))

    con = connect(cdb)
    cp = os.path.join(outdir, "cards.bin")
    tp = os.path.join(outdir, "texts.bin")
    os.makedirs(outdir, exist_ok=True)
    n_cards = build_cards(con, cp)
    n_texts, empty_desc = build_texts(con, tp)
    con.close()

    print("cards.bin : %d 张卡, %d 字节" % (n_cards, os.path.getsize(cp)))
    print("texts.bin : %d 条文本, %d 字节, 其中卡文为空 %d 条"
          % (n_texts, os.path.getsize(tp), empty_desc))
