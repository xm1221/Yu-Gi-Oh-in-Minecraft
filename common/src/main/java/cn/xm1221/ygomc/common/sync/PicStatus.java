package cn.xm1221.ygomc.common.sync;

/**
 * 卡图下载进度的快照。工作线程只写这个对象，不碰客户端。
 *
 * @param bulk 这一条是全库下载，而不是看卡时的按需补图
 * @param done 全库时是已结束的张数（含跳过）；按需时是本会话已完成的张数
 * @param skipped 全库开始时就已经在本地或负缓存里的张数
 */
public record PicStatus(Kind kind, boolean bulk, int done, int total, int failed, int skipped, int queued) {

    public enum Kind {
        IDLE, BULK, DEMAND, READY, STOPPED, FAILED
    }

    public static PicStatus idle() {
        return new PicStatus(Kind.IDLE, false, 0, 0, 0, 0, 0);
    }
}
