package cn.xm1221.ygomc.common.sync;

/**
 * 决定哪一次同步状态该进聊天。标题画面上还没有玩家时不能把警告记成已经显示过。
 */
public final class SyncAnnounce {

    private SyncAnnounce() {
    }

    public record Outcome(boolean log, boolean chat, boolean actionBar, AssetSync.Status status) {
        public static final Outcome NONE = new Outcome(false, false, false, null);
    }

    public static final class Gate {
        private AssetSync.Phase phase = AssetSync.Phase.IDLE;
        private boolean logged;
        private boolean delivered;

        public Outcome tick(AssetSync.Status current, boolean playerPresent) {
            if (current == null || !current.announce()) {
                phase = current == null ? AssetSync.Phase.IDLE : current.phase();
                logged = true;
                delivered = true;
                return Outcome.NONE;
            }
            if (current.phase() != phase) {
                phase = current.phase();
                logged = false;
                delivered = false;
            }
            if (delivered) {
                return Outcome.NONE;
            }
            boolean log = !logged;
            logged = true;
            if (!playerPresent) {
                return new Outcome(log, false, false, current);
            }
            delivered = true;
            boolean warn = current.phase() == AssetSync.Phase.DEGRADED
                    || current.phase() == AssetSync.Phase.FAILED;
            return new Outcome(log, true, warn, current);
        }
    }
}
