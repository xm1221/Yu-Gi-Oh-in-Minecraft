package cn.xm1221.ygomc.common.pack.supply;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子和村民交易里投放哪些卡包。
 *
 * <p>只列 jar 里自带、世界一加载就有的包。现在就是 {@code ygomc:demo}。
 * 启动后同步下来的系列不在这里：数量不固定，也不该一进世界就铺满箱子。
 * 赢 NPC 的奖励不在这里，没有 NPC。
 */
public final class PackSupply {

    /** 数据包里的演示包和演示盒，id 相同，注册表不同。 */
    public static final String DEMO = "ygomc:demo";

    public enum Goods {
        PACK,
        BOX
    }

    /**
     * 往原版箱子表上加的一池。{@code chance} 是这一池单独成功的概率，和原版池子无关。
     * 同一张表可以有两行（一包、一盒）。
     */
    public record Chest(String table, Goods goods, float chance) {
    }

    /** 村民某一级的一笔交易。等级是 1 到 5，和原版一样。 */
    public record VillagerDeal(String profession, int level, Goods goods,
                                int emeralds, int maxUses, int xp, float priceMultiplier) {
    }

    /** 流浪商人的一笔交易。{@code rare} 为真时抽中的机会大约是普通货的五分之一。 */
    public record WandererDeal(Goods goods, int emeralds, int maxUses, int xp,
                                float priceMultiplier, boolean rare) {
    }

    /**
     * 地牢和矿井是常见的箱子；埋藏的宝藏少，所以盒只放这里；
     * 制图师的屋子和后面的制图师交易是同一条线。
     */
    public static final List<Chest> CHESTS = List.of(
            new Chest("minecraft:chests/simple_dungeon", Goods.PACK, 0.25f),
            new Chest("minecraft:chests/abandoned_mineshaft", Goods.PACK, 0.08f),
            new Chest("minecraft:chests/buried_treasure", Goods.PACK, 0.45f),
            new Chest("minecraft:chests/buried_treasure", Goods.BOX, 0.12f),
            new Chest("minecraft:chests/village/village_cartographer", Goods.PACK, 0.40f)
    );

    /**
     * 制图师。学徒（2 级）卖一包，专家（4 级）卖一盒。
     * 不新开职业：职业要工作方块和村民贴图，这里用原版制图台就够。
     */
    public static final List<VillagerDeal> VILLAGERS = List.of(
            new VillagerDeal("minecraft:cartographer", 2, Goods.PACK, 6, 12, 10, 0.05f),
            new VillagerDeal("minecraft:cartographer", 4, Goods.BOX, 24, 4, 30, 0.2f)
    );

    /** 没有村庄时，路上的流浪商人也能买到一包。 */
    public static final List<WandererDeal> WANDERERS = List.of(
            new WandererDeal(Goods.PACK, 8, 1, 1, 0.05f, false)
    );

    private PackSupply() {
    }

    /** 这张原版表要追加的池。玩家数据包整表覆盖时不加，免得盖掉别人的改动。 */
    public static List<Chest> chests(String table, boolean builtin) {
        if (!builtin) {
            return List.of();
        }
        List<Chest> found = new ArrayList<>();
        for (Chest chest : CHESTS) {
            if (chest.table().equals(table)) {
                found.add(chest);
            }
        }
        return List.copyOf(found);
    }

    /** 常量写错就在启动和测试里失败，不把坏交易带进游戏。 */
    public static void check() {
        if (CHESTS.isEmpty() || VILLAGERS.isEmpty()) {
            throw new IllegalStateException("卡包投放是空的");
        }
        if (!"ygomc:demo".equals(DEMO)) {
            throw new IllegalStateException("投放 id 必须是 jar 里的 ygomc:demo");
        }
        boolean pack = false;
        boolean box = false;
        for (int i = 0; i < CHESTS.size(); i++) {
            Chest chest = CHESTS.get(i);
            if (!chest.table().startsWith("minecraft:chests/")) {
                throw new IllegalStateException("不是原版箱子表：" + chest.table());
            }
            if (!(chest.chance() > 0f) || chest.chance() > 1f) {
                throw new IllegalStateException("概率要在 (0, 1]：" + chest);
            }
            if (chest.goods() == Goods.PACK) {
                pack = true;
            } else if (chest.goods() == Goods.BOX) {
                box = true;
            }
            for (int j = 0; j < i; j++) {
                Chest earlier = CHESTS.get(j);
                if (earlier.table().equals(chest.table()) && earlier.goods() == chest.goods()) {
                    throw new IllegalStateException("同一张表的同一种货物写了两次：" + chest.table());
                }
            }
        }
        if (!pack || !box) {
            throw new IllegalStateException("箱子里要有包，也要有盒");
        }
        boolean villagerPack = false;
        boolean villagerBox = false;
        for (VillagerDeal deal : VILLAGERS) {
            if (deal.level() < 1 || deal.level() > 5) {
                throw new IllegalStateException("村民等级要在 1 到 5：" + deal);
            }
            checkPrice(deal.emeralds(), deal.maxUses(), deal.xp(), deal.priceMultiplier());
            if ("minecraft:cartographer".equals(deal.profession()) && deal.level() == 2 && deal.goods() == Goods.PACK) {
                villagerPack = true;
            }
            if ("minecraft:cartographer".equals(deal.profession()) && deal.level() == 4 && deal.goods() == Goods.BOX) {
                villagerBox = true;
            }
        }
        if (!villagerPack || !villagerBox) {
            throw new IllegalStateException("制图师要卖包，也要卖盒");
        }
        int packEmeralds = -1;
        int boxEmeralds = -1;
        for (VillagerDeal deal : VILLAGERS) {
            if (!"minecraft:cartographer".equals(deal.profession())) {
                continue;
            }
            if (deal.goods() == Goods.PACK) {
                packEmeralds = deal.emeralds();
            } else if (deal.goods() == Goods.BOX) {
                boxEmeralds = deal.emeralds();
            }
        }
        if (boxEmeralds <= packEmeralds) {
            throw new IllegalStateException("盒要比单包贵");
        }
        if (WANDERERS.isEmpty()) {
            throw new IllegalStateException("流浪商人没有货");
        }
        for (WandererDeal deal : WANDERERS) {
            if (deal.goods() != Goods.PACK) {
                throw new IllegalStateException("流浪商人只卖单包");
            }
            checkPrice(deal.emeralds(), deal.maxUses(), deal.xp(), deal.priceMultiplier());
        }
    }

    private static void checkPrice(int emeralds, int maxUses, int xp, float priceMultiplier) {
        if (emeralds < 1 || maxUses < 1 || xp < 0 || priceMultiplier < 0f) {
            throw new IllegalStateException("交易价格不合法");
        }
    }
}
