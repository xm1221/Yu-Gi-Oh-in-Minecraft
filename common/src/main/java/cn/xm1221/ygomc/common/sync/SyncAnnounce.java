package cn.xm1221.ygomc.common.sync;

import java.util.List;

/**
 * 决定哪一次同步状态该进聊天。标题画面上还没有玩家时不能把警告记成已经显示过。
 * 卡图下载走同一套规则：进度只刷动作条，开始、结束和失败才进聊天。
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

    public record PicCue(boolean log, boolean chat, boolean actionBar, boolean warn, boolean deliveredTerminal,
                          String messageKey, List<String> args) {
        public static final PicCue NONE = new PicCue(false, false, false, false, false, null, List.of());

        public Object[] argsArray() {
            return args == null ? new Object[0] : args.toArray();
        }
    }

    /**
     * 卡图进度。全库下载开始和结束各进一次聊天；中间只改动作条上的张数。
     * 按需补图不逐张刷聊天，排队清空后若有失败再补一句。
     */
    public static final class PicGate {
        private PicStatus.Kind kind = PicStatus.Kind.IDLE;
        private int seenDone = -1;
        private int seenFailed = -1;
        private int seenQueued = -1;
        private int ticksSinceBar;
        private boolean logged;
        private boolean held;

        public PicCue tick(PicStatus current, boolean playerPresent) {
            if (current == null) {
                current = PicStatus.idle();
            }
            if (current.kind() != kind) {
                kind = current.kind();
                logged = false;
                held = false;
                ticksSinceBar = 0;
                snapshot(current);
                if (kind == PicStatus.Kind.IDLE) {
                    return PicCue.NONE;
                }
                if (kind == PicStatus.Kind.DEMAND) {
                    return barOnly(current);
                }
                held = true;
            }
            if (held) {
                return deliver(transition(current), playerPresent);
            }
            if (kind == PicStatus.Kind.BULK || kind == PicStatus.Kind.DEMAND) {
                boolean changed = current.done() != seenDone
                        || current.failed() != seenFailed
                        || current.queued() != seenQueued;
                if (changed || ++ticksSinceBar >= 40) {
                    ticksSinceBar = 0;
                    snapshot(current);
                    return barOnly(current);
                }
            }
            return PicCue.NONE;
        }

        private PicCue deliver(PicCue cue, boolean playerPresent) {
            boolean log = !logged;
            logged = true;
            if (!playerPresent) {
                held = true;
                return new PicCue(log, false, false, cue.warn(), false, cue.messageKey(), cue.args());
            }
            held = false;
            return new PicCue(log, cue.chat(), cue.actionBar(), cue.warn(), true, cue.messageKey(), cue.args());
        }

        private void snapshot(PicStatus current) {
            seenDone = current.done();
            seenFailed = current.failed();
            seenQueued = current.queued();
        }

        private static PicCue transition(PicStatus current) {
            return switch (current.kind()) {
                case BULK -> cue(true, false, "ygomc.sync.msg.pics_progress",
                        num(current.done()), num(current.total()), num(current.failed()));
                case READY -> cue(true, false, "ygomc.sync.msg.pics_done",
                        num(current.done()), num(current.total()), num(current.failed()));
                case STOPPED -> cue(true, false, "ygomc.sync.msg.pics_stopped",
                        num(current.done()), num(current.total()));
                case FAILED -> current.bulk()
                        ? cue(true, true, "ygomc.sync.msg.pics_failed",
                        num(current.failed()), num(current.total()))
                        : cue(true, true, "ygomc.sync.msg.pics_demand_fail", num(current.failed()));
                case DEMAND -> barOnly(current);
                case IDLE -> PicCue.NONE;
            };
        }

        private static PicCue barOnly(PicStatus current) {
            if (current.kind() == PicStatus.Kind.DEMAND) {
                return cue(false, false, "ygomc.sync.msg.pics_demand",
                        num(current.queued()), num(current.done()));
            }
            return cue(false, false, "ygomc.sync.msg.pics_progress",
                    num(current.done()), num(current.total()), num(current.failed()));
        }

        private static PicCue cue(boolean chat, boolean warn, String key, String... args) {
            return new PicCue(false, chat, true, warn, false, key, List.of(args));
        }

        private static String num(int value) {
            return Integer.toString(value);
        }
    }
}
