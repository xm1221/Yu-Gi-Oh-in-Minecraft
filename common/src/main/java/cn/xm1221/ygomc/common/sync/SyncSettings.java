package cn.xm1221.ygomc.common.sync;

import java.util.ArrayList;
import java.util.List;

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
    /** 为真时，下面的非空列表排在 pin 前面；为假时整表替换。{@code ygomc/urls.json} 可以再盖一层。 */
    public boolean urlPrepend = false;
    public List<String> cardsCdbUrls = new ArrayList<>();
    public List<String> stringsConfUrls = new ArrayList<>();
    public List<String> scriptUrls = new ArrayList<>();
    public List<String> setUrls = new ArrayList<>();
    public List<String> picUrls = new ArrayList<>();

    public static SyncSettings defaults() {
        return new SyncSettings();
    }

    public int timeoutSeconds() {
        return timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    public int maxPicBytes() {
        return maxPicBytes >= 1024 ? maxPicBytes : 2_000_000;
    }

    public List<String> list(SyncUrls.Key key) {
        List<String> raw = switch (key) {
            case CARDS_CDB -> cardsCdbUrls;
            case STRINGS_CONF -> stringsConfUrls;
            case SCRIPTS -> scriptUrls;
            case SETS -> setUrls;
            case PICS -> picUrls;
        };
        return raw == null ? List.of() : raw;
    }
}
