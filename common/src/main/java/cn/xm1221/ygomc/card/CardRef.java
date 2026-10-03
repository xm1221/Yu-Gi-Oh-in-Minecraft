package cn.xm1221.ygomc.card;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一张卡的身份：{@code card_ref} 数据组件的值。
 *
 * <h2>为什么是「一个物品 + 一个组件」而不是一万多个物品（决策 D2）</h2>
 * 卡池有一万两千张以上，且会随数据更新继续增长。如果每张卡一个 {@code Item}，
 * 一来注册表会被撑爆、启动与网络同步都会明显变慢，二来删卡/改卡就要动模组版本。
 * 把卡号放进数据组件，模组只注册<b>一个</b> {@code CardItem}，
 * 卡名、卡图、卡文全部在运行期按 {@link #cardCode} 从数据包（cdb + pics）解析——
 * 换数据包等于换整副卡池，模组本身不用动。
 *
 * <h2>三个字段各自解决什么</h2>
 * <ul>
 *   <li>{@link #cardCode}：ocgcore 的卡号（cdb 主键），也就是「这是哪张卡」。
 *       名字故意不叫 {@code id}——它<b>不是</b>注册表里的那个 id，两者混用极易出错。</li>
 *   <li>{@link #rarity}：这一张是该卡的哪种闪。同一张卡号可以有多张不同稀有度的物品，
 *       它们的 {@code cardCode} 相同而 {@code rarity} 不同。</li>
 *   <li>{@link #variant}：同一卡号 + 同一稀有度下的「第几版」，
 *       用来区分异画（alternate art）、不同卡图版本、不同语言的印刷。
 *       默认 0 = 最初版。</li>
 * </ul>
 * 三者一起才唯一确定卡图上该画什么，所以它们必须同属一个组件：
 * 拆成三个组件会出现「改了一个忘了改另一个」的不一致窗口，
 * 而一个组件是原子的——客户端拿到的要么全是新的，要么全是旧的。
 *
 * @param cardCode 卡号（cdb 的 {@code datas.id}）
 * @param rarity   稀有度档位
 * @param variant  同卡同罕下的异画/版本序号，0 为最初版
 */
public record CardRef(int cardCode, CardRarity rarity, int variant) {

    /** 默认版本号，供便捷构造与旧数据升级使用。 */
    public static final int DEFAULT_VARIANT = 0;

    /**
     * 存档 / 数据包用。
     *
     * <p>{@code variant} 用 {@link Codec#optionalFieldOf} 带默认值：将来若给这个记录
     * 追加字段，旧存档里缺字段的条目仍然能读出来，不会因为一次小幅格式演进把
     * 玩家背包里的卡整批判为损坏。
     */
    public static final Codec<CardRef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("card_code").forGetter(CardRef::cardCode),
            CardRarity.CODEC.fieldOf("rarity").forGetter(CardRef::rarity),
            Codec.INT.optionalFieldOf("variant", DEFAULT_VARIANT).forGetter(CardRef::variant)
    ).apply(instance, CardRef::new));

    /**
     * 网络同步用。
     *
     * <p>{@code cardCode} 用 {@code VAR_INT}：多数卡号是 8 位十进制数（约 2^23 量级），
     * 变长编码常见情况下比定长 int 省 1 字节。单张卡省不了多少，但背包里塞满卡、
     * 或者整副卡组一次性同步时，这个差别是线性的。
     */
    public static final StreamCodec<ByteBuf, CardRef> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CardRef::cardCode,
            CardRarity.STREAM_CODEC, CardRef::rarity,
            ByteBufCodecs.VAR_INT, CardRef::variant,
            CardRef::new
    );

    /** @return 该卡号的最初版、指定稀有度的引用。 */
    public static CardRef of(int cardCode, CardRarity rarity) {
        return new CardRef(cardCode, rarity, DEFAULT_VARIANT);
    }
}
