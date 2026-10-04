package cn.xm1221.ygomc.common.card;

import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
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
     * 造一张具体的卡。
     *
     * <p>为什么需要这个工厂：{@link Item.Properties} 在构造时就要求把默认组件定下来，
     * 而那时 {@link CardComponents#CARD_REF} 还没实例化（见 {@link #createDefault()}）。
     * 所以「带卡号的卡」只能先造出 stack、再塞组件。这里是那条路径的<b>唯一</b>实现，
     * 免得每个调用点各写一遍 {@code set}——漏写一次就是一张查不到卡号、只显示卡背的卡。
     */
    public static ItemStack stack(int cardCode, CardRarity rarity) {
        ItemStack stack = new ItemStack(YgomcItems.CARD.get());
        stack.set(CardComponents.CARD_REF.get(), CardRef.of(cardCode, rarity));
        return stack;
    }

    /**
     * 卡牌的名称来自数据包，而不是物品的固定译名。
     *
     * <p>这正是「一个物品 + 数据组件区分全部卡」这个决策能成立的原因：
     * 一万五千多张卡不需要一万五千多个物品，它们的区别只在于组件里的卡号，
     * 而名字、卡文、卡图都能由卡号反查出来。
     *
     * <p>查不到时回落到 {@code super.getName}，而不是抛异常或显示空串。
     * 原因：数据包缺失是<b>预期情况</b>（卡图与卡文有版权，不随模组分发），
     * 这时应当看到一个能认出来的兜底名，而不是一个崩服或者一片空白。
     */
    @Override
    public Component getName(ItemStack stack) {
        CardRef ref = ref(stack);
        if (ref != null) {
            String name = DataPacks.get().nameOf(ref.cardCode());
            if (name != null && !name.isBlank()) {
                return Component.literal(name);
            }
        }
        return super.getName(stack);
    }

    /**
     * 悬停提示：稀有度、数值、卡文。
     *
     * <p>卡文是<b>在这里手工折行</b>的，而不是交给客户端。原因：
     * 按像素宽度折行要用 {@code Minecraft.getInstance().font}，那是个客户端专属类，
     * 在公共代码里引用它会让专用服务器在类加载阶段崩掉。而按「显示宽度」折行
     * （CJK 记 2 列、其余记 1 列）不需要任何客户端类，服务端也能算，
     * 结果与游戏内实际渲染相差很小。
     *
     * <p>换行要同时处理 {@code \n} 与 cdb 里常见的 {@code \r}——后者如果漏掉，
     * 会在提示里渲染成一个方块。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        CardRef ref = ref(stack);
        if (ref == null) {
            return;
        }
        int code = ref.cardCode();

        // 稀有度与异画版本只在非默认值时才显示，免得每张卡都挂一行废话。
        if (ref.rarity() != CardRarity.COMMON) {
            tooltip.add(Component.literal("稀有度：" + ref.rarity().getSerializedName())
                    .withStyle(ChatFormatting.GOLD));
        }
        if (ref.variant() != 0) {
            tooltip.add(Component.literal("异画版本 " + ref.variant())
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        String stats = describeStats(code);
        if (stats != null) {
            tooltip.add(Component.literal(stats).withStyle(ChatFormatting.GRAY));
        }

        String desc = DataPacks.get().descOf(code);
        if (desc == null || desc.isBlank()) {
            // 区分「卡不存在」和「卡存在但没有卡文」——两者的处理方式完全不同。
            boolean known = DataPacks.get().statsOf(code) != null;
            tooltip.add(Component.literal(known ? "（数据包里没有这张卡的卡文）"
                            : "（数据包里找不到卡号 " + code + "）")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            return;
        }
        for (String line : wrap(desc, DESC_WIDTH)) {
            tooltip.add(Component.literal(line).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** 卡文折行宽度（显示列数）。大约是一行中文 24 字。 */
    private static final int DESC_WIDTH = 48;

    /** 数值行。魔法/陷阱没有攻防与等级，就不显示这一行。 */
    private static String describeStats(int code) {
        CardDataDb.Stats s = DataPacks.get().statsOf(code);
        if (s == null) {
            return null;
        }
        int type = s.type();
        if ((type & CardDataDb.CardTypes.TYPE_MONSTER) == 0) {
            return (type & CardDataDb.CardTypes.TYPE_SPELL) != 0 ? "魔法卡" : "陷阱卡";
        }
        StringBuilder sb = new StringBuilder();

        // 属性与种族放在最前面：认一张怪兽卡最先要看的就是这两个，
        // 它们比攻守更能决定「这是张什么卡」。
        //
        // 查不到名字时整段略过而不是显示「未知」：魔法/陷阱卡的 race 与怪兽种族
        // 共用同一个字段，用的是另一套位（永续/装备/速攻…），
        // 那种情况下这一行本来就不该出现，而不是该显示成某个怪兽种族。
        String attribute = CardDataDb.Attributes.name(s.attribute());
        String race = CardDataDb.Races.name(s.race());
        if (attribute != null) {
            sb.append(attribute).append("属性");
        }
        if (race != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(race).append("族");
        }
        if (sb.length() > 0) {
            sb.append("    ");
        }

        if ((type & CardDataDb.CardTypes.TYPE_XYZ) != 0) {
            sb.append("阶级 ").append(s.level());
        } else if ((type & CardDataDb.CardTypes.TYPE_LINK) != 0) {
            // 连接怪兽没有等级也没有守备力，用 LINK 值代替
            sb.append("连接 ").append(s.level()).append("    ATK ").append(s.attack());
            return sb.toString();
        } else {
            sb.append("等级 ").append(s.level());
        }
        return sb.append("    ATK ").append(s.attack())
                .append(" / DEF ").append(s.defense()).toString();
    }

    /**
     * 按显示宽度折行。
     *
     * <p>先按已有换行符切段，再对每段按宽度切。刻意<b>不</b>在单词中间断开——
     * 英文卡文里断在词中间很难读，所以放不下时回退到最后一个空格；
     * 找不到空格（例如一长串中文）才硬断。
     */
    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\\r\\n|\\r|\\n")) {
            String rest = paragraph.trim();
            while (displayWidth(rest) > width) {
                int cut = Math.max(1, fitIndex(rest, width));
                out.add(rest.substring(0, cut).stripTrailing());
                rest = rest.substring(cut).stripLeading();
            }
            if (!rest.isEmpty()) {
                out.add(rest);
            }
        }
        return out;
    }

    /** 最多能放下多少个字符；能退到空格就退，避免劈开英文单词。 */
    private static int fitIndex(String s, int width) {
        int used = 0;
        int lastSpace = -1;
        for (int i = 0; i < s.length(); i++) {
            int w = charWidth(s.charAt(i));
            if (used + w > width) {
                return lastSpace > 0 ? lastSpace : i;
            }
            used += w;
            if (s.charAt(i) == ' ') {
                lastSpace = i;
            }
        }
        return s.length();
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += charWidth(s.charAt(i));
        }
        return w;
    }

    /** CJK 与全角标点占两列，其余占一列。够用，且不需要客户端字体。 */
    private static int charWidth(char c) {
        if (c < 0x1100) {
            return 1;
        }
        boolean wide = (c <= 0x115F)                    // 韩文字母
                || (c >= 0x2E80 && c <= 0xA4CF)         // CJK 部首、假名、汉字
                || (c >= 0xAC00 && c <= 0xD7A3)         // 谚文音节
                || (c >= 0xF900 && c <= 0xFAFF)         // CJK 兼容汉字
                || (c >= 0xFE30 && c <= 0xFE6F)         // CJK 兼容形式
                || (c >= 0xFF00 && c <= 0xFF60)         // 全角
                || (c >= 0xFFE0 && c <= 0xFFE6);
        return wide ? 2 : 1;
    }

    // 卡牌本身没有右键行为：对局交互走决斗盘/决斗台，开包走卡包物品。
    // 刻意不覆写 use()，避免以后有人以为「右键卡片能做什么」。
}
