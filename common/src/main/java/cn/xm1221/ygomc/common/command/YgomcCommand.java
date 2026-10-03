package cn.xm1221.ygomc.common.command;

import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.ocg.FirstChoiceResponder;
import cn.xm1221.ygomc.common.ocg.Natives;
import cn.xm1221.ygomc.common.ocg.OcgDuel;
import cn.xm1221.ygomc.common.ocg.OcgEngine;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /ygomc} 命令。
 *
 * <p>M1 阶段只有两个子命令，作用都是「把引擎链路的状态和连通性变得可观测」：
 * <ul>
 *   <li>{@code /ygomc status} —— 数据包、原生库、引擎各自是否就绪，缺什么、去哪儿找；</li>
 *   <li>{@code /ygomc selftest} —— 真的起一局打到收局，报出推进步数、应答次数和
 *       消息类型直方图。这是 M1 的验收动作：它跑通就说明「引擎消息能推着对局走」。</li>
 * </ul>
 *
 * <h2>为什么 selftest 要另开线程</h2>
 * 一局要推进几千步、应答几百次，全在服务端主线程上跑会把整个服务器卡住
 * （M0 实测单局约 6 ms，但那是空载；真实服务器上不能赌）。
 * 内核本身是「每局一个专用线程」的模型，所以另开线程是它希望的用法。
 *
 * <p>结果要发回玩家时必须绕回主线程——{@link CommandSourceStack} 不是线程安全的。
 * 这里用 {@code source.getServer().execute(...)} 把回包排队到服务端线程上。
 */
public final class YgomcCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/command");

    /**
     * 自检用的卡组：40 张 4 星通常怪兽（卡号 14575467，2000/0，类型 {@code 0x11} = 怪兽|通常）。
     *
     * <p><b>为什么不是「随便挑一张强力怪兽」</b>：最初这里用的是 40 张青眼白龙
     * （89631139，8 星）。它能打完整局，但 8 星需要 2 个祭品，所以
     * {@code SELECT_IDLECMD} 的 summonable 列表<b>永远是空的</b>——
     * 场上永远没有怪兽 → {@code SELECT_BATTLECMD} 的 attackable 也永远是空的 →
     * 整局只能靠抽爆卡组收场。自检照样「通过」，可战斗、伤害、召唤、表示形式变更
     * 这一整条路径一条都没走到。换成能通常召唤的 4 星怪之后，对局才会真的打起来。
     *
     * <p>卡号是照着 {@code cards.bin} 筛出来的（{@code .agent/m1/findbeater.py}），
     * 筛选时特意排除了连接/超量/同调/融合/祭品/衍生物——这些的类型位里也有
     * 「怪兽」，只看这一个位会把它们当成能通常召唤的怪兽。
     */
    private static final int SELFTEST_CARD = 14575467;

    private static final int[] SELFTEST_DECK = new int[40];

    static {
        java.util.Arrays.fill(SELFTEST_DECK, SELFTEST_CARD);
    }

    /** 同一时刻只允许一个自检在跑：内核允许并发，但日志会互相穿插，不好读。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private YgomcCommand() {
    }

    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) ->
                register(dispatcher));

        // 服务器一启动就自动跑一局。给开发/CI 用：dedicated server 上敲命令要占 stdin，
        // 自动化验证不方便，而「启动完就有一行自检结果」是可以直接从日志里断言的。
        LifecycleEvent.SERVER_STARTED.register(server -> autoSelftest());
    }

    /**
     * 环境变量 {@code YGOMC_SELFTEST} 非空时自动跑一次自检。
     *
     * <p>这里用<b>环境变量</b>而不是系统属性是刻意的：Gradle 的 {@code runServer} 会把
     * 环境变量原样传给被 fork 出来的游戏进程，而 {@code -D} 系统属性只作用于 Gradle
     * 自己的 JVM，传不进游戏。用 {@code -D} 会得到一个「设了但没用」的假象。
     */
    private static void autoSelftest() {
        if (System.getenv("YGOMC_SELFTEST") == null) {
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            LOGGER.warn("已有自检在跑，跳过自动自检");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                LOGGER.info("自动自检结果：\n{}", runSelftest());
            } finally {
                RUNNING.set(false);
            }
        }, "ygomc-selftest-auto");
        worker.setDaemon(true);
        worker.start();
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ygomc")
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(statusText()), false);
                    return 1;
                }))
                .then(Commands.literal("selftest").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    if (!RUNNING.compareAndSet(false, true)) {
                        source.sendSuccess(() -> Component.literal("已经有一个自检在跑了"), false);
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("自检开始，结果会发在这里…"), false);
                    Thread worker = new Thread(() -> {
                        String report;
                        try {
                            report = runSelftest();
                        } finally {
                            RUNNING.set(false);
                        }
                        LOGGER.info("自检结果：\n{}", report);
                        source.getServer().execute(
                                () -> source.sendSuccess(() -> Component.literal(report), false));
                    }, "ygomc-selftest");
                    // 守护线程：服务器关停时不该被一个还在跑的对局拖住。
                    worker.setDaemon(true);
                    worker.start();
                    return 1;
                })));
    }

    /** 一行一条，拼成给玩家看的纯文本。 */
    private static String statusText() {
        StringBuilder sb = new StringBuilder("ygomc 状态：");

        DataPack pack = DataPacks.get();
        sb.append("\n数据包：").append(pack.isComplete() ? "就绪" : "不完整")
          .append("（").append(pack.dir().toAbsolutePath()).append("）");
        for (String problem : pack.problems()) {
            sb.append("\n  - ").append(problem);
        }

        sb.append("\n原生库：").append(Natives.isBundled() ? "已打包" : "未打包")
          .append("，").append(Natives.problem() == null ? "已加载" : "加载失败");
        if (Natives.directory() != null) {
            sb.append("（").append(Natives.directory()).append("）");
        }
        if (Natives.problem() != null) {
            sb.append("\n  - ").append(Natives.problem());
        }

        sb.append("\n引擎：").append(OcgEngine.isReady() ? "就绪" : "不可用");
        if (OcgEngine.problem() != null) {
            sb.append("\n  - ").append(OcgEngine.problem());
        }
        return sb.toString();
    }

    /** 起一局打到收局，返回人类可读的报告。任何失败都以文本形式返回，不抛给命令层。 */
    private static String runSelftest() {
        if (!OcgEngine.prepare()) {
            return "引擎不可用，无法自检：\n" + OcgEngine.problem();
        }
        long startedAt = System.nanoTime();
        OcgDuel.Outcome outcome = OcgDuel.playOut(
                new int[]{1, 2, 3, 4, 5, 6, 7, 8},
                new int[][]{SELFTEST_DECK, SELFTEST_DECK},
                new FirstChoiceResponder(),
                OcgDuel.DEFAULT_MAX_STEPS);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        if (!outcome.won()) {
            return "自检失败：" + outcome.error()
                    + "\n推进 " + outcome.steps() + " 步，应答 " + outcome.queries() + " 次，"
                    + millis + " ms"
                    + "\n消息：" + outcome.histogram();
        }
        return "自检通过：玩家 " + outcome.winner() + " 获胜（胜因 " + outcome.reason() + "）"
                + "\n推进 " + outcome.steps() + " 步，应答 " + outcome.queries() + " 次，"
                + millis + " ms"
                + "\n消息：" + outcome.histogram();
    }
}
