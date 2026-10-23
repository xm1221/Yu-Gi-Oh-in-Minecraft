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
    public boolean picsPrefetchAll = false;
    public int timeoutSeconds = 30;
    public int maxPicBytes = 2_000_000;
    public boolean allowReplaceNewerLocal = false;

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
        return settings;
    }
}
