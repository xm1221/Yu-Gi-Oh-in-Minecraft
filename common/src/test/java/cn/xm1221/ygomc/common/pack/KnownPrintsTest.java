package cn.xm1221.ygomc.common.pack;

import cn.xm1221.ygomc.common.card.CardRarity;
import cn.xm1221.ygomc.common.pack.model.Booster;
import cn.xm1221.ygomc.common.pack.model.CardSetData;
import cn.xm1221.ygomc.common.pack.model.KnownPrints;
import cn.xm1221.ygomc.common.pack.model.PackRoller;
import cn.xm1221.ygomc.common.pack.model.PrintRef;
import cn.xm1221.ygomc.common.pack.model.Pull;
import cn.xm1221.ygomc.common.pack.model.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownPrintsTest {

    @Test
    void dropsUnknownAndNonPositiveCodes() {
        CardSetData raw = new CardSetData(List.of(
                PrintRef.of(1001, CardRarity.COMMON),
                PrintRef.of(9, CardRarity.RARE),
                PrintRef.of(0, CardRarity.COMMON),
                PrintRef.of(-1, CardRarity.SUPER)));
        CardSetData filtered = KnownPrints.filter(raw, code -> code == 1001 || code == 0);
        assertEquals(List.of(1001), filtered.pool(CardRarity.COMMON).stream().map(PrintRef::code).toList());
        assertTrue(filtered.isEmpty(CardRarity.RARE));
        assertTrue(filtered.isEmpty(CardRarity.SUPER));
        assertNull(KnownPrints.filter(raw, code -> false));
    }

    @Test
    void emptyRarityUsesBoosterFallback() {
        CardSetData raw = new CardSetData(List.of(
                PrintRef.of(1, CardRarity.RARE),
                PrintRef.of(2, CardRarity.COMMON)));
        CardSetData filtered = KnownPrints.filter(raw, code -> code == 2);
        Booster booster = new Booster(
                List.of(new Pull("only", 1, List.of(Slot.of(1, CardRarity.RARE)))),
                Booster.Duplicates.ALLOW,
                List.of(CardRarity.SECRET, CardRarity.ULTRA, CardRarity.SUPER, CardRarity.RARE, CardRarity.COMMON));
        List<PrintRef> cards = PackRoller.open(booster, filtered, bound -> 0);
        assertEquals(1, cards.size());
        assertEquals(2, cards.get(0).code());
        assertEquals(CardRarity.COMMON, cards.get(0).rarity());
    }
}
