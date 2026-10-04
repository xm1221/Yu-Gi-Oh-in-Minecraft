package cn.xm1221.ygomc.common.command;

import cn.xm1221.ygomc.common.card.DeckData;
import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.deck.BuiltinDecks;
import cn.xm1221.ygomc.common.deck.DeckLibrary;
import cn.xm1221.ygomc.common.deck.DeckValidator;
import cn.xm1221.ygomc.common.duel.AutoPlayer;
import cn.xm1221.ygomc.common.duel.DuelRoom;
import cn.xm1221.ygomc.common.duel.DuelSnapshotProbe;
import cn.xm1221.ygomc.common.duel.DuelStreamRecorder;
import cn.xm1221.ygomc.common.ocg.DeclareCardName;
import cn.xm1221.ygomc.common.ocg.DuelSession;
import cn.xm1221.ygomc.common.ocg.PlayerResponder;
import java.io.IOException;
import java.nio.file.Path;
import cn.xm1221.ygomc.common.ocg.DuelSessions;
import cn.xm1221.ygomc.common.ocg.DuelOptions;
import cn.xm1221.ygomc.common.ocg.FirstChoiceResponder;
import cn.xm1221.ygomc.common.ocg.Natives;
import cn.xm1221.ygomc.common.ocg.OcgDuel;
import cn.xm1221.ygomc.common.ocg.OcgEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@code /ygomc} 命令。
 *
 * <p>用途都是把「引擎链路」变得可观测、可操作，而不是给玩家玩的界面：
 * <ul>
 *   <li>{@code /ygomc status} —— 数据包、原生库、引擎各自是否就绪，正在跑哪几局；</li>
 *   <li>{@code /ygomc deck list} / {@code deck <名字>} —— 列出可用卡组、读一副并校验；</li>
 *   <li>{@code /ygomc selftest [名字]} —— 真的起一局打到收局并报出消息类型直方图；</li>
 *   <li>{@code /ygomc duel [名字]} —— 与 selftest 相同的开局路径，但不去刷日志，
 *       只把结果发回给发起者。留着它是因为它和决斗盘物品走的是同一条路。</li>
 * </ul>
 *
 * <h2>为什么对局不在这里同步跑</h2>
 * 一局要推进几千步、应答几百次。放在服务端主线程上跑会把整个服务器卡住。
 * 所以这里一律交给 {@link DuelSessions}，它每局一个专用线程（内核推荐用法），
 * 结果通过回调回来。要发回玩家时再绕回主线程——{@link CommandSourceStack}
 * 不是线程安全的，必须用 {@code source.getServer().execute(...)} 排队回去。
 */
public final class YgomcCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/command");

    /**
     * 询问策略的落盘位置。
     *
     * <p>服务器启动时定下来（世界目录下的 {@code ygomc-duel.properties}）；
     * 没启动过（例如纯客户端）时为 {@code null}，那时 {@code save} 会如实回报
     * 「只在本次运行内有效」，而不是假装写成功。
     */
    private static volatile java.nio.file.Path optionFile;

    // 兜底卡组已挪到 {@link cn.xm1221.ygomc.common.deck.BuiltinDecks}：
    // 原先这里是「同一张卡填满 40 格」，能开局但测试价值几乎为零
    // （没有召唤链、没有魔陷互动、没有额外卡组，很多询问根本触发不到）。

    private YgomcCommand() {
    }

    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) ->
                register(dispatcher));

        // 服务器一启动就自动跑一局，给开发/CI 用：dedicated server 上敲命令要占 stdin，
        // 自动化验证不方便，而「启动完就有一行自检结果」可以直接从日志里断言。
        LifecycleEvent.SERVER_STARTED.register(server -> {
            // 询问策略存在世界目录里：它是【服务器侧】的行为（谁来答那条询问），
            // 而不是客户端的观感设置，所以跟着存档走、而不是跟着客户端配置目录走。
            optionFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("ygomc-duel.properties");
            LOGGER.info("询问策略：{}", DuelOptions.load(optionFile));
            autoDeckAudit();
            autoSelftest();
        });
        // 停机时把在跑的对局都中止掉，免得守护线程被硬切断在半途。
        LifecycleEvent.SERVER_STOPPING.register(server -> DuelSessions.abortAll());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ygomc")
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(statusText()), false);
                    return 1;
                }))
                .then(Commands.literal("deck")
                        .then(Commands.literal("list").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(deckListText()), false);
                            return 1;
                        }))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    // greedyString 是刻意的：本机 526 副卡组里大量名字带中文、
                                    // 空格和括号（例如「阿莱中庸之道(既要韧性差也要多废件)」），
                                    // Brigadier 的 word()/string() 都读不了这些。
                                    String name = StringArgumentType.getString(ctx, "name");
                                    CommandSourceStack source = ctx.getSource();
                                    source.sendSuccess(() -> Component.literal(describeDeck(name)), false);
                                    return 1;
                                })))
                .then(Commands.literal("option")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "当前询问策略：" + DuelOptions.describe()
                                            + "\n/ygomc option chain <skip|always|never>"
                                            + "\n    skip=没有可发动的效果就不问（默认）"
                                            + "　always=每个时点都问　never=一律不问"
                                            + "\n/ygomc option autoforced <on|off>"
                                            + "\n    on=必发效果自动发动，不再询问"), false);
                            return 1;
                        })
                        .then(Commands.literal("chain")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String mode = StringArgumentType.getString(ctx, "mode");
                                            DuelOptions.ChainPrompt v = switch (mode) {
                                                case "skip" -> DuelOptions.ChainPrompt.SKIP_EMPTY;
                                                case "always" -> DuelOptions.ChainPrompt.ALWAYS;
                                                case "never" -> DuelOptions.ChainPrompt.NEVER;
                                                default -> null;
                                            };
                                            if (v == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "chain 只接受 skip / always / never，收到：" + mode));
                                                return 0;
                                            }
                                            DuelOptions.setChainPrompt(v);
                                            String saved = DuelOptions.save(optionFile);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "已设置：" + DuelOptions.describe() + "　" + saved), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("autoforced")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String mode = StringArgumentType.getString(ctx, "mode");
                                            boolean on;
                                            if (mode.equals("on")) {
                                                on = true;
                                            } else if (mode.equals("off")) {
                                                on = false;
                                            } else {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "autoforced 只接受 on / off，收到：" + mode));
                                                return 0;
                                            }
                                            DuelOptions.setAutoForcedChain(on);
                                            String saved = DuelOptions.save(optionFile);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "已设置：" + DuelOptions.describe() + "　" + saved), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("selftest")
                        .executes(ctx -> launch(ctx.getSource(), null, true))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> launch(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), true))))
                .then(Commands.literal("duel")
                        .executes(ctx -> launch(ctx.getSource(), null, false))
                        .then(Commands.literal("abort")
                                .executes(ctx -> abort(ctx.getSource())))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> launch(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), false)))));
    }

    /**
     * 中止自己正在进行的对局。
     *
     * <p>这是唯一的退出阀门：对局线程可能正卡在「等人作答」上，
     * 而内核不响应中断——没有这个命令，卡住的局只能靠重启服务器回收，
     * 期间它还一直占着 {@code MAX_CONCURRENT} 的名额。
     */
    private static int abort(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            reply(source, "这条命令要由玩家执行");
            return 0;
        }
        if (!DuelRoom.abortFor(player)) {
            reply(source, "你现在没有正在进行的对局");
            return 0;
        }
        reply(source, "已请求中止对局；卡在等你操作的那一步会带着异常解开。");
        return 1;
    }

    // ── 开局 ──────────────────────────────────────────────────────────────

    /**
     * 校验卡组、开局。
     *
     * @param verbose 是否连消息类型直方图一起报（自检要，普通对局不要）
     */
    private static int launch(CommandSourceStack source, String deckName, boolean verbose) {
        if (!OcgEngine.prepare()) {
            reply(source, "引擎不可用，无法开局：\n" + OcgEngine.problem());
            return 0;
        }

        DeckData deck;
        String label;
        if (deckName == null) {
            deck = BuiltinDecks.testPool();
            label = BuiltinDecks.TEST_POOL_LABEL;
        } else {
            try {
                deck = DeckLibrary.load(deckName);
                label = deckName;
            } catch (Exception e) {
                reply(source, "读取卡组失败：" + e.getMessage());
                return 0;
            }
        }

        // 有错就不开：内核收到未知卡号不会报错，只会把它当成一张全零属性的空卡，
        // 之后的对局行为无从预期——那种「能跑但结果没意义」比直接拒绝更糟。
        DeckValidator.Report report = DeckValidator.validate(deck, DataPacks.get());
        if (!report.ok()) {
            reply(source, "「" + label + "」不能用于开局：\n" + report.describe());
            return 0;
        }

        OcgDuel.DeckLoadout loadout = toLoadout(deck);
        try {
            DuelSessions.start(label, new OcgDuel.DeckLoadout[]{loadout, loadout},
                    new FirstChoiceResponder(DeclareCardName.packagedTable()), session -> {
                        String text = format(session, verbose);
                        LOGGER.info("对局结束：\n{}", text);
                        reply(source, text);
                    });
        } catch (IllegalStateException e) {
            reply(source, e.getMessage());
            return 0;
        }

        StringBuilder sb = new StringBuilder("已开局「").append(label).append("」")
                .append("（主 ").append(deck.main().size())
                .append(" / 额外 ").append(deck.extra().size()).append("）");
        if (!report.warnings().isEmpty()) {
            sb.append("，有 ").append(report.warnings().size()).append(" 项提示，用 /ygomc deck ")
              .append(label).append(" 查看");
        }
        sb.append("\n双方都用同一副卡组，由「第一个合法项」策略自动应答——");
        sb.append("这是 M1 的观战形态，真正的双人对战在 M2。");
        reply(source, sb.toString());
        return 1;
    }

    /** 结算回调可能跑在自动对局线程上，所以必须排队回主线程再碰 {@link CommandSourceStack}。 */
    private static void reply(CommandSourceStack source, String text) {
        try {
            source.getServer().execute(
                    () -> source.sendSuccess(() -> Component.literal(text), false));
        } catch (RuntimeException e) {
            // 服务器正在关停时 execute 会拒绝新任务。此时命令源已经没意义了，
            // 结果本身已经写进日志，所以吞掉即可。
            LOGGER.debug("回包失败（服务器可能正在关停）：{}", e.toString());
        }
    }

    /**
     * 环境变量 {@code YGOMC_DECKAUDIT} 非空时，把卡组目录里每一副卡组都读一遍并校验，
     * 汇总打进日志。
     *
     * <p>放在游戏里而不是做一个独立命令行工具，是因为 {@code DeckData} 带着 Minecraft 的
     * 编解码器（存档与网络同步要用），脱开游戏就加载不了。与其为测试去拼一份
     * MC 的 classpath，不如直接走它真正运行的那条路——这样验的就是真代码。
     */
    private static void autoDeckAudit() {
        if (System.getenv("YGOMC_DECKAUDIT") == null) {
            return;
        }
        List<String> names = DeckLibrary.list();
        if (names.isEmpty()) {
            LOGGER.warn("卡组审计：卡组目录为空。查找过：{}",
                    DeckLibrary.candidates(dev.architectury.platform.Platform.getGameFolder()));
            return;
        }
        DataPack pack = DataPacks.get();
        int ok = 0;
        int warned = 0;
        List<String> rejected = new java.util.ArrayList<>();
        for (String name : names) {
            DeckData deck;
            try {
                deck = DeckLibrary.load(name);
            } catch (Exception e) {
                rejected.add(name + " —— 读取失败：" + e.getMessage());
                continue;
            }
            DeckValidator.Report report = DeckValidator.validate(deck, pack);
            if (!report.ok()) {
                rejected.add(name + " —— " + String.join("；", report.errors()));
            } else {
                ok++;
                if (!report.warnings().isEmpty()) {
                    warned++;
                }
            }
        }
        LOGGER.info("卡组审计：{} 副，可用 {}，带提示 {}，不可用 {}",
                names.size(), ok, warned, rejected.size());
        for (String line : rejected) {
            LOGGER.info("  ✗ {}", line);
        }
    }

    /**
     * 环境变量 {@code YGOMC_SELFTEST} 非空时自动跑一次自检。
     *
     * <p>这里用<b>环境变量</b>而不是系统属性是刻意的：Gradle 的 {@code runServer} 会把
     * 环境变量原样传给被 fork 出来的游戏进程，而 {@code -D} 系统属性只作用于 Gradle
     * 自己的 JVM，传不进游戏。用 {@code -D} 会得到一个「设了但没用」的假象。
     *
     * <p>不指定卡组名时优先用卡组目录里的第一副真实卡组——那比内置的 40 张相同卡
     * 有说服力得多（内置卡组走不到需要祭品/额外卡组的路径）。
     */
    private static void autoSelftest() {
        if (System.getenv("YGOMC_SELFTEST") == null) {
            return;
        }
        if (!OcgEngine.prepare()) {
            LOGGER.warn("自动自检跳过，引擎不可用：{}", OcgEngine.problem());
            return;
        }
        OcgDuel.DeckLoadout loadout = toLoadout(pickDeck("自动自检").deck());
        // 自动自检顺带跑快照探针。queryFieldInfo 这条路径此前在本项目里
        // 一次都没被执行过，而「从未跑过」和「跑过且正确」在编译期长得一模一样。
        DuelSnapshotProbe probe = new DuelSnapshotProbe();
        try {
            DuelSessions.start("selftest", new OcgDuel.DeckLoadout[]{loadout, loadout},
                    new FirstChoiceResponder(DeclareCardName.packagedTable()), probe,
                    session -> {
                        LOGGER.info("自动自检结果：\n{}", format(session, true));
                        LOGGER.info(probe.report());
                    });
        } catch (IllegalStateException e) {
            LOGGER.warn("自动自检无法开局：{}", e.getMessage());
        }

        if (System.getenv("YGOMC_PLAYER") != null) {
            startPlayerDriven(loadout);
        }
    }

    /** 选定的卡组与它的可读名字。 */
    public record DeckPick(DeckData deck, String label) {
    }

    /**
     * 决斗盘开局用的卡组。
     *
     * <p>与自动自检走<b>同一条</b>选择与校验策略，所以抽成一处：
     * 两处各写一遍的话，以后改「优先用哪副」只改一处就会出现两种行为，
     * 而且只在其中一条路径上表现出来。
     *
     * <p>归宿说明：放在命令类里是因为它要用 {@code FALLBACK_DECK}，
     * 而那是命令侧的常量；等卡组来源独立成组件时再搬走。
     */
    public static OcgDuel.DeckLoadout loadoutForDuel() {
        return toLoadout(pickDeck("决斗盘").deck());
    }

    /**
     * 人机对抗里<b>对手（AI）</b>用的卡组。
     *
     * <p>刻意固定成内置测试卡池，而不是跟着玩家那副走：
     * <ul>
     *   <li>牌路丰富——40 张全不重复 + 8 张额外，能摸到的卡种远多于任何一副预组；
     *   <li>可复现——对手的行为不随玩家选了什么卡组而变，
     *       出了问题先怀疑引擎或界面，而不是「这次对手抽到了什么」；
     *   <li>玩家那副仍然由玩家/卡组目录决定，改的只是对手。
     * </ul>
     *
     * <p>玩家想换掉对手的卡组时，改这一处即可。
     */
    public static OcgDuel.DeckLoadout opponentLoadoutForDuel() {
        return toLoadout(BuiltinDecks.testPool());
    }

    private static DeckPick pickDeck(String who) {
        String name = firstRealDeck();
        DeckData deck;
        String label;
        try {
            deck = name == null ? BuiltinDecks.testPool() : DeckLibrary.load(name);
            label = name == null ? BuiltinDecks.TEST_POOL_LABEL : name;
        } catch (Exception e) {
            LOGGER.warn("{}：读取卡组失败，改用内置卡组：{}", who, e.toString());
            deck = BuiltinDecks.testPool();
            label = BuiltinDecks.TEST_POOL_LABEL;
        }
        DeckValidator.Report report = DeckValidator.validate(deck, DataPacks.get());
        if (!report.ok()) {
            LOGGER.warn("{}：卡组「{}」校验不过，改用内置卡组\n{}", who, label, report.describe());
            deck = BuiltinDecks.testPool();
            label = BuiltinDecks.TEST_POOL_LABEL;
        }
        return new DeckPick(deck, label);
    }

    /**
     * 玩家驱动的自检：同一个卡组、同一副牌序，但应答改由「界面侧」给出——
     * 引擎侧完全不知道有界面，只是把 {@link Responder} 换成了会阻塞的那个。
     *
     * <p><b>期望结果与贪心自检逐字相同</b>（步数 / 应答次数 / 胜者 / 胜因）。
     * 理由是 {@code AutoPlayer} 用 {@code defaultChoice}，而它刻意对齐了贪心的优先序，
     * 所以这是一条<b>完全不同的代码路径</b>通往同一个终局：阻塞层、跨线程唤醒、
     * 问题建模、应答回拼全都参与了，任何一处丢问题或答错，终局就会不一样。
     * 两个数字不一样，就说明这条链路有问题。
     */
    private static void startPlayerDriven(OcgDuel.DeckLoadout loadout) {
        PlayerResponder player =
                new PlayerResponder(new FirstChoiceResponder(DeclareCardName.packagedTable()));
        AutoPlayer auto = new AutoPlayer(player, "selftest");
        auto.start();

        // 录制是可选的：YGOMC_RECORD 指向要写的文件。它和快照探针【叠着用】，
        // 不是二选一——一边要牌桌快照，一边要原始字节，两者互不干扰。
        OcgDuel.Observer observer = new DuelSnapshotProbe();
        DuelStreamRecorder recorder = null;
        String recordPath = System.getenv("YGOMC_RECORD");
        if (recordPath != null && !recordPath.isBlank()) {
            try {
                recorder = new DuelStreamRecorder(Path.of(recordPath), observer);
                observer = recorder;
            } catch (IOException e) {
                LOGGER.warn("消息流录制无法开始，本局不录：{}", e.toString());
            }
        }
        final DuelStreamRecorder rec = recorder;
        final OcgDuel.Observer obs = observer;

        try {
            DuelSessions.start("player", new OcgDuel.DeckLoadout[]{loadout, loadout},
                    player, obs,
                    session -> {
                        auto.close();
                        LOGGER.info("玩家驱动自检结果：\n{}", format(session, true));
                        LOGGER.info(player.report());
                        LOGGER.info("自动玩家：作答 {} 次，被拒 {} 次", auto.answered(), auto.failed());
                        if (rec != null) {
                            LOGGER.info(rec.report());
                            rec.close();
                        }
                    });
        } catch (IllegalStateException e) {
            auto.close();
            if (rec != null) {
                rec.close();
            }
            LOGGER.warn("玩家驱动自检无法开局：{}", e.getMessage());
        }
    }

    /** 卡组目录里的第一副卡组名；没有目录或目录为空时返回 null。 */
    private static String firstRealDeck() {
        List<String> names = DeckLibrary.list();
        return names.isEmpty() ? null : names.get(0);
    }

    // ── 报告 ──────────────────────────────────────────────────────────────

    private static String format(DuelSession session, boolean verbose) {
        OcgDuel.Outcome outcome = session.outcome();
        if (outcome == null || !outcome.won()) {
            String why = outcome == null ? session.failure() : outcome.error();
            return "「" + session.label() + "」未完成：" + why
                    + (outcome == null ? "" : "\n推进 " + outcome.steps() + " 步，应答 "
                    + outcome.queries() + " 次\n消息：" + outcome.histogram());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(session.label()).append("」收局：玩家 ").append(outcome.winner())
          .append(" 获胜（胜因 ").append(outcome.reason()).append("）")
          .append("\n推进 ").append(outcome.steps()).append(" 步，应答 ")
          .append(outcome.queries()).append(" 次，").append(session.millis()).append(" ms");
        if (verbose) {
            sb.append("\n消息：").append(outcome.histogram());
        }
        return sb.toString();
    }

    private static String statusText() {
        StringBuilder sb = new StringBuilder("ygomc 状态：");

        DataPack pack = DataPacks.get();
        sb.append("\n数据包：").append(pack.isComplete() ? "就绪" : "不完整")
          .append("（").append(pack.dir().toAbsolutePath()).append("）");
        for (String problem : pack.problems()) {
            sb.append("\n  - ").append(problem);
        }

        // 「已打包」和「已加载」是两件事：isBundled() 说 jar 里有没有原生库资源，
        // directory() 说有没有真的解出来过。分开报，否则「没打包」会被误读成「加载失败」。
        sb.append("\n原生库：").append(Natives.isBundled() ? "已打包进 jar" : "未打包（需外部提供）");
        if (Natives.directory() != null) {
            sb.append("，已解出到 ").append(Natives.directory());
        }
        if (Natives.problem() != null) {
            sb.append("\n  - ").append(Natives.problem());
        }

        sb.append("\n引擎：").append(OcgEngine.isReady() ? "就绪" : "不可用");
        if (OcgEngine.problem() != null) {
            sb.append("\n  - ").append(OcgEngine.problem());
        }

        sb.append("\n卡组目录：").append(DeckLibrary.directory() == null
                ? "未找到（查找过 " + DeckLibrary.candidates(
                        dev.architectury.platform.Platform.getGameFolder()) + "）"
                : DeckLibrary.directory() + "，" + DeckLibrary.list().size() + " 副");

        List<DuelSession> active = DuelSessions.active();
        sb.append("\n正在进行的对局：").append(active.isEmpty() ? "无" : active.size() + " 局");
        for (DuelSession session : active) {
            sb.append("\n  - ").append(session.describe());
        }
        for (DuelSession session : DuelSessions.recent(3)) {
            if (!session.isRunning()) {
                sb.append("\n  · 最近：").append(session.describe());
            }
        }
        return sb.toString();
    }

    private static String deckListText() {
        List<String> names = DeckLibrary.list();
        if (names.isEmpty()) {
            return "没有可用卡组。查找过："
                    + DeckLibrary.candidates(dev.architectury.platform.Platform.getGameFolder());
        }
        StringBuilder sb = new StringBuilder("可用卡组 ").append(names.size()).append(" 副：");
        int shown = 0;
        for (String name : names) {
            if (shown++ == 20) {
                sb.append(" …（用 /ygomc deck list 的完整输出见日志）");
                break;
            }
            sb.append("\n  ").append(name);
        }
        return sb.toString();
    }

    private static String describeDeck(String name) {
        DeckData deck;
        try {
            deck = DeckLibrary.load(name);
        } catch (Exception e) {
            return "读取卡组失败：" + e.getMessage();
        }
        return "「" + name + "」主 " + deck.main().size()
                + " / 额外 " + deck.extra().size()
                + " / 副 " + deck.side().size() + "\n"
                + DeckValidator.validate(deck, DataPacks.get()).describe();
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static OcgDuel.DeckLoadout toLoadout(DeckData deck) {
        return new OcgDuel.DeckLoadout(toArray(deck.main()), toArray(deck.extra()));
    }

    private static int[] toArray(List<Integer> codes) {
        int[] out = new int[codes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = codes.get(i);
        }
        return out;
    }

    private static List<Integer> toList(int[] codes) {
        List<Integer> out = new java.util.ArrayList<>(codes.length);
        for (int code : codes) {
            out.add(code);
        }
        return out;
    }
}
