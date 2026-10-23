package cn.xm1221.ygomc.neoforge;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.pack.data.BoosterBoxDef;
import cn.xm1221.ygomc.common.pack.data.BoosterDef;
import cn.xm1221.ygomc.common.pack.data.CardSetDef;
import cn.xm1221.ygomc.common.pack.data.PackCatalog;
import cn.xm1221.ygomc.common.pack.data.PackRegistries;
import cn.xm1221.ygomc.common.sync.SetsPackFinder;
import net.minecraft.server.packs.PackType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;


/**
 * NeoForge 入口。
 *
 * <h2>为什么三个模块必须待在互不重叠的包里</h2>
 * 包布局是<b>刻意</b>分成 {@code .common} / {@code .fabric} / {@code .neoforge} 的，
 * 不能图省事把平台入口压平回 {@code cn.xm1221.ygomc}——那会让 NeoForge 起不来。
 *
 * <p>NeoForge 会给每个模组 jar 生成一份 {@code module-info}，把该 jar 里
 * <b>含有的每个包</b>都 export 出去。而 common 的类在 dev 环境下是作为
 * <b>独立的 jar</b>（{@code common/build/devlibs/ygomc-common-*-dev.jar}）
 * 挂在运行期 classpath 上的，NeoForge 会把它当成一个匿名模组
 * （日志里显示为 {@code generated_<hash>}）。
 *
 * <p>于是同一个包名出现在<b>两个具名 JPMS 模块</b>里，触发「拆分包」，
 * 直接崩在模块解析阶段：
 * <pre>
 *   java.lang.module.ResolutionException: Module ygomc contains package
 *   cn.xm1221.ygomc, module generated_8a40509 exports package
 *   cn.xm1221.ygomc to ygomc
 * </pre>
 * 这个错误<b>编译期完全看不到</b>——三个模块的 {@code compileJava} 全绿，
 * 只有真正启动游戏/服务端才会暴露。
 *
 * <p>分工后的包名互不重叠：common 在 {@code cn.xm1221.ygomc.common.*}，
 * 本类在 {@code cn.xm1221.ygomc.neoforge}，Fabric 侧在 {@code cn.xm1221.ygomc.fabric.*}。
 */
@Mod(Ygomc.MOD_ID)
public final class YgomcNeoForge {

    public YgomcNeoForge(IEventBus modBus) {
        modBus.addListener(YgomcNeoForge::registerPackRegistries);
        modBus.addListener(YgomcNeoForge::addPackFinders);
        NeoForge.EVENT_BUS.addListener(YgomcNeoForge::validatePacks);
        // NeoForge 在 mod 构造阶段就已经允许通过 DeferredRegister 登记内容
        // （真正的实例化发生在 RegisterEvent 中）。
        Ygomc.init();
    }

    private static void registerPackRegistries(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(PackRegistries.CARD_SET, CardSetDef.CODEC, CardSetDef.CODEC);
        event.dataPackRegistry(PackRegistries.BOOSTER, BoosterDef.DIRECT_CODEC, BoosterDef.DIRECT_CODEC);
        event.dataPackRegistry(PackRegistries.BOOSTER_BOX, BoosterBoxDef.DIRECT_CODEC, BoosterBoxDef.DIRECT_CODEC);
    }

    /**
     * 把 {@code <游戏目录>/ygomc/sets} 挂成服务端数据包。
     * 位置用 BOTTOM。世界 {@code datapacks/} 默认 TOP，后加载，盖过自动生成的卡包。
     * 计划第 6 节。目录这时候可以还不存在，同步线程稍后写好，下次重载再看见。
     */
    private static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        event.addRepositorySource(SetsPackFinder::load);
    }

    private static void validatePacks(AddReloadListenerEvent event) {
        PackCatalog.validate(event.getRegistryAccess());
    }
}
