"""
cards.cdb -> 数据包文件（cards.bin + texts.bin）

为什么要转：Java 侧要在启动时把整张卡表一次性推进 native 内存缓存。
若在运行时读 SQLite 就得给 mod 塞一个原生 SQLite 依赖（约 12 MB，且跨平台打包麻烦），
而实际只需要一次性顺序读。转成定长记录后，Java 侧是一次 read + 一次 memcpy 进 native。

产出三个文件：

  cards.bin    给引擎用的 card_data（80 字节结构体），映射规则照官方
               DataManager（data_manager.cpp:40-71），逐字对齐。
  texts.bin    给人看的卡名、卡文与 str1..str16（效果说明文本），
               Java 侧按需惰性解码（不在启动时造 15019 个 String）。
  strings.bin  系统文本（属性/种族/类型/指示物/胜负原因/系列名），来自 strings.conf。

用法:  python mkdatapack.py [cards.cdb] [输出目录] [strings.conf]
        strings.conf 默认取 cards.cdb 同目录下的同名文件。
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
CARDS_VERSION = 1
TEXTS_MAGIC = b"YGOMCTX2"
TEXTS_VERSION = 2
STRINGS_MAGIC = b"YGOMCST1"
STRINGS_VERSION = 1

# description 解码规则（官方 data_manager.cpp:267-278、data_manager.h:21,66）：
#   strCode <= 2047  → strings.conf 的 !system <strCode>
#   否则             → 卡号 = (strCode >> 4) & 0x0fffffff，取该卡 texts 表的 str((strCode & 0xf) + 1)
#
# 这两条不是照抄文档，是用本地数据独立验证过的：
#   · 13575 个 .lua 脚本里 SetDescription(<字面量>) 共 13 个不同取值，全部 <= 2047，
#     且全部存在于 !system（0 个落空）；!system 的 id 范围是 1..1700。
#   · aux.Stringid(code, i) 算出值最小是 40177（= 2511<<4|1），与字面量最大值 1623 之间
#     隔着 38554 的空隙，所以 2047 这个阈值取在空隙里的任何位置都等价。
#   · i → str{i+1} 的映射由卡 39015 定案：脚本里 e1/e2 分别用 i=0/1，
#     而 str1=「特殊召唤」、str2=「改变种族·属性」，两个效果与两条 str 一一对应。
#
# 注意：cdb 的 texts.desc 列存的是【卡文本身】（15017 条全是中文效果文本），不是描述 id，
# 别拿它当 strCode 用。strCode 来自脚本，落到 texts.str1..str16。
MAX_STRING_ID = 0x7ff   # 2047
DESC_COUNT = 16


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
        f.write(struct.pack("<III", CARDS_VERSION, len(rows), 84))  # 84 = 4 字节卡号 + 80 字节结构体
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
    """texts.bin v2：卡名、卡文，外加 str1..str16（效果说明）。

    索引项从 v1 的 20 字节涨到 148 字节，换来的是「发动效果显示『（说明 42）』」这类
    文本层缺口能真正补上。文本仍然【不预先解码】，偏移相对 pool 起点。
    """
    cols = ", ".join(["id", "name", "desc"] + ["str%d" % i for i in range(1, DESC_COUNT + 1)])
    rows = list(con.execute("select %s from texts order by id" % cols))
    if not rows:
        raise SystemExit("cards.cdb 的 texts 表没有数据")

    index = bytearray()
    pool = bytearray()
    empty_desc = 0
    all_empty_str = 0

    for row in rows:
        cid = row[0]
        # 0=name、1=desc、2..17 = str1..str16
        fields = [(row[1] or ""), (row[2] or "")]
        fields += [(row[2 + i] or "") for i in range(1, DESC_COUNT + 1)]
        if not fields[1].strip():
            empty_desc += 1
        if not any(fields[2:]):
            all_empty_str += 1

        entry = bytearray(struct.pack("<I", cid & 0xFFFFFFFF))
        for t in fields:
            # 统一换行：库里存的是 CRLF，渲染端自己会按 \n 断行，先归一化省掉一类 bug。
            # desc 与 str1..str16 一视同仁，否则「卡文断行对、效果说明断行错」很难看出来。
            b = t.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")
            entry += struct.pack("<II", len(pool), len(b))
            pool += b
        index += entry

    # 头部 28 字节 = magic(8) + version/count/indexOffset/poolOffset/poolBytes(5×4)
    header = len(TEXTS_MAGIC) + 4 * 5
    assert header == 28, header
    assert len(index) == len(rows) * (4 + 4 * 4 + DESC_COUNT * 8), "索引项必须恰好 148 字节"
    pool_off = header + len(index)
    with open(out_path, "wb") as f:
        f.write(TEXTS_MAGIC)
        f.write(struct.pack("<IIIII", TEXTS_VERSION, len(rows), header, pool_off, len(pool)))
        f.write(index)
        f.write(pool)

    return len(rows), empty_desc, all_empty_str


def parse_strings_conf(path):
    """解析 strings.conf。返回 (四节字典, 忽略掉的东西)。

    规则照官方（data_manager.cpp:187-216）：
      · 只处理以 '!' 开头的行，其余整行忽略；
      · 四节 !system / !counter / !victory / !setname，其余节忽略；
      · !system 的 id 是【十进制】，其余三节是【十六进制】——
        实际数据里后三节都写作 0x 前缀（!counter 0x1 魔力指示物），
        官方的 %x 能吃这个前缀，Python 的 int(x, 16) 也能；
      · !setname 的文本遇 tab 截断（官方拿 tab 做行内注释），其余三节取到行尾。
        这份数据里 !setname 有 550/598 行带 tab，所以这条例外是必须的、不是理论问题；
      · 文件是 UTF-8。

    重复 id 的取舍：官方 !system 用 emplace（保留首个），!counter/!setname 用 operator[]（后者覆盖）。
    【这份数据四节一个重复 id 都没有（535/114/26/598 全唯一）】，
    所以这条差异在当前数据上无法观测。照抄是为了与官方行为一致，不是因为我验过它。
    计划没写明 !victory 属于哪一类，这里按 operator[] 一族处理。
    """
    sections = ["system", "counter", "victory", "setname"]
    first_wins = ("system",)
    out = {s: {} for s in sections}
    ignored = {}

    def note(k):
        ignored[k] = ignored.get(k, 0) + 1

    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if not line.startswith("!"):
                continue
            parts = line[1:].split(None, 2)
            if len(parts) < 3:
                note(line[:40])
                continue
            name, tok, text = parts[0], parts[1], parts[2]
            if name not in out:
                note("!" + name)
                continue
            try:
                key = int(tok, 10) if name == "system" else int(tok, 16)
            except ValueError:
                note(line[:40])
                continue
            if name == "setname":
                text = text.split("\t")[0]
            if name in first_wins and key in out[name]:
                continue
            out[name][key] = text
    return out, ignored


def build_strings(conf_path, out_path):
    """strings.bin：属性/种族/类型名、指示物名、胜负原因、系列名。

    这一节是「系统文本」的唯一来源。界面上「位 0x40」这类不可读的显示（P11）、
    「可放 N 个」却没有指示物名（P12）、胜负原因说不清，都靠它补齐。
    """
    if not os.path.exists(conf_path):
        raise SystemExit("找不到 strings.conf：%s" % conf_path)
    sec, ignored = parse_strings_conf(conf_path)
    if ignored:
        print("  ! strings.conf 中忽略的行/节: %s" % ignored)

    # 属性/种族/类型的编号必须【齐全】，缺一条就报错退出，不做静默少一条。
    # 理由：少一条的症状是界面上出现「未知」，而那几乎不可能被联想到数据层。
    for label, rng in (("属性 1010..1016", range(1010, 1017)),
                       ("种族 1020..1045", range(1020, 1046)),
                       ("类型 1050..1075", range(1050, 1076))):
        miss = [i for i in rng if i not in sec["system"]]
        if miss:
            raise SystemExit("strings.conf 的 !system 缺 %s：%s" % (label, miss))

    order = ["system", "counter", "victory", "setname"]
    pool = bytearray()
    index = bytearray()
    counts = []
    for name in order:
        ids = sorted(sec[name])
        counts.append(len(ids))
        for key in ids:
            b = sec[name][key].encode("utf-8")
            index += struct.pack("<III", key & 0xFFFFFFFF, len(pool), len(b))
            pool += b

    # 头部 40 字节 = magic(8) + version(4) + 四个 count(4×4) + index/pool 偏移与长度(3×4)
    header = len(STRINGS_MAGIC) + 4 * 8
    assert header == 40, header
    pool_off = header + len(index)
    with open(out_path, "wb") as f:
        f.write(STRINGS_MAGIC)
        f.write(struct.pack("<I", STRINGS_VERSION))
        f.write(struct.pack("<IIII", *counts))
        f.write(struct.pack("<III", header, pool_off, len(pool)))
        f.write(index)
        f.write(pool)

    return counts


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
    sp = os.path.join(outdir, "strings.bin")
    # strings.conf 与 cards.cdb 通常同目录
    conf = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(os.path.abspath(cdb)), "strings.conf")
    os.makedirs(outdir, exist_ok=True)
    n_cards = build_cards(con, cp)
    n_texts, empty_desc, all_empty_str = build_texts(con, tp)
    con.close()
    counts = build_strings(conf, sp)

    print("cards.bin    : %d 张卡, %d 字节（本轮格式未变，不需要重建 native）"
          % (n_cards, os.path.getsize(cp)))
    print("texts.bin v2 : %d 条 / %d 字节（卡文为空 %d 条，str1..str16 全空的卡 %d 张）"
          % (n_texts, os.path.getsize(tp), empty_desc, all_empty_str))
    print("strings.bin  : system=%d counter=%d victory=%d setname=%d, %d 字节"
          % (counts[0], counts[1], counts[2], counts[3], os.path.getsize(sp)))
