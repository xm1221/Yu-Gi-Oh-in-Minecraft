package cn.xm1221.ygomc.common.ocg;

/**
 * 询问策略的可调项。
 *
 * <h2>为什么需要它</h2>
 * 「是否发动效果」这类询问是按<b>时点</b>来的：每到一个时点内核就问一次。
 * 于是玩家会遇到「明明没有可以发动的效果，还是被问了好几遍」——
 * 而那些时点的合法答案只有一个（不发动），问与不问结果完全一样。
 *
 * <p>官方客户端正是这么处理的：{@code duelclient.cpp:1836-1844} 在
 * 「没有候选项、且没开『显示时点』」时<b>静默回 -1</b>（{@code return true} 表示
 * 一个像素都不画）。它同时在 {@code system.conf} 里给出这几个开关
 * （{@code autochain} / {@code showchain}，见 {@code game.cpp:1466-1471}），
 * 界面上还有「忽略时点 / 显示时点 / 可用时点」三个按钮
 * （{@code game.cpp:945-947}，状态在 {@code event_handler.cpp:164-184}）。
 * 这里把同一组语义做成配置，默认值与官方一致。
 *
 * <h2>为什么默认「没有候选项就不问」</h2>
 * 因为那个询问不携带任何信息：答案唯一，玩家点了也只是把「不发动」发回去。
 * 想看到每个时点（例如观察对手的空过）就把 {@link #setChainPrompt} 改成
 * {@link ChainPrompt#ALWAYS}，行为立刻回到「每次都问」。
 *
 * <h2>线程</h2>
 * 这些字段会被对局线程读、被命令线程写，取值都是单个 boolean/枚举引用，
 * 用 {@code volatile} 保证可见性即可，不需要锁——读到旧值最多是「这一条询问
 * 还按老规矩问」，下一刻就生效，不会算错应答。
 */
public final class DuelOptions {

    /** 遇到连锁/发动询问时的策略。 */
    public enum ChainPrompt {
        /** 没有候选项就不问，直接按「不发动」放过（官方默认）。 */
        SKIP_EMPTY,
        /** 每个时点都问，包括没有候选项的（官方的「显示时点」）。 */
        ALWAYS,
        /** 一律不问，全部自动放弃（官方的「忽略时点」）。 */
        NEVER
    }

    /** 对一条连锁询问要做的事。 */
    public enum ChainAction {
        /** 摆给玩家点。 */
        ASK,
        /** 不问，直接回「不发动」。 */
        DECLINE,
        /** 不问，直接选第一个必发效果。 */
        PICK_FIRST
    }

    private static volatile ChainPrompt chainPrompt = ChainPrompt.SKIP_EMPTY;

    /**
     * 必发效果自动发动（对应官方 {@code autochain}，默认关）。
     *
     * <p>开了之后「必须发动一个效果」的询问也不再摆出来，直接选第一个必发项。
     * 官方那一项默认也是关的——强制连锁虽然没得选，但玩家可能想看它发动。
     */
    private static volatile boolean autoForcedChain = false;

    private DuelOptions() {
    }

    /**
     * 这条连锁询问要不要真的摆给玩家。
     *
     * @param forced     是否含必发效果（{@code Msg.SelectChain.hasForced()}）
     * @param candidates 内核给出的可选连锁项数量（不含「不发动」）
     */
    public static ChainAction chainAction(boolean forced, int candidates) {
        if (forced) {
            // 必发：没有「不发动」这个合法答案，所以只可能是「问」或「替他选第一个」。
            return autoForcedChain ? ChainAction.PICK_FIRST : ChainAction.ASK;
        }
        return switch (chainPrompt) {
            case NEVER -> ChainAction.DECLINE;
            case ALWAYS -> ChainAction.ASK;
            // 没有候选项时答案唯一（不发动），问了也白问。
            case SKIP_EMPTY -> candidates > 0 ? ChainAction.ASK : ChainAction.DECLINE;
        };
    }

    public static ChainPrompt chainPrompt() {
        return chainPrompt;
    }

    public static void setChainPrompt(ChainPrompt v) {
        chainPrompt = v == null ? ChainPrompt.SKIP_EMPTY : v;
    }

    public static boolean autoForcedChain() {
        return autoForcedChain;
    }

    public static void setAutoForcedChain(boolean v) {
        autoForcedChain = v;
    }

    /** 当前配置的一行摘要，供命令回执与日志使用。 */
    public static String describe() {
        return "连锁询问=" + chainPrompt + "　必发自动发动=" + autoForcedChain;
    }

    /** 配置文件里 {@code chain} 键的取值。 */
    private static final String KEY_CHAIN = "chain";
    private static final String KEY_AUTOFORCED = "autoforced";

    /**
     * 从属性文件读回配置。<b>读不到就用默认值，不抛异常</b>。
     *
     * <p>配置读失败不该拦住开局：一个坏掉的 properties 文件若让服务器起不来，
     * 代价远大于「这次用的是默认询问策略」。所以这里的每条异常路径都只是
     * 退回默认值并留一行说明，由调用方决定要不要记日志。
     *
     * @return 一行说明（成功或失败原因），供日志使用
     */
    public static String load(java.nio.file.Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            resetToDefaults();
            return "没有配置文件，用默认值：" + describe();
        }
        java.util.Properties props = new java.util.Properties();
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (Exception e) {
            resetToDefaults();
            return "配置文件读取失败（" + e + "），用默认值：" + describe();
        }
        String chain = props.getProperty(KEY_CHAIN, "");
        switch (chain) {
            case "skip" -> setChainPrompt(ChainPrompt.SKIP_EMPTY);
            case "always" -> setChainPrompt(ChainPrompt.ALWAYS);
            case "never" -> setChainPrompt(ChainPrompt.NEVER);
            default -> setChainPrompt(ChainPrompt.SKIP_EMPTY);
        }
        setAutoForcedChain(Boolean.parseBoolean(props.getProperty(KEY_AUTOFORCED, "false")));
        return "已读取配置：" + describe();
    }

    /**
     * 把两项都退回出厂值。
     *
     * <p>{@code load} 的语义是「让内存里的配置反映这个文件」——文件缺了或者坏了，
     * 它反映的就是「一片空白」，也就是默认值。少了这一步，{@code load} 在
     * 坏文件上会<b>保留上一次运行期改的值</b>：玩家明明看到「读取失败，用默认值」，
     * 实际生效的却是上一局设的另一个值。日志与行为不一致是最难查的那类问题。
     */
    private static void resetToDefaults() {
        chainPrompt = ChainPrompt.SKIP_EMPTY;
        autoForcedChain = false;
    }

    /**
     * 把当前配置写回属性文件。
     *
     * <p>即使写失败也只是回一行说明：设置本身<b>已经生效</b>了，
     * 不该因为磁盘问题告诉玩家「没设置成功」——那是在撒谎。
     */
    public static String save(java.nio.file.Path file) {
        if (file == null) {
            return "没有指定配置文件，配置只在本次运行内有效";
        }
        java.util.Properties props = new java.util.Properties();
        props.setProperty(KEY_CHAIN, switch (chainPrompt) {
            case SKIP_EMPTY -> "skip";
            case ALWAYS -> "always";
            case NEVER -> "never";
        });
        props.setProperty(KEY_AUTOFORCED, Boolean.toString(autoForcedChain));
        try {
            java.nio.file.Path parent = file.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                props.store(w, "ygomc duel options");
            }
            return "已保存到 " + file.getFileName();
        } catch (Exception e) {
            return "配置已生效，但写文件失败（" + e + "），重启后会回到默认值";
        }
    }
}
