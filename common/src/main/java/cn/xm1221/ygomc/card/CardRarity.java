package cn.xm1221.ygomc.card;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * 卡片的稀有度档位（物品层面的「是哪一种闪」）。
 *
 * <h2>为什么必须有一档 {@code COMMON}</h2>
 * 数据组件的字段不能为 null、也不该缺省：一张普通卡同样要能回答「你是什么稀有度」。
 * 把「普通」建成枚举里的一个正常取值，而不是 {@code null} 或 {@code Optional.empty()}，
 * 是为了让 {@link CardRef} 的三个字段全部无条件存在——序列化与网络同步代码因此
 * 不需要写任何分支，运行期也不会出现「这个组件少了一个字段」的半残状态。
 *
 * <h2>为什么用 {@link StringRepresentable} 而不是 ordinal</h2>
 * ordinal 会随枚举常量顺序漂移：以后在中间插入一个新档位（比如「金碎」），
 * 存档里所有旧卡的稀有度就会静默错位。{@link StringRepresentable#fromEnum} 用的是
 * 稳定的小写名，插值不影响旧数据；代价只是存档里字段稍长、可读性反而更好。
 *
 * <p>网络同步走 {@link ByteBufCodecs#idMapper}，也就是枚举序号——它只在同一次连接
 * 的双方之间使用，双方跑的是同一份代码，不存在版本漂移问题，因此这里用序号是安全的。
 * <b>不要把序号写进存档</b>，那是上面那段话要防的事。
 *
 * <p>这里只有「档位」，不含任何渲染信息：每一档叠哪些图层由 {@link RarityEntry} 描述。
 */
public enum CardRarity implements StringRepresentable {
    /** 普通（无闪工艺）。 */
    COMMON("common"),
    /** 亮面 / 银碎一类：单层全息。 */
    RARE("rare"),
    /** 面闪 / 金闪一类：多层全息叠加。 */
    SUPER("super"),
    /** 浮雕 / 立体：全息 + 凸版纹理层。 */
    ULTRA("ultra"),
    /** 顶罕（如二十周年红碎）。 */
    SECRET("secret");

    /** 存档 / 数据包用：按稳定名字读写，未知名字会让解析失败并报出位置，便于定位脏数据。 */
    public static final Codec<CardRarity> CODEC = StringRepresentable.fromEnum(CardRarity::values);

    /** 网络同步用：序号编码，见类注释里对「为什么这里用序号是安全的」的说明。 */
    public static final StreamCodec<ByteBuf, CardRarity> STREAM_CODEC =
            ByteBufCodecs.idMapper(CardRarity::byId, CardRarity::id);

    private final String name;

    CardRarity(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** @return 枚举序号，仅用于网络编码。 */
    public int id() {
        return ordinal();
    }

    /** @return 与 {@link #id()} 互逆；越界回落到 {@link #COMMON}，避免脏数据炸掉整包解析。 */
    public static CardRarity byId(int id) {
        CardRarity[] all = values();
        return id >= 0 && id < all.length ? all[id] : COMMON;
    }
}
