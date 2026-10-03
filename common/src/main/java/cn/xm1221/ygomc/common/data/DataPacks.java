package cn.xm1221.ygomc.common.data;

import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据包的运行期持有者。
 *
 * <h2>为什么要单独一层，而不是直接调 {@link DataPack#openDefault}</h2>
 * {@link DataPack} 本身刻意<b>不依赖任何平台 API</b>——它的定位逻辑
 * （{@link DataPack#candidates}）只做路径拼接，可以脱离 Minecraft 单独编译和测试
 * （{@code .agent/m1} 下的检查程序就是这么用的）。真正需要平台 API 的只有一件事：
 * 「游戏目录在哪」。把那一句隔离在这里，{@code DataPack} 就能保持纯粹。
 *
 * <h2>为什么懒加载，而不是在模组构造阶段就打开</h2>
 * 构造阶段调用平台 API 有踩空的风险，而数据包<b>不是</b>模组能不能加载的前提
 * （缺文件是正常情况，见 {@link DataPack} 的类注释）。
 * 所以打开时机定在「第一次真的要用」——但 {@link #init()} 会在启动时主动触发一次，
 * 这样用户在日志里立刻能看到数据包是否就绪，而不必等到某次交互失败才发现。
 *
 * <h2>线程安全</h2>
 * 卡牌查询会被客户端渲染线程和服务端逻辑线程同时调用（例如工具提示在渲染线程上
 * 拼卡名），所以这里用双重检查加锁。{@link DataPack} 内部只读、且卡图走
 * {@code FileChannel} 位置式读取，因此拿到实例之后不需要再加锁。
 */
public final class DataPacks {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/datapack");

    private static volatile DataPack instance;

    private DataPacks() {
    }

    /**
     * 取数据包，必要时打开。
     *
     * <p>永远返回非 null：数据包缺失时返回的是一个三份文件都为 null 的实例，
     * 调用方用 {@link DataPack#problems()} 或各 getter 的 null 判断即可，
     * 不必到处写空值检查。
     */
    public static DataPack get() {
        DataPack pack = instance;
        if (pack == null) {
            synchronized (DataPacks.class) {
                pack = instance;
                if (pack == null) {
                    pack = DataPack.openDefault(Platform.getGameFolder());
                    instance = pack;
                }
            }
        }
        return pack;
    }

    /**
     * 启动时主动打开一次并打日志。
     *
     * <p>由 {@link cn.xm1221.ygomc.common.Ygomc#init()} 调用。这里的意义只有「让状态可见」——
     * 数据包缺失是预期情况，所以只记日志，不抛异常、不阻断加载。
     */
    public static void init() {
        DataPack pack = get();
        if (pack.problems().isEmpty()) {
            LOGGER.info("数据包就绪: {}（{} 张卡，卡图 {}）",
                    pack.dir().toAbsolutePath(),
                    pack.cardData() == null ? "?" : pack.cardData().size(),
                    pack.cardImages() == null ? "不可用" : "已索引");
        } else {
            // 逐条打印：problems() 里第一条在「一个候选都没找到」时是多行文本，
            // 里面本来就带了每个查找过的位置，直接原样输出最有用。
            for (String problem : pack.problems()) {
                LOGGER.warn("数据包不完整: {}", problem);
            }
        }
    }

    /**
     * 关掉当前实例，下次 {@link #get()} 重新打开。
     *
     * <p>用户重新生成数据包后不必重启游戏。注意：已渲染并缓存的卡图纹理不会因此失效，
     * 那是渲染层要自己处理的事。
     */
    public static synchronized void reload() {
        DataPack old = instance;
        instance = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                LOGGER.warn("关闭旧数据包时出错（忽略）: {}", e.toString());
            }
        }
    }
}
