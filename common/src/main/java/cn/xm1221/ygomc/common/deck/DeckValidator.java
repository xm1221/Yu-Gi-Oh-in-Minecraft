package cn.xm1221.ygomc.common.deck;

import cn.xm1221.ygomc.common.card.DeckData;
import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡组合法性校验。
 *
 * <p>校验分两级，区别很重要：
 * <ul>
 *   <li><b>错误</b>：这副卡组<b>不能</b>拿去开局（张数不合规、卡号在数据包里不存在、
 *       同名超过 3 张）。错误不修掉就开局，轻则卡组少牌，重则内核收到未知卡号后
 *       把它当成一张全零属性的空卡，对局行为无从预期；</li>
 *   <li><b>警告</b>：能开局，但大概率是用户搞错了（例如把融合怪兽放进主卡组）。
 *       不拦，只提示。</li>
 * </ul>
 *
 * <h2>本类还不检查什么</h2>
 * <b>禁限卡表（{@code lflist.conf}）没有应用。</b>那是 M4 的工作：它要读 677 KB 的
 * lflist，还要处理「同一张卡在不同赛制下限制不同」。现在放行了限制卡，
 * 因此这里的结论只能用于本地自娱，不能当作合规判据。这一点在 {@link Report#describe()}
 * 里也会如实说明，免得用户以为「没报错就是合规」。
 *
 * <h2>为什么以 {@link DataPack} 为参数而不是去 {@code openDefault}</h2>
 * 校验逻辑应当能脱离 Minecraft 单独跑（对着数据包跑一遍本机 526 副卡组就能验证），
 * 所以数据来源由调用方注入，本类不碰任何平台 API。
 */
public final class DeckValidator {

    /** 只有额外卡组能放的怪兽类型。 */
    private static final int EXTRA_ONLY_TYPES = CardDataDb.CardTypes.TYPE_FUSION
            | CardDataDb.CardTypes.TYPE_SYNCHRO
            | CardDataDb.CardTypes.TYPE_XYZ
            | CardDataDb.CardTypes.TYPE_LINK;

    private DeckValidator() {
    }

    /**
     * @param errors   必须修掉才能开局的项；空表示可开局
     * @param warnings 可疑但不拦的项
     */
    public record Report(List<String> errors, List<String> warnings) {

        public Report {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        /** 人类可读的多行报告。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (ok()) {
                sb.append("卡组可用");
                if (warnings.isEmpty()) {
                    sb.append("。");
                } else {
                    sb.append("，但有 ").append(warnings.size()).append(" 项提示：");
                }
            } else {
                sb.append("卡组不可用，有 ").append(errors.size()).append(" 项错误：");
            }
            for (String e : errors) {
                sb.append("\n  ✗ ").append(e);
            }
            for (String w : warnings) {
                sb.append("\n  ! ").append(w);
            }
            if (!warnings.isEmpty() || !ok()) {
                sb.append("\n（未检查禁限卡表，结论仅适用于本地对局）");
            }
            return sb.toString();
        }
    }

    /** 校验一副卡组。{@code pack} 可以是加载不全的数据包——缺的部分会被报成错误而不是崩溃。 */
    public static Report validate(DeckData deck, DataPack pack) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        checkSizes(deck, errors);
        checkExistence(deck, pack, errors);
        checkSameName(deck, pack, errors);
        checkZonePlacement(deck, pack, warnings);

        return new Report(errors, warnings);
    }

    private static void checkSizes(DeckData deck, List<String> errors) {
        int main = deck.main().size();
        if (main == 0) {
            errors.add("主卡组是空的");
        } else if (main < DeckData.MAIN_MIN || main > DeckData.MAIN_MAX) {
            errors.add("主卡组 " + main + " 张，必须在 " + DeckData.MAIN_MIN
                    + "–" + DeckData.MAIN_MAX + " 张之间");
        }
        if (deck.extra().size() > DeckData.EXTRA_MAX) {
            errors.add("额外卡组 " + deck.extra().size() + " 张，上限 " + DeckData.EXTRA_MAX + " 张");
        }
        if (deck.side().size() > DeckData.SIDE_MAX) {
            errors.add("副卡组 " + deck.side().size() + " 张，上限 " + DeckData.SIDE_MAX + " 张");
        }
    }

    private static void checkExistence(DeckData deck, DataPack pack, List<String> errors) {
        if (pack.cardData() == null) {
            errors.add("卡表不可用，无法校验卡号：" + pack.problems());
            return;
        }
        // 同一张缺失的卡在卡组里可能出现多次，去重后只报一次，否则一份 40 张的卡组
        // 刷出几十行同样的错误，真正的问题反而看不见。
        Map<Integer, Integer> missing = new LinkedHashMap<>();
        for (int code : deck.all()) {
            if (pack.statsOf(code) == null) {
                missing.merge(code, 1, Integer::sum);
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("有 ").append(missing.size())
                    .append(" 种卡号在数据包里不存在：");
            int shown = 0;
            for (Map.Entry<Integer, Integer> e : missing.entrySet()) {
                if (shown++ == 8) {
                    sb.append(" …共 ").append(missing.size()).append(" 种");
                    break;
                }
                sb.append(' ').append(e.getKey());
                if (e.getValue() > 1) {
                    sb.append('×').append(e.getValue());
                }
            }
            errors.add(sb.toString());
        }
    }

    /**
     * 同名卡数量。
     *
     * <p>规则是「主卡组 + 副卡组同名合计 ≤ 3」、「额外卡组同名 ≤ 3」，两个池子分开算。
     * 这里按卡的<b>名字</b>而不是卡号分组：异画、以及 {@code alias} 那些卡，
     * 卡号不同但算同名。
     */
    private static void checkSameName(DeckData deck, DataPack pack, List<String> errors) {
        if (pack.cardText() == null) {
            errors.add("卡文库不可用，无法校验同名卡数量：" + pack.problems());
            return;
        }
        checkPoolSameName(concat(deck.main(), deck.side()), "主卡组+副卡组", pack, errors);
        checkPoolSameName(deck.extra(), "额外卡组", pack, errors);
    }

    private static void checkPoolSameName(List<Integer> pool, String label,
                                          DataPack pack, List<String> errors) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (int code : pool) {
            String name = pack.nameOf(code);
            if (name == null) {
                continue;      // 卡号不存在或卡文缺失，已在别处报过，不在这里重复刷屏
            }
            byName.merge(name, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            if (e.getValue() > 3) {
                errors.add(label + "里「" + e.getKey() + "」有 " + e.getValue() + " 张，上限 3 张");
            }
        }
    }

    /**
     * 区域错放。
     *
     * <p>主卡组放融合/同调/超量/连接怪兽，或者额外卡组放普通怪兽，都是新手很常见的错误，
     * 而且引擎<b>不会</b>因此报错——它只是让那些卡永远无法出场。所以这里提示一下。
     */
    private static void checkZonePlacement(DeckData deck, DataPack pack, List<String> warnings) {
        if (pack.cardData() == null) {
            return;
        }
        int misplacedInMain = 0;
        for (int code : deck.main()) {
            CardDataDb.Stats s = pack.statsOf(code);
            if (s != null && (s.type() & EXTRA_ONLY_TYPES) != 0) {
                misplacedInMain++;
            }
        }
        if (misplacedInMain > 0) {
            warnings.add("主卡组里有 " + misplacedInMain + " 张融合/同调/超量/连接怪兽，"
                    + "它们应当放在额外卡组，否则永远无法出场");
        }

        int misplacedInExtra = 0;
        for (int code : deck.extra()) {
            CardDataDb.Stats s = pack.statsOf(code);
            if (s != null && (s.type() & EXTRA_ONLY_TYPES) == 0) {
                misplacedInExtra++;
            }
        }
        if (misplacedInExtra > 0) {
            warnings.add("额外卡组里有 " + misplacedInExtra + " 张并非融合/同调/超量/连接的卡，"
                    + "它们无法从额外卡组出场");
        }
    }

    private static List<Integer> concat(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
