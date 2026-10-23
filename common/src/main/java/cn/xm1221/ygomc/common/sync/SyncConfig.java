package cn.xm1221.ygomc.common.sync;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * Cloth Config（{@code config/ygomc-sync.json}）。缺字段时用默认值。
 *
 * <p>{@link #syncPolicyVersion} 用来一次性升级旧存档里的策略默认值。
 * c7ab700 起 {@code picsPrefetchAll} 默认改为 true（开局尽量拉全卡图），
 * 但旧 run 配置里常写死 {@code false}，会盖掉 Java 默认——进游戏就像“没全量同步”。
 * 缺版本号或版本偏低时，强制改回 true 并盖章；之后玩家再手动关掉不会被再次覆盖。
 */
@Config(name = "ygomc-sync")
public class SyncConfig implements ConfigData {

    /** 当前同步策略版本；升默认值时只加这一处。 */
    public static final int SYNC_POLICY_VERSION = 1;

    public boolean enabled = true;
    public boolean database = true;
    public boolean scripts = true;
    public boolean sets = true;
    public boolean picsOnDemand = true;
    /** 为真时客户端在开局尽量预取缺的卡图。 */
    public boolean picsPrefetchAll = true;
    /** TCP/TLS 连接超时（秒）；大图、脚本包另有更大的读取超时，见 HttpFetch。 */
    public int timeoutSeconds = 30;
    public int maxPicBytes = 2_000_000;
    public boolean allowReplaceNewerLocal = false;
    public boolean urlPrepend = false;
    public java.util.List<String> cardsCdbUrls = new java.util.ArrayList<>();
    public java.util.List<String> stringsConfUrls = new java.util.ArrayList<>();
    public java.util.List<String> scriptUrls = new java.util.ArrayList<>();
    public java.util.List<String> setUrls = new java.util.ArrayList<>();
    public java.util.List<String> picUrls = new java.util.ArrayList<>();

    /**
     * 策略版本。旧文件没有该字段时 Gson 读成 0，触发 {@link #migratePolicyIfNeeded()}。
     */
    public int syncPolicyVersion = 0;

    private static ConfigHolder<SyncConfig> holder;

    public static synchronized void init() {
        if (holder == null) {
            holder = AutoConfig.register(SyncConfig.class, GsonConfigSerializer::new);
            SyncConfig raw = holder.getConfig();
            if (raw.migratePolicyIfNeeded()) {
                holder.save();
            }
        }
    }

    /**
     * @return 是否改写了字段（调用方应 {@code holder.save()}）
     */
    boolean migratePolicyIfNeeded() {
        if (syncPolicyVersion >= SYNC_POLICY_VERSION) {
            return false;
        }
        // 旧默认曾是 false；开局全量预取是现行策略，缺版本号的存档一律打开一次。
        picsPrefetchAll = true;
        syncPolicyVersion = SYNC_POLICY_VERSION;
        return true;
    }

    public static SyncSettings current() {
        init();
        SyncConfig raw = holder.getConfig();
        SyncSettings settings = new SyncSettings();
        settings.enabled = raw.enabled;
        settings.database = raw.database;
        settings.scripts = raw.scripts;
        settings.sets = raw.sets;
        settings.picsOnDemand = raw.picsOnDemand;
        settings.picsPrefetchAll = raw.picsPrefetchAll;
        settings.timeoutSeconds = raw.timeoutSeconds;
        settings.maxPicBytes = raw.maxPicBytes;
        settings.allowReplaceNewerLocal = raw.allowReplaceNewerLocal;
        settings.urlPrepend = raw.urlPrepend;
        settings.cardsCdbUrls = raw.cardsCdbUrls;
        settings.stringsConfUrls = raw.stringsConfUrls;
        settings.scriptUrls = raw.scriptUrls;
        settings.setUrls = raw.setUrls;
        settings.picUrls = raw.picUrls;
        return settings;
    }
}
