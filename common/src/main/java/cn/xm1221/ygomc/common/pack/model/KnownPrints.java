package cn.xm1221.ygomc.common.pack.model;

import cn.xm1221.ygomc.common.card.CardRarity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * 开包和 {@code PackCatalog.validate} 共用的卡密过滤。
 * 不在卡表里的卡密丢掉；滤完某一档为空时，抽卡仍走 {@link Booster} 的 fallback。
 * 卡号小于等于 0 一律丢掉，避免发出一张 0 号卡。没有卡表时只做这一步。
 */
public final class KnownPrints {

    private KnownPrints() {
    }

    /**
     * @param known 卡表里有这张卡时返回真。{@code null} 表示没有卡表，除了非正卡号都保留
     * @return 滤完一张不剩时返回 {@code null}
     */
    public static CardSetData filter(CardSetData expanded, IntPredicate known) {
        List<PrintRef> kept = new ArrayList<>();
        for (CardRarity rarity : CardRarity.values()) {
            for (PrintRef print : expanded.pool(rarity)) {
                if (keep(print.code(), known)) {
                    kept.add(print);
                }
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        return new CardSetData(kept);
    }

    public static boolean keep(int code, IntPredicate known) {
        if (code <= 0) {
            return false;
        }
        return known == null || known.test(code);
    }
}
