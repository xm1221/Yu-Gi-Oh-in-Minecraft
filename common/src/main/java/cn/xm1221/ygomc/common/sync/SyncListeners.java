package cn.xm1221.ygomc.common.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * 同步线程不引用客户端类。客户端入口在这里登记「换上新文件之后清纹理」和「一张图下好了」。
 */
public final class SyncListeners {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/sync");
    private static final List<Runnable> APPLIED = new CopyOnWriteArrayList<>();
    private static final List<IntConsumer> PIC_READY = new CopyOnWriteArrayList<>();

    private SyncListeners() {
    }

    public static void onApplied(Runnable listener) {
        APPLIED.add(listener);
    }

    public static void fireApplied() {
        for (Runnable listener : APPLIED) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.warn("同步完成回调失败：{}", e.toString());
            }
        }
    }

    public static void onPicReady(IntConsumer listener) {
        PIC_READY.add(listener);
    }

    public static void firePicReady(int code) {
        for (IntConsumer listener : PIC_READY) {
            try {
                listener.accept(code);
            } catch (RuntimeException e) {
                LOGGER.warn("卡图就绪回调失败：{}", e.toString());
            }
        }
    }
}
