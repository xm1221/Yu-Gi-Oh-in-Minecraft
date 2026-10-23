package cn.xm1221.ygomc.common.pack.supply;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSupplyTest {

    @Test
    void jarDemoIsTheOnlySeedAndTheTablesStaySmall() {
        PackSupply.check();
        assertEquals("ygomc:demo", PackSupply.DEMO);
        assertEquals(List.of(
                "minecraft:chests/simple_dungeon",
                "minecraft:chests/abandoned_mineshaft",
                "minecraft:chests/buried_treasure",
                "minecraft:chests/buried_treasure",
                "minecraft:chests/village/village_cartographer"
        ), PackSupply.CHESTS.stream().map(PackSupply.Chest::table).toList());
        assertEquals(2, PackSupply.VILLAGERS.size());
        assertEquals("minecraft:cartographer", PackSupply.VILLAGERS.get(0).profession());
        assertEquals(2, PackSupply.VILLAGERS.get(0).level());
        assertEquals(PackSupply.Goods.PACK, PackSupply.VILLAGERS.get(0).goods());
        assertEquals(4, PackSupply.VILLAGERS.get(1).level());
        assertEquals(PackSupply.Goods.BOX, PackSupply.VILLAGERS.get(1).goods());
        assertTrue(PackSupply.VILLAGERS.get(1).emeralds() > PackSupply.VILLAGERS.get(0).emeralds());
        assertEquals(1, PackSupply.WANDERERS.size());
        assertEquals(PackSupply.Goods.PACK, PackSupply.WANDERERS.get(0).goods());
        assertFalse(PackSupply.WANDERERS.get(0).rare());
    }

    @Test
    void builtinChestsGetTheDemoPoolAndOverwritesDoNot() {
        List<PackSupply.Chest> treasure = PackSupply.chests("minecraft:chests/buried_treasure", true);
        assertEquals(List.of(PackSupply.Goods.PACK, PackSupply.Goods.BOX),
                treasure.stream().map(PackSupply.Chest::goods).toList());
        assertEquals(List.of(), PackSupply.chests("minecraft:chests/buried_treasure", false));
        assertEquals(List.of(), PackSupply.chests("minecraft:chests/spawn_bonus_chest", true));
        assertEquals(1, PackSupply.chests("minecraft:chests/simple_dungeon", true).size());
    }
}
