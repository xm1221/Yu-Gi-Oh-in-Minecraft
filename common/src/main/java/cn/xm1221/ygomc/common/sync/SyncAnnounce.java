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
        private String step = "";
        private long done = Long.MIN_VALUE;
        private long total = Long.MIN_VALUE;
        private boolean logged;
        private boolean chatted;
        private boolean terminal;

        public Outcome tick(AssetSync.Status current, boolean playerPresent) {
            if (current == null || !current.announce()) {
                phase = current == null ? AssetSync.Phase.IDLE : current.phase();
                step = current == null ? "" : current.step();
                done = Long.MIN_VALUE;
                total = Long.MIN_VALUE;
                logged = true;
                chatted = true;
                terminal = true;
                return Outcome.NONE;
            }
            boolean phaseChanged = current.phase() != phase;
            boolean stepChanged = phaseChanged || !current.step().equals(step);
            boolean bytesChanged = current.doneBytes() != done || current.totalBytes() != total;
            if (phaseChanged) {
                phase = current.phase();
                logged = false;
                terminal = false;
            }
            if (stepChanged) {
                step = current.step();
                logged = false;
                chatted = false;
            }
            done = current.doneBytes();
            total = current.totalBytes();

            boolean warn = current.phase() == AssetSync.Phase.DEGRADED
                    || current.phase() == AssetSync.Phase.FAILED;
            boolean live = current.phase() == AssetSync.Phase.DOWNLOADING
                    || current.phase() == AssetSync.Phase.CONVERTING
                    || current.phase() == AssetSync.Phase.APPLYING;
            if (warn) {
                if (terminal) {
                    return Outcome.NONE;
                }
                boolean log = !logged;
                logged = true;
                if (!playerPresent) {
                    return new Outcome(log, false, false, current);
                }
                terminal = true;
                chatted = true;
                return new Outcome(log, true, true, current);
            }
            if (live) {
                if (!playerPresent) {
                    boolean log = !logged;
                    logged = true;
                    return new Outcome(log, false, false, current);
                }
                boolean chat = !chatted;
                boolean bar = chat || bytesChanged;
                boolean log = !logged;
                if (!log && !chat && !bar) {
                    return Outcome.NONE;
                }
                logged = true;
                chatted = true;
                return new Outcome(log, chat, bar, current);
            }
            if (terminal) {
                return Outcome.NONE;
            }
            boolean log = !logged;
            logged = true;
            if (!playerPresent) {
                return new Outcome(log, false, false, current);
            }
            terminal = true;
            return new Outcome(log, true, false, current);
        }
    }
}
