package cn.xm1221.ygomc.common.sync;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * Cloth Config：{@code config/ygomc-sync.json}。缺字段时用默认值。
 */
@Config(name = "ygomc-sync")
public class SyncConfig implements ConfigData {

    public boolean enabled = true;
    public boolean database = true;
    public boolean scripts = true;
    public boolean sets = true;
    public boolean picsOnDemand = true;
    /** 为真时客户端在卡库就绪后并行下载缺的卡图。 */
    public boolean picsPrefetchAll = true;
    /** TCP/TLS 连接超时（秒）。卡库、脚本、卡包的正文读取不看这个值。 */
    public int timeoutSeconds = 30;
    public int maxPicBytes = 2_000_000;
    public boolean allowReplaceNewerLocal = false;
    public boolean urlPrepend = false;
    public java.util.List<String> cardsCdbUrls = new java.util.ArrayList<>();
    public java.util.List<String> stringsConfUrls = new java.util.ArrayList<>();
    public java.util.List<String> scriptUrls = new java.util.ArrayList<>();
    public java.util.List<String> setUrls = new java.util.ArrayList<>();
    public java.util.List<String> picUrls = new java.util.ArrayList<>();

    private static ConfigHolder<SyncConfig> holder;

    public static synchronized void init() {
        if (holder == null) {
            holder = AutoConfig.register(SyncConfig.class, GsonConfigSerializer::new);
        }
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
