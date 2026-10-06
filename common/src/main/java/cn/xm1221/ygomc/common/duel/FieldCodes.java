package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.OcgDuel;

import java.util.ArrayList;
import java.util.List;

/**
 * 把内核 {@code query_field_card} 的原始字节解析成「卡号 + 表示形式 + 叠放数」，
 * 并按【座位】决定哪些卡号可以交给渲染层。
 *
 * <h2>一、字节格式（逐字核过内核源码）</h2>
 *
 * {@code query_field_card} 的整体输出（{@code ocgapi.cpp:263-309}）是一串<b>段</b>：
 * <ul>
 *   <li>怪兽区 / 魔陷区：按 {@code list_mzone} / {@code list_szone} 的<b>槽位顺序</b>，
 *       每个<b>有卡</b>的槽写一段（{@code ocgapi.cpp:270-288}）；
 *       每个<b>空</b>槽会往游标处写一个 {@code LEN_EMPTY}（= 4）但
 *       <b>不推进游标</b>（{@code ocgapi.cpp:275,285} 里 {@code p} 没有 {@code +=}），
 *       所以返回长度里<b>不含</b>空格子——槽位与段的一一对应必须靠
 *       {@code query_field_info} 的占用位反推。本类因此在
 *       {@link #fillZones} 里拿快照的占用位去配对，并对不上一律抛异常。</li>
 *   <li>手牌 / 墓地 / 除外 / 额外 / 卡组：按各自 list 的顺序，<b>一卡一段</b>，
 *       没有空槽的概念（{@code ocgapi.cpp:289-307}）。</li>
 * </ul>
 *
 * <p>每一段的内部布局来自 {@code card::get_infos}（{@code card.cpp:160-389}）：
 * <pre>
 *   0   i32 len    整段字节数（含这 8 字节头）             card.cpp:385-386
 *   4   u32 flag   本段<b>实际写出</b>的字段位             card.cpp:387
 *   8   … 按 flag 的位依次排布（没请求的字段完全不出现）：
 *
 *       QUERY_CODE          (0x1)      u32  卡号                       card.cpp:172-174
 *       QUERY_POSITION      (0x2)      u32  get_public_info_location() card.cpp:175-183
 *       QUERY_ALIAS         (0x4)      u32  get_code()（别名后的卡号）  card.cpp:185-189
 *       QUERY_TYPE          (0x8)      u32                              card.cpp:190-194
 *       QUERY_LEVEL         (0x10)     u32                              card.cpp:195-199
 *       QUERY_RANK          (0x20)     u32                              card.cpp:200-204
 *       QUERY_ATTRIBUTE     (0x40)     u32                              card.cpp:205-209
 *       QUERY_RACE          (0x80)     u32                              card.cpp:210-214
 *       QUERY_ATTACK        (0x100)    i32                              card.cpp:215-218
 *       QUERY_DEFENSE       (0x200)    i32                              card.cpp:219-222
 *       QUERY_BASE_ATTACK   (0x400)    i32                              card.cpp:223-226
 *       QUERY_BASE_DEFENSE  (0x800)    i32                              card.cpp:227-230
 *       QUERY_REASON        (0x1000)   u32                              card.cpp:231-234
 *       QUERY_REASON_CARD   (0x2000)   u32  info location                card.cpp:298-301
 *       QUERY_EQUIP_CARD    (0x4000)   u32  info location                card.cpp:302-309
 *       QUERY_TARGET_CARD   (0x8000)   i32 n + n×u32 info location       card.cpp:310-316
 *       QUERY_OVERLAY_CARD  (0x10000)  i32 n + n×u32 素材卡号            card.cpp:317-322
 *       QUERY_COUNTERS      (0x20000)  i32 n + n×u32 (类型 | 个数&lt;&lt;16)  card.cpp:323-329
 *       QUERY_OWNER         (0x40000)  i32                              card.cpp:330-333
 *       QUERY_STATUS        (0x80000)  u32                              card.cpp:334-342
 *       QUERY_LSCALE        (0x200000) u32                              card.cpp:344-348
 *       QUERY_RSCALE        (0x400000) u32                              card.cpp:349-353
 *       QUERY_LINK          (0x800000) u32 link + u32 link_marker       card.cpp:354-361
 * </pre>
 * 位值见 {@code common.h:230-252}。{@code QUERY_LINK} 写<b>两个</b> u32，
 * 这一点与其它位不同，漏看会整体错位。
 *
 * <p>{@code QUERY_POSITION} 的值是 {@code get_public_info_location()}，其字节序为
 * （{@code card.cpp:390-410}）：
 * <pre>
 *   byte0 = controller, byte1 = location, byte2 = sequence, byte3 = position
 * </pre>
 * 并且<b>当且仅当</b>这张卡在场上且受「公开」效果影响时，才把 {@code POS_REVEAL}(0x80)
 * 或进 byte3（{@code card.cpp:407-408}）。这是内核自己的「这张卡是否公开」信号。
 *
 * <h2>二、可见性：内核<b>不</b>替我们过滤卡号（对任务前提的一处更正）</h2>
 *
 * 任务书说「可见性靠内核自己筛，用正确的 flag 以玩家自己的座位去查」。
 * 通读 {@code card::get_infos}（{@code card.cpp:160-389}）与
 * {@code query_field_card}（{@code ocgapi.cpp:263-309}）后可以确定：
 * <b>内核没有任何可见性判断</b>——它对每个有卡的槽无条件写出 {@code data.code}
 * 或 {@code get_code()}。所以「内核自己筛」只在一种意义上成立：
 * <b>flag 里不带 {@code QUERY_CODE}/{@code QUERY_ALIAS}，内核就根本不会写出卡号</b>。
 * 本类据此分两种情况处理：
 *
 * <ol>
 *   <li><b>整块不可见</b>的（对手手牌 / 对手卡组 / 对手额外卡组、以及自己的卡组）：
 *       传 {@link #FLAG_HIDDEN}，只问表示形式。卡号在内核里<b>从不产生</b>，
 *       结构上泄不出去。</li>
 *   <li><b>混合可见</b>的（对手的怪兽区 / 魔陷区：表侧的公开、里侧的保密）：
 *       一个 flag 表达不了逐格差异，所以卡号会出现在缓冲区里，
 *       必须由本类丢掉。判据只用内核自己给的公开信号：
 *       <pre>隐藏 ⟺ (position &amp; POS_FACEDOWN) != 0 &amp;&amp; (position &amp; POS_REVEAL) == 0</pre>
 *       这也就是官方客户端里 {@code ShouldHideFacedownCode} 的口径。
 *       <b>这是全工程里唯一一处「什么卡片信息可以出网」的判定</b>，
 *       所以它被写成 {@link #visible} 一个方法，便于离线校验对着它验。</li>
 * </ol>
 *
 * <p>自己的牌一律可见（包括自己盖下的里侧卡——玩家当然知道自己盖了什么）。
 *
 * <h2>三、官方 Refresh 的那组 flag，其含义是这组常量的组合</h2>
 * 任务书给出的五个值，与 {@code common.h:230-252} 的位逐位对得上：
 * <pre>
 *   0x1fff = CODE|POSITION|ALIAS|TYPE|LEVEL|RANK|ATTRIBUTE|RACE
 *          | ATTACK|DEFENSE|BASE_ATTACK|BASE_DEFENSE|REASON
 *   0x881fff = 0x1fff | STATUS | LINK                              （怪兽区）
 *   0x681fff = 0x1fff | STATUS | LSCALE | RSCALE                   （魔陷区 / 手牌）
 *   0x081fff = 0x1fff | STATUS                                     （墓地）
 *   0xe81fff = 0x1fff | STATUS | LSCALE | RSCALE | LINK            （额外卡组）
 * </pre>
 * 五个值全都能由 {@code common.h} 的位精确组合出来，所以这组常量是可信的。
 * <b>但没能核实</b>的是这组值在官方客户端的出处（{@code single_duel.h:37-42}）——
 * 那份文件是 ygopro 客户端（GPLv2）的，本地只有 ocgcore，没有它。
 * 上面这段是「值本身自洽」的验证，不等于「官方就是这几个数」。
 *
 * <h2>四、线程</h2>
 * 所有查询只能在<b>持有该对局句柄的那条线程</b>上调。本类不负责线程，
 * 调用方（{@link DuelRoom#onQuestion}）保证在对局线程上调用。
 */
public final class FieldCodes {

    // ── 内核常量（common.h:37-39、55-64、75-83、230-252）───────────────────

    /** 段长度 = 0：无效查询（{@code common.h:37}、{@code ocgapi.cpp:265}）。 */
    public static final int LEN_FAIL = 0;
    /** 段长度 = 4：空格子只写了一个长度，没有 flag 也没有字段（{@code common.h:38}）。 */
    public static final int LEN_EMPTY = 4;
    /** 段头字节数：i32 len + u32 flag（{@code common.h:39}）。 */
    public static final int LEN_HEADER = 8;

    /**
     * 这个位置是不是「一堆」——卡组/墓地/额外/除外。
     *
     * <p>它们在牌桌上只占一个格子、所有卡共用，所以「从墓地选一张」这种询问
     * <b>没法靠点格子回答</b>（点那一堆等于同时点中里面每一张），必须列出来。
     * ygo 的做法是弹 {@code wCardSelect} 卡名列表（{@code client_field.cpp:431-527}）。
     */
    public static boolean isPileLocation(int location) {
        return location == LOCATION_DECK || location == LOCATION_GRAVE
                || location == LOCATION_EXTRA || location == LOCATION_REMOVED;
    }
    public static final int LOCATION_DECK = 0x01;
    public static final int LOCATION_HAND = 0x02;
    public static final int LOCATION_MZONE = 0x04;
    public static final int LOCATION_SZONE = 0x08;
    public static final int LOCATION_GRAVE = 0x10;
    public static final int LOCATION_REMOVED = 0x20;
    public static final int LOCATION_EXTRA = 0x40;
    public static final int LOCATION_OVERLAY = 0x80;

    public static final int POS_FACEUP = 0x5;
    public static final int POS_FACEDOWN = 0xa;
    /** 攻击表示的两位（表侧 {@code 0x1}｜里侧 {@code 0x2}），{@code common.h:112-115}。 */
    public static final int POS_ATTACK = 0x3;
    /** 守备表示的两位（表侧 {@code 0x4}｜里侧 {@code 0x8}）。 */
    public static final int POS_DEFENSE = 0xc;
    /** 公开标记，由 {@code card.cpp:407-408} 或进 position 字节。 */
    public static final int POS_REVEAL = 0x80;

    /**
     * 「改变表示形式」这一项按<b>当前表示形式</b>该叫什么。
     *
     * <p>与官方客户端逐条对齐（{@code event_handler.cpp:2297-2307}）：
     * 里侧 → {@code 1154 反转召唤}、攻击表示 → {@code 1155 守备表示}、
     * 其余（表侧守备）→ {@code 1156 攻击表示}。
     *
     * <p>三项都写成「变更表示」是不行的：盖着的怪要做的是<b>反转召唤</b>
     * （会翻开并触发反转效果），竖着的是转守备、横着的是转攻击——
     * 这是三个不同的决定，玩家得先知道现在是什么姿势才能选。
     *
     * <p>顺序有讲究：必须先判里侧。里侧攻击（{@code 0x2}）同时命中攻击位，
     * 先判攻击会把它说成「守备表示」，而它实际是可以反转召唤的。
     *
     * <p>这个方法刻意放在这里而不是界面里：它是<b>纯位运算</b>，
     * 放在这里就能在没有 Minecraft 的情况下逐位断言。
     *
     * @param position 卡的 {@code position} 位标志；{@code -1}（查不到快照）时给中性文案
     */
    public static String repositionName(int position) {
        return repositionKey(position);
    }

    /**
     * 「变更表示」这一项该用语言资源里的哪一条 key。
     *
     * <p><b>返回 key 而不是中文</b>：界面文案由模组自己的语言资源决定
     * （{@code assets/ygomc/lang/*.json}），而这个类属于 common，服务端也会加载，
     * 不该、也不能去查客户端语言表。所以这里只给语义，{@code DuelScreen} 拿 key 去取词。
     *
     * <p>返回的取值与 {@link #repositionName} 一一对应，自检（{@code Round4Check}
     * 与 {@code FieldCodesCheck}）按这些常量断言，不再比中文。
     *
     * @param position 卡的 {@code position} 位标志；{@code -1}（查不到快照）时给中性 key
     */
    public static String repositionKey(int position) {
        if (position < 0) {
            // 拿不到快照（例如这一帧还没到）时不要瞎猜一个具体姿势。
            return KEY_REPOSITION_GENERIC;
        }
        if ((position & POS_FACEDOWN) != 0) {
            return KEY_REPOSITION_FLIP;
        }
        if ((position & POS_ATTACK) != 0) {
            return KEY_REPOSITION_TO_DEFENSE;
        }
        return KEY_REPOSITION_TO_ATTACK;
    }

    /**
     * 「变更表示」四种说法的 key。
     *
     * <p>它们住在 {@code DuelText} 那个常量类里会形成反向依赖（{@code DuelText} 是
     * 客户端类），所以 key 的字面量放在这里，两边共用同一份。
     */
    public static final String KEY_REPOSITION_GENERIC = "ygomc.duel.reposition.generic";
    public static final String KEY_REPOSITION_FLIP = "ygomc.duel.reposition.flip";
    public static final String KEY_REPOSITION_TO_DEFENSE = "ygomc.duel.reposition.to_defense";
    public static final String KEY_REPOSITION_TO_ATTACK = "ygomc.duel.reposition.to_attack";

    public static final int QUERY_CODE = 0x1;
    public static final int QUERY_POSITION = 0x2;
    public static final int QUERY_ALIAS = 0x4;
    public static final int QUERY_TYPE = 0x8;
    public static final int QUERY_LEVEL = 0x10;
    public static final int QUERY_RANK = 0x20;
    public static final int QUERY_ATTRIBUTE = 0x40;
    public static final int QUERY_RACE = 0x80;
    public static final int QUERY_ATTACK = 0x100;
    public static final int QUERY_DEFENSE = 0x200;
    public static final int QUERY_BASE_ATTACK = 0x400;
    public static final int QUERY_BASE_DEFENSE = 0x800;
    public static final int QUERY_REASON = 0x1000;
    public static final int QUERY_REASON_CARD = 0x2000;
    public static final int QUERY_EQUIP_CARD = 0x4000;
    public static final int QUERY_TARGET_CARD = 0x8000;
    public static final int QUERY_OVERLAY_CARD = 0x10000;
    public static final int QUERY_COUNTERS = 0x20000;
    public static final int QUERY_OWNER = 0x40000;
    public static final int QUERY_STATUS = 0x80000;
    public static final int QUERY_LSCALE = 0x200000;
    public static final int QUERY_RSCALE = 0x400000;
    public static final int QUERY_LINK = 0x800000;

    // ── 官方 Refresh 用的 flag（推导见类注释三）────────────────────────────

    /** 怪兽区：{@code 0x1fff | STATUS | LINK}。 */
    public static final int FLAG_MZONE = 0x881fff;
    /** 魔陷区：{@code 0x1fff | STATUS | LSCALE | RSCALE}。 */
    public static final int FLAG_SZONE = 0x681fff;
    /** 手牌：同魔陷区。 */
    public static final int FLAG_HAND = 0x681fff;
    /** 墓地：{@code 0x1fff | STATUS}。 */
    public static final int FLAG_GRAVE = 0x081fff;
    /** 除外：同为公开区域，用墓地的 flag。 */
    public static final int FLAG_REMOVED = 0x081fff;
    /** 额外卡组：{@code 0x1fff | STATUS | LSCALE | RSCALE | LINK}。 */
    public static final int FLAG_EXTRA = 0xe81fff;

    /**
     * 整块不可见的区域用的 flag：<b>只问表示形式</b>。
     *
     * <p>不带 {@code QUERY_CODE}/{@code QUERY_ALIAS}，所以内核根本不会写出卡号——
     * 这不是「查出来再丢掉」，而是「从来没查出来」，即使本类的过滤逻辑写错了也泄不出去。
     * 也不带 TYPE/LEVEL/ATK 之类：那些同样能反推出一张里侧卡的身份。
     */
    public static final int FLAG_HIDDEN = QUERY_POSITION;

    /**
     * 一段：一张卡。{@code code} 为 0 表示这次查询没要卡号。
     *
     * <p>{@code stats} 是当前攻/守/属性/种族/等级/阶级/连接。掩码里本来就请求了这些字段
     * （{@code FLAG_MZONE = 0x881fff}），以前解析时只做 {@code c += 4} 跳过、不落库，
     * 所以界面上没法显示「变了的攻守」。里侧/隐藏的卡查不到它们（见 {@link #FLAG_HIDDEN}）。
     */
    public record Entry(int code, int position, int overlayCount, DuelBoard.Stats stats) {
    }

    private FieldCodes() {
    }

    /**
     * 解析 {@code query_field_card} 的输出，返回<b>非空段</b>按出现顺序的列表。
     *
     * <p>遇到 {@code LEN_EMPTY} 段会跳过（内核在怪兽区/魔陷区的空槽上会写它，
     * 但按 {@code ocgapi.cpp:275,285} 不推进游标，所以正常情况下根本不会出现在
     * 返回长度内；这里照样处理，免得两边理解不一致时整体错位）。
     *
     * @throws IllegalStateException 段长度与缓冲区不自洽（宁可炸，也不要按错位解析出一张错的牌桌）
     */
    public static List<Entry> parse(byte[] data) {
        List<Entry> out = new ArrayList<>();
        int p = 0;
        while (p + 4 <= data.length) {
            int len = readInt(data, p);
            if (len == LEN_FAIL) {
                break;
            }
            if (len == LEN_EMPTY) {
                p += LEN_EMPTY;
                continue;
            }
            if (len < LEN_HEADER || p + len > data.length) {
                throw new IllegalStateException("queryFieldCard 段长度异常：" + len
                        + "（游标 " + p + "，缓冲 " + data.length + " 字节）");
            }
            int flag = readInt(data, p + 4);
            int c = p + LEN_HEADER;
            int code = 0;
            int position = 0;
            int overlay = 0;
            int level = 0;
            int rank = 0;
            int attribute = 0;
            int race = 0;
            int attack = 0;
            int defense = 0;
            int link = 0;

            // 严格按 card.cpp 的书写顺序前进，一个位都不能漏。
            if ((flag & QUERY_CODE) != 0) {
                code = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_POSITION) != 0) {
                position = (readInt(data, c) >>> 24) & 0xFF;
                c += 4;
            }
            if ((flag & QUERY_ALIAS) != 0) {
                c += 4;
            }
            if ((flag & QUERY_TYPE) != 0) {
                c += 4;
            }
            if ((flag & QUERY_LEVEL) != 0) {
                level = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_RANK) != 0) {
                rank = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_ATTRIBUTE) != 0) {
                attribute = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_RACE) != 0) {
                race = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_ATTACK) != 0) {
                attack = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_DEFENSE) != 0) {
                defense = readInt(data, c);
                c += 4;
            }
            if ((flag & QUERY_BASE_ATTACK) != 0) {
                c += 4;
            }
            if ((flag & QUERY_BASE_DEFENSE) != 0) {
                c += 4;
            }
            if ((flag & QUERY_REASON) != 0) {
                c += 4;
            }
            if ((flag & QUERY_REASON_CARD) != 0) {
                c += 4;
            }
            if ((flag & QUERY_EQUIP_CARD) != 0) {
                c += 4;
            }
            if ((flag & QUERY_TARGET_CARD) != 0) {
                c += 4 + 4 * readInt(data, c);
            }
            if ((flag & QUERY_OVERLAY_CARD) != 0) {
                overlay = readInt(data, c);
                c += 4 + 4 * overlay;
            }
            if ((flag & QUERY_COUNTERS) != 0) {
                c += 4 + 4 * readInt(data, c);
            }
            if ((flag & QUERY_OWNER) != 0) {
                c += 4;
            }
            if ((flag & QUERY_STATUS) != 0) {
                c += 4;
            }
            if ((flag & QUERY_LSCALE) != 0) {
                c += 4;
            }
            if ((flag & QUERY_RSCALE) != 0) {
                c += 4;
            }
            if ((flag & QUERY_LINK) != 0) {
                link = readInt(data, c);
                c += 8;                       // link + link_marker，是两个 u32
            }
            if (c > p + len) {
                throw new IllegalStateException("queryFieldCard 段内字段越界：flag=0x"
                        + Integer.toHexString(flag) + "，段长 " + len + "，字段读到 " + (c - p));
            }
            out.add(new Entry(code, position, overlay, new DuelBoard.Stats(
                    attack, defense, attribute, race, level, rank, link)));
            p += len;
        }
        return out;
    }

    /**
     * 这张卡对 {@code viewerSeat} 是否可见。见类注释二。
     *
     * @param mine     {@code seat == viewerSeat}
     * @param location {@code LOCATION_*}
     * @param position 内核给的表示形式（可能带 {@code POS_REVEAL}）
     */
    public static boolean visible(boolean mine, int location, int position) {
        if (mine) {
            return true;
        }
        if (location == LOCATION_HAND || location == LOCATION_DECK || location == LOCATION_EXTRA) {
            return false;
        }
        return (position & POS_FACEDOWN) == 0 || (position & POS_REVEAL) != 0;
    }

    /**
     * 在快照牌桌的基础上填卡号，返回一张新牌桌。
     *
     * <p><b>必须在持有该对局句柄的线程上调用</b>（内部会调 JNI）。
     *
     * <p>两条独立路径必须对得上，对不上就抛：
     * {@code query_field_info} 的占用数 vs {@code query_field_card} 的段数。
     * 这是唯一能抓住「槽位与段配对错位」的检查——配对错了会安静地
     * 把 A 格的卡号画到 B 格上，看起来完全像一局正常的牌。
     *
     * @param viewerSeat 视角座位（本项目里是 {@code DuelRoom.HUMAN_SEAT}）
     */
    public static DuelBoard attach(OcgDuel duel, DuelBoard board, int viewerSeat) {
        DuelBoard.PlayerBoard[] sides = new DuelBoard.PlayerBoard[2];
        for (int seat = 0; seat < 2; seat++) {
            boolean mine = seat == viewerSeat;
            DuelBoard.PlayerBoard b = board.playerAt(seat);
            List<DuelBoard.Zone> mz = fillZones(duel, seat, LOCATION_MZONE, FLAG_MZONE,
                    b.monsterZones(), mine);
            List<DuelBoard.Zone> sz = fillZones(duel, seat, LOCATION_SZONE, FLAG_SZONE,
                    b.spellZones(), mine);
            List<DuelBoard.Zone> hand = fillList(duel, seat, LOCATION_HAND,
                    mine ? FLAG_HAND : FLAG_HIDDEN, b.handCount(), mine);
            List<DuelBoard.Zone> grave = fillList(duel, seat, LOCATION_GRAVE,
                    FLAG_GRAVE, b.graveCount(), mine);
            List<DuelBoard.Zone> removed = fillList(duel, seat, LOCATION_REMOVED,
                    FLAG_REMOVED, b.removedCount(), mine);
            List<DuelBoard.Zone> extra = fillList(duel, seat, LOCATION_EXTRA,
                    mine ? FLAG_EXTRA : FLAG_HIDDEN, b.extraCount(), mine);
            sides[seat] = new DuelBoard.PlayerBoard(b.lp(), mz, sz, b.deckCount(),
                    b.handCount(), b.graveCount(), b.removedCount(), b.extraCount(),
                    b.extraPCount(), hand, grave, removed, extra);
        }
        // 阶段与回合是快照之外另记的（见 DuelBoard 的类注释），这里必须原样带过去——
        // 用五参构造会静默把它们清成「未知」，表现为阶段条上的当前格时有时无。
        return new DuelBoard(board.duelRule(), board.chainCount(), sides[0], sides[1],
                board.phase(), board.turn());
    }

    /** 怪兽区 / 魔陷区：段与「快照里占用的槽」按升序配对。 */
    private static List<DuelBoard.Zone> fillZones(OcgDuel duel, int seat, int location, int flag,
                                                  List<DuelBoard.Zone> shape, boolean mine) {
        List<Entry> entries = parse(duel.fieldCardBytes(seat, location, flag));
        int occupied = 0;
        for (DuelBoard.Zone z : shape) {
            if (z.occupied()) {
                occupied++;
            }
        }
        if (entries.size() != occupied) {
            throw new IllegalStateException("queryFieldCard(座位 " + seat + "，区域 " + location
                    + ") 返回 " + entries.size() + " 段，但快照里占用 " + occupied
                    + " 格：两条独立路径不一致，拒绝画这张牌桌");
        }
        List<DuelBoard.Zone> out = new ArrayList<>(shape.size());
        int k = 0;
        for (DuelBoard.Zone z : shape) {
            if (!z.occupied()) {
                out.add(DuelBoard.Zone.EMPTY);
                continue;
            }
            Entry e = entries.get(k++);
            // 表示形式与叠放数一律沿用快照里的值：
            //   * 快照的 position 与查询的 position 低 7 位必然相同
            //     （快照写 current.position，查询写 get_public_info_location()，
            //     区别只是后者可能多一个 POS_REVEAL 位），沿用快照不改变既有行为；
            //   * 叠放数只有快照里有——官方 Refresh 的怪兽区 flag 不含
            //     QUERY_OVERLAY_CARD（0x881fff 的 0x10000 位是 0），
            //     照查询结果填会把界面已经在画的叠放数清零。
            out.add(new DuelBoard.Zone(true, z.position(), z.overlayCount(),
                    visible(mine, location, e.position()) ? e.code() : 0,
                    // 数值同样按可见性给：里侧/隐藏的卡连攻守都不该泄出去
                    // （内核那边这种查询压根没要这些字段，这里是第二道闸）。
                    visible(mine, location, e.position()) ? e.stats() : null));
        }
        return List.copyOf(out);
    }

    /** 手牌 / 墓地 / 除外 / 额外：一卡一段，段数必须等于快照里的张数。 */
    private static List<DuelBoard.Zone> fillList(OcgDuel duel, int seat, int location, int flag,
                                                 int snapshotCount, boolean mine) {
        List<Entry> entries = parse(duel.fieldCardBytes(seat, location, flag));
        if (entries.size() != snapshotCount) {
            throw new IllegalStateException("queryFieldCard(座位 " + seat + "，区域 " + location
                    + ") 返回 " + entries.size() + " 段，但快照里是 " + snapshotCount
                    + " 张：两条独立路径不一致，拒绝画这张牌桌");
        }
        List<DuelBoard.Zone> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            boolean vis = visible(mine, location, e.position());
            out.add(new DuelBoard.Zone(true, e.position(), e.overlayCount(),
                    vis ? e.code() : 0, vis ? e.stats() : null));
        }
        return List.copyOf(out);
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
