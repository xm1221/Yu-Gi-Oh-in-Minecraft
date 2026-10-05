package cn.xm1221.ygomc.common.duel;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * 服务端配置（<b>Cloth Config / AutoConfig</b>）。
 *
 * <p>为什么不自己写：第一版我手搓了一个 {@code config/ygomc.json} 的读写
 * （Gson 解析 + mtime 判断重读 + 自己写默认文件），咩咩一句话点掉——
 * 「配置用 cloth config，乱写」。配置这件事有现成的、整个模组圈通用的实现，
 * 自己造一份只会多出一套要维护、要单测、语义还跟别人不一样的读写。
 *
 * <p>文件位置就是 AutoConfig 的约定：服务器目录下的 {@code config/ygomc.json}。
 * 这一项是<b>规则</b>（超时判负）而不是客户端外观，所以以服务端的值为准。
 */
@Config(name = "ygomc")
public class DuelConfig implements ConfigData {

    /** 超时判负的默认秒数。咩咩定的：默认 100 秒。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 100;

    /**
     * 有人轮到该他操作却一直不动，多少秒后判他负。
     *
     * <p>超时只<b>判负</b>，绝不替他作答——不许替玩家做决定。
     * 0 或负数会被 {@link #saneSeconds} 当作「没配」，退回默认值。
     */
    public int duelTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    private static ConfigHolder<DuelConfig> holder;

    /** 注册配置。由公共入口 {@code Ygomc.init()} 在启动时调一次（两个平台都走那里）。 */
    public static synchronized void init() {
        if (holder == null) {
            holder = AutoConfig.register(DuelConfig.class, GsonConfigSerializer::new);
        }
    }

    /** 当前配置。 */
    public static DuelConfig get() {
        init();
        return holder.getConfig();
    }

    /** 生效的超时判负秒数。 */
    public static int timeoutSeconds() {
        return saneSeconds(get().duelTimeoutSeconds);
    }

    /**
     * 0 或负数当作没配，退回默认。
     *
     * <p>抽成纯函数是为了能离线钉住它：配置文件是手写的，
     * 一个 0 秒判负的值只会让人莫名其妙地输掉，不能照单全收。
     */
    public static int saneSeconds(int raw) {
        return raw > 0 ? raw : DEFAULT_TIMEOUT_SECONDS;
    }
}
