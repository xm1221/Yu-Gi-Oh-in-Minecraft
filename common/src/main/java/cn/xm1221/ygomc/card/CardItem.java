package cn.xm1221.ygomc.card;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 卡牌物品——<b>整个模组只有这一个</b>物品类承载全部一万两千多张卡。
 *
 * <h2>为什么必须 {@code stacksTo(1)}（决策 D2 的直接后果）</h2>
 * 一张卡的身份完全由 {@link CardRef} 组件决定。如果允许堆叠，同一个
 * {@code ItemStack} 里的 64 张卡就必须共享同一份组件，也就是说它们只能是同一张卡——
 * 那等于把「堆叠」这件事的范围限制死，还会让「抽到第 30 张时组件怎么变」
 * 变成一堆积木一样的特例。更要紧的是实卡本来就是一张一张的：
 * 卡组、收藏册、卡图渲染都按单张卡建模。所以这里直接锁死上限 1，
 * 把「多张同卡」表达成多个 {@code ItemStack}，语义干净。
 *
 * <h2>为什么名字/卡文/卡图不在这里</h2>
 * 它们全部由 {@link CardRef#cardCode()} 在运行期从数据包反查
 * （卡名/卡文来自 {@code CardTextDb}，卡图来自 {@code pics.bin}，见 PLAN §5.1）。
 * 物品本身不持有任何与具体卡有关的状态，因此换数据包就等于换整副卡池，
 * 模组不需要重新编译。
 */
public class CardItem extends Item {

    /**
     * 注册用的工厂方法。
     *
     * <p>{@link Item.Properties} 在构造时就要求把默认组件定下来，而
     * {@link CardComponents#CARD_REF} 这时只是<b>已登记、尚未实例化</b>的
     * {@code RegistrySupplier}——注册表条目要等注册事件才真正被创建。
     * 所以这里不能直接 {@code .get()}（会抛 NPE），而是保留
     * {@link CardComponents#CARD_REF} 这个共享的组件类型，
     * 由创建 {@code ItemStack} 的一方负责塞入具体卡号。
     */
    public static CardItem createDefault() {
        return new CardItem(new Item.Properties().stacksTo(1));
    }

    public CardItem(Properties properties) {
        super(properties);
    }

    /**
     * 读取这张卡的引用。
     *
     * <p>这是全模组取卡的<b>唯一入口</b>。刻意返回 {@code null} 而不是
     * {@link java.util.Optional}：一整条渲染/组卡/决斗链路都要取它，
     * {@code Optional} 会在每个调用点强制加一层 {@code ifPresent}，
     * 而「没有 card_ref 的 card 物品」本来就只可能是创造模式刷错物品或数据损坏，
     * 那种情况应该当成异常快速暴露，不是正常分支。
     *
     * @return 卡片引用；该 stack 不带 {@code card_ref} 时返回 {@code null}
     */
    public static CardRef ref(ItemStack stack) {
        DataComponentType<CardRef> type = CardComponents.CARD_REF.get();
        return stack.get(type);
    }

    /**
     * 卡牌的名称由数据包解析出来的卡名决定，而不是物品的固定译名。
     *
     * <p>现在还没有加载数据包（M3 做），所以先返回默认名，
     * 让物品在创造模式里至少看得出是什么。
     */
    // TODO(M3): 接入 CardTextDb，按 CardRef.cardCode 返回真实卡名；
    //           译名缺失时回落到 cdb 里的原始名，再回落到默认名。
    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack);
    }

    /**
     * 悬停提示：以后要把卡文（效果文本）以及稀有度/异画信息放在这里。
     */
    // TODO(M3): 追加卡文（按行折行）、稀有度档位、异画版本；数据缺失时给出可诊断的提示。
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
    }

    // 卡牌本身没有右键行为：对局交互走决斗盘/决斗台，开包走卡包物品。
    // 刻意不覆写 use()，避免以后有人以为「右键卡片能做什么」。
}
