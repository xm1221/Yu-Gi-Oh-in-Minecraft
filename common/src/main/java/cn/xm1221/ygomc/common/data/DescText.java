package cn.xm1221.ygomc.common.data;

import cn.xm1221.ygomc.common.ocg.msg.Msg;

import java.util.Set;

/**
 * 内核 {@code description} 字段 → 人看的文本。
 *
 * <p>这是 ygopro 客户端那套解析的忠实移植，逐行对着
 * {@code gframe/data_manager.cpp:267-278} 的 {@code DataManager::GetDesc}：
 * {@code strCode <= MAX_STRING_ID(0x7ff)} 说明它是 {@code strings.conf} 的
 * <b>系统串</b>，去 strings.bin 的 system 节查；否则拆成
 * {@code 卡号 = strCode >> 4} 与 {@code str1..str16 序号 = strCode & 0xf}
 * 去查那张卡的字符串表。
 *
 * <p>{@code SELECT_EFFECTYN} 另有三条特例（{@code duelclient.cpp:1581-1594}）：
 * {@code desc == 0} 内核没给文本；{@code desc == 221} 是诱发类效果，末尾还要追加
 * 223「稍后将询问其他可以发动的效果。」；{@code desc} 落在
 * {@code {95,96,97,218,219,220}} 时（{@code duelclient.cpp:46}）系统串自带
 * {@code %ls} 占位、要用<b>卡名</b>填。{@code SELECT_YESNO} 只走 GetDesc
 * （{@code :1606}），没有这些特例。
 *
 * <p>为什么要有这个类：原来的实现是 {@code "（说明 " + description + "）"}——
 * 把字符串表编号直接印给玩家看，于是界面上出现「是否发动效果？（说明 122）」
 * 这种文本。<b>查不到就什么都不显示</b>，让调用方回退到自己的中文兜底，
 * 也比把编号印出来强（ygo 对查不到的编号显示 {@code ???}，那是它没有别的兜底）。
 */
public final class DescText {

    private DescText() {
    }

    /** 同 {@code data_manager.h:21}。超过它的 {@code strCode} 是「卡号<<4 | str序号」。 */
    public static final int MAX_STRING_ID = 0x7FF;

    /** 位置名的起始编号，同 {@code data_manager.h:126}（卡组/手卡/怪兽区/…）。 */
    private static final int STRING_ID_LOCATION = 1000;

    /** {@code SELECT_EFFECTYN} 里自带 {@code %ls}、要用卡名填的系统串。 */
    private static final Set<Integer> NAME_FORMATTED = Set.of(95, 96, 97, 218, 219, 220);

    /** 诱发类效果：格式是「是否在[位置]发动[卡名]的诱发类效果？」。 */
    private static final int INDUCED = 221;

    /** 诱发类效果问完还要补一句「稍后将询问其他可以发动的效果。」。 */
    private static final int INDUCED_FOLLOWUP = 223;

    /**
     * {@code SELECT_EFFECTYN} 的询问标题。
     *
     * @param desc         内核给的 description
     * @param cardCode     那张卡的卡号，用于填 {@code %ls}
     * @param locationName 卡所在区域名（{@link #location(int, int)}），填 {@code %ls}
     * @param fallback     解析不出来时的中文兜底
     * @return 标题；{@code desc == 0} 或查不到时原样返回 {@code fallback}
     */
    public static String effectyn(int desc, int cardCode, String locationName, String fallback) {
        if (desc == 0) {
            return fallback;
        }
        String name = cardName(cardCode);
        if (desc == INDUCED) {
            String head = fill(sysString(INDUCED), locationName, name);
            if (head == null) {
                return fallback;
            }
            String tail = sysString(INDUCED_FOLLOWUP);
            return tail == null ? head : head + "\n" + tail;
        }
        if (NAME_FORMATTED.contains(desc)) {
            String s = fill(sysString(desc), name);
            return s == null ? fallback : s;
        }
        String s = getDesc(desc);
        return s == null ? fallback : s;
    }

    /**
     * {@code SELECT_YESNO} 的询问标题。
     *
     * <p>它和 {@code SELECT_EFFECTYN} 不同：内核只给一个 description、
     * 客户端也只走 {@code GetDesc}（{@code duelclient.cpp:1606}），
     * 没有 {@code 221} 的追加、也没有要用卡名填的 {@code %ls}。
     */
    public static String yesNo(int desc, String fallback) {
        if (desc == 0) {
            return fallback;
        }
        String s = getDesc(desc);
        return s == null ? fallback : s;
    }
    /**
     * {@code HINT_SELECTMSG} 的文本。
     *
     * <p>它本该是 {@code strings.conf} 的系统串（500「请选择要解放的卡」、
     * 549「请选择攻击的对象」…），但真实对局里也见到脚本直接塞<b>裸卡号</b>
     * （实测 desc=13903402 就是那一局卡组里的第一张卡）。裸卡号走 {@code GetDesc}
     * 会被当成「卡号 868962 的第 11 条」而查不到——ygo 到这一步只能显示 {@code ???}
     * （{@code client_field.cpp:1056} 的 {@code display_hint}）。这里多补一步：
     * 查不到文本就查卡名，总比一个问号强。
     *
     * @return 提示文本；两者都查不到时返回 {@code null}（调用方应当放弃这条提示）
     */
    public static String selectMessage(int data) {
        String s = getDesc(data);
        return s != null ? s : cardName(data);
    }
    /**
     * {@code GetDesc} 的两路分派（{@code data_manager.cpp:267-278}）。
     *
     * @return 文本；编号不在表里、或那张卡没有这一条时返回 {@code null}
     */
    public static String getDesc(int strCode) {
        if (strCode <= 0) {
            return null;
        }
        if (strCode <= MAX_STRING_ID) {
            return sysString(strCode);
        }
        return DataPacks.descOrNull(strCode);
    }

    /**
     * 区域名，同 {@code DataManager::FormatLocation}（{@code data_manager.cpp:336-356}）。
     *
     * <p>三处特判是抄来的：魔陷区序号 0-4 是「魔法陷阱区」、5 是「场地区」、6 是「灵摆区」；
     * 其余区域按位取对数查系统串 1000+i（卡组=1000、手卡=1001、怪兽区=1002、…）。
     *
     * @return 区域名；表里查不到时返回 {@code null}
     */
    public static String location(int location, int sequence) {
        if (location == Msg.Location.SZONE) {
            if (sequence < 5) {
                return sysString(STRING_ID_LOCATION + 3);
            }
            return sysString(sequence == 5 ? STRING_ID_LOCATION + 8 : STRING_ID_LOCATION + 9);
        }
        for (int i = 0; i < 10; i++) {
            if ((1 << i) == location) {
                String s = sysString(STRING_ID_LOCATION + i);
                if (s != null) {
                    return s;
                }
                break;
            }
        }
        return null;
    }

    /** @return 系统串原文（可能带 {@code %ls}）；查不到返回 {@code null} */
    private static String sysString(int id) {
        try {
            String s = DataPacks.sysString(id);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable t) {
            // 数据包没装/没配（离线自检里就是这种情形）时不能让询问构造整个炸掉——
            // 界面退化成中文兜底，还能继续打。
            return null;
        }
    }

    private static String cardName(int code) {
        try {
            String n = DataPacks.get().nameOf(code);
            return (n == null || n.isEmpty()) ? null : n;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把系统串里的 {@code %ls} 按顺序换成实参。
     *
     * <p>只用得到 {@code %ls} 这一种占位（strings.conf 里系统串的格式符就这一种），
     * 所以不做完整的 printf。参数不够时剩下的 {@code %ls} 原样留着——
     * 那说明我们的调用和内核的格式对不上，让它显形，而不是悄悄少一段话。
     */
    static String fill(String template, String... args) {
        if (template == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int arg = 0;
        int i = 0;
        while (i < template.length()) {
            int at = template.indexOf("%ls", i);
            if (at < 0 || arg >= args.length) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, at);
            sb.append(args[arg] == null ? "？" : args[arg]);
            arg++;
            i = at + 3;
        }
        return sb.toString();
    }
}