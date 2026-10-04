package cn.xm1221.ygomc.common.deck;

import cn.xm1221.ygomc.common.card.DeckData;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置卡组：不依赖任何外部文件就能开局的那一副。
 *
 * <h2>为什么要有它，以及为什么不能再是「40 张同一张卡」</h2>
 * 早先的兜底卡组是 {@code FALLBACK_CARD} 填满 40 格——40 张一模一样的卡。
 * 它确实能开局，但作为<b>测试</b>用的卡组几乎没有价值：没有召唤链、没有魔陷互动、
 * 没有额外卡组，任何一种询问都很难被触发，出了问题也无从判断是引擎还是界面。
 *
 * <p>现在这一副取自 {@code local-data/decks/时光飞跃卡池全卡.ydk}——那是本地唯一一副
 * <b>60 张主卡组全部互不相同</b>的卡组（另有 15 张额外、5 张副卡组，也全不重复），
 * 是全卡池铺开的测试用牌。这里取其中 40 张主卡组 + 8 张额外卡组固化进代码，
 * 于是「没有卡组目录 / 玩家没选卡组 / 卡组校验不过」这三种情况下，
 * 兜底仍然是<b>卡种丰富</b>的一副，而不是 40 张一样的卡。
 *
 * <h2>为什么固化进代码而不是读文件</h2>
 * 兜底的意义正是「文件不在也能开局」。读文件当兜底，文件一没就还是开不了局。
 *
 * <h2>卡号合法性</h2>
 * 这些卡号必须都在数据包里存在，否则内核会把未知卡号当成一张全零属性的空卡，
 * 对局行为无从预期。{@code DeckValidator} 会拦住这种卡组，
 * 离线侧还有 {@code BuiltinDeckCheck} 专门拿真实数据包校验这一副。
 */
public final class BuiltinDecks {

    /** 兜底 / 测试卡组的显示名。玩家在报告里看到的就是这个。 */
    public static final String TEST_POOL_LABEL = "内置测试卡池卡组";

    /**
     * 主卡组 40 张，<b>互不相同</b>。
     *
     * <p>刻意保持全不重复：测试要的是「尽可能多的不同卡被摸到」，
     * 重复的卡只会让同样的分支被走很多遍，而没走过的分支依旧没走过。
     */
    public static final int[] TEST_POOL_MAIN = {
            13903402, 41406613, 8085950, 90488465, 78077209,
            91434602, 14124483, 81476402, 85753549, 48745395,
            5560911, 35595518, 42338879, 8814959, 70465810,
            77895328, 66206748, 72427512, 30037118, 97682931,
            87462901, 99054885, 71187462, 40975574, 1980574,
            25533642, 54126514, 38529357, 18482473, 19403423,
            25955749, 80196387, 89693655, 99550630, 20508881,
            25733157, 28958464, 30430448, 31834488, 42560034,
    };

    /**
     * 额外卡组 8 张，互不相同。
     *
     * <p>额外卡组不能省：没有它就永远走不到融合 / 同调 / 超量 / 连接的召唤路径，
     * 而那几类召唤各自会引出不同的询问（选素材、选位置、选表示形式）。
     */
    public static final int[] TEST_POOL_EXTRA = {
            80666118, 23338098, 68431965, 10602628,
            42566602, 33955120, 39317553, 1269512,
    };

    private BuiltinDecks() {
    }

    /**
     * 兜底 / 测试卡组。
     *
     * <p>每次调用返回<b>新的</b> {@link DeckData}：它内部是可变的列表，
     * 而调用方（卡组校验、开局）不保证只读。共用一个实例迟早会被谁改掉。
     */
    public static DeckData testPool() {
        return new DeckData(toList(TEST_POOL_MAIN), toList(TEST_POOL_EXTRA), List.of());
    }

    /** 主卡组张数，供断言与报告使用。 */
    public static int mainCount() {
        return TEST_POOL_MAIN.length;
    }

    /** 额外卡组张数。 */
    public static int extraCount() {
        return TEST_POOL_EXTRA.length;
    }

    private static List<Integer> toList(int[] codes) {
        List<Integer> out = new ArrayList<>(codes.length);
        for (int c : codes) {
            out.add(c);
        }
        return out;
    }
}
