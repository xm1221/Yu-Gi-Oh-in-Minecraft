package cn.xm1221.ygomc.common.sync;

/**
 * {@code config/ygomc-sync.json} 的值。和 {@code DuelConfig} 分开，那边只放规则。
 * 见计划第 4.3 节。
 */
public final class SyncSettings {

    public boolean enabled = true;
    public boolean database = true;
    public boolean scripts = true;
    public boolean sets = true;
    public boolean picsOnDemand = true;
    public boolean picsPrefetchAll = false;
    /** TCP/TLS 连接超时（秒）。大文件正文见 {@link HttpFetch#LARGE_BODY_TIMEOUT}。 */
    public int timeoutSeconds = 30;
    public int maxPicBytes = 2_000_000;
    public boolean allowReplaceNewerLocal = false;

    public static SyncSettings defaults() {
        return new SyncSettings();
    }

    public int timeoutSeconds() {
        return timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    public int maxPicBytes() {
        return maxPicBytes >= 1024 ? maxPicBytes : 2_000_000;
    }
}
