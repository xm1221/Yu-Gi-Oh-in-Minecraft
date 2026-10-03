package cn.xm1221.ygomc.neoforge;

import cn.xm1221.ygomc.common.Ygomc;
import net.neoforged.fml.common.Mod;

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

    public YgomcNeoForge() {
        // NeoForge 在 mod 构造阶段就已经允许通过 DeferredRegister 登记内容
        // （真正的实例化发生在 RegisterEvent 中）。
        Ygomc.init();
    }
}
