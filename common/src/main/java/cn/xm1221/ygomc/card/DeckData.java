package cn.xm1221.ygomc.card;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * 一副卡组的卡号列表：主卡组 / 额外卡组 / 副卡组。
 *
 * <h2>为什么这里只存卡号，不存完整卡牌对象</h2>
 * 卡名、卡文、卡图都能由卡号从数据包反查得到，存卡号等于存了一份「外键」。
 * 好处有两个：一是存档极小（一副 60 张卡只是 60 个 int），
 * 二是数据包更新后卡文自动跟着变，不会出现「卡组里嵌着一年前的旧卡文」。
 *
 * <h2>为什么本轮不做校验</h2>
 * 校验规则（主卡组 40–60、额外 ≤15、副卡组 ≤15、同名 ≤3、禁限卡表、不可用卡 flag）
 * 依赖 cdb 与 {@code lflist.conf} 的完整加载，属于 M4 的组卡里程碑。
 * 本轮的契约只有「存得进、取得出、网络与存档都不丢字节」。
 * 一旦校验逻辑加进来，它会放在 {@code DeckValidator} 里，而<b>不是</b>塞进这个记录——
 * 记录保持成纯数据，才能被序列化、被测试、被 UI 直接读。
 *
 * @param main  主卡组卡号
 * @param extra 额外卡组卡号
 * @param side  副卡组卡号
 */
public record DeckData(List<Integer> main, List<Integer> extra, List<Integer> side) {

    /** 空卡组，用于新建卡组盒时的默认值。 */
    public static final DeckData EMPTY = new DeckData(List.of(), List.of(), List.of());

    /** 主卡组张数规则（M4 校验用；这里只是把领域常量放在数据旁边）。 */
    public static final int MAIN_MIN = 40;
    public static final int MAIN_MAX = 60;
    /** 额外 / 副卡组张数上限（M4 校验用）。 */
    public static final int EXTRA_MAX = 15;
    public static final int SIDE_MAX = 15;

    /** 存档 / 数据包用。 */
    public static final Codec<DeckData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.listOf().fieldOf("main").forGetter(DeckData::main),
            Codec.INT.listOf().fieldOf("extra").forGetter(DeckData::extra),
            Codec.INT.listOf().fieldOf("side").forGetter(DeckData::side)
    ).apply(instance, DeckData::new));

    /**
     * 网络同步用。
     *
     * <p>这里<b>故意</b>不用 {@link ByteBufCodecs#collection} 的自带上限版本：
     * 卡组长度上限由 {@link #MAIN_MAX} 等规则决定，而规则归 M4 的校验器管；
     * 现在写死一个数字，等规则改动时就会变成两个不同步的「真相源」。
     * 解码侧的上限防护等 M4 一并处理。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, DeckData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DeckData::main,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DeckData::extra,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DeckData::side,
            DeckData::new
    );

    /**
     * 紧凑构造器：把三个列表复制成不可变副本。
     *
     * <p>不这么做的话，组件一旦被 {@code ItemStack} 持有，外部仍能拿到内部列表去改，
     * 于是「同一个 ItemStack 的组件内容」会在无人察觉时变化——数据组件被当作
     * 不可变值使用，破坏这个假设会导致难以复现的 bug。
     */
    public DeckData {
        main = List.copyOf(main);
        extra = List.copyOf(extra);
        side = List.copyOf(side);
    }

    /** @return 全部卡号（主 + 额外 + 副），只读。 */
    public List<Integer> all() {
        List<Integer> out = new ArrayList<>(main.size() + extra.size() + side.size());
        out.addAll(main);
        out.addAll(extra);
        out.addAll(side);
        return List.copyOf(out);
    }

    /** @return 总张数。 */
    public int totalSize() {
        return main.size() + extra.size() + side.size();
    }

    /** @return 是否为空卡组。 */
    public boolean isEmpty() {
        return main.isEmpty() && extra.isEmpty() && side.isEmpty();
    }
}
