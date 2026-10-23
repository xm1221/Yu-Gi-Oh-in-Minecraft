package cn.xm1221.ygomc.common.sync;

/**
 * 什么时候下载、什么时候留着玩家自己放的文件。规则在计划第 4.2 节和第 8 节。
 */
public final class SyncPolicy {

    public enum Database {
        /** pin 与清单、三个 bin 的哈希都一致。 */
        SKIP,
        /** pin 没变，但本地文件和清单对不上，或没有清单。不盖。 */
        KEEP_LOCAL,
        /** 文件缺失、魔数坏了，或 pin 变了。 */
        FETCH
    }

    public enum Scripts {
        /** 没有清单，目录里已经有 constant.lua。 */
        SKIP_PLAYER,
        /** 清单里的脚本 sha 就是当前 pin，哨兵也在。 */
        SKIP_PIN,
        FETCH
    }

    private SyncPolicy() {
    }

    public static Database database(boolean binsValid, boolean hashesMatchManifest,
                                    String manifestSha, String pinSha, boolean allowReplace) {
        if (!binsValid) {
            return Database.FETCH;
        }
        boolean pinChanged = manifestSha != null && !manifestSha.isBlank() && !manifestSha.equalsIgnoreCase(pinSha);
        if (pinChanged) {
            return Database.FETCH;
        }
        if (manifestSha == null || manifestSha.isBlank()) {
            return allowReplace ? Database.FETCH : Database.KEEP_LOCAL;
        }
        if (hashesMatchManifest) {
            return Database.SKIP;
        }
        return allowReplace ? Database.FETCH : Database.KEEP_LOCAL;
    }

    public static Scripts scripts(boolean sentinelPresent, String manifestSha, String pinSha) {
        if (!sentinelPresent) {
            return Scripts.FETCH;
        }
        if (manifestSha == null || manifestSha.isBlank()) {
            return Scripts.SKIP_PLAYER;
        }
        if (manifestSha.equalsIgnoreCase(pinSha)) {
            return Scripts.SKIP_PIN;
        }
        return Scripts.FETCH;
    }
}
