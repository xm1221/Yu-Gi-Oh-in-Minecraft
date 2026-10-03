package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.ocg.msg.Msg;

/**
 * 对引擎的询问给出应答。
 *
 * <p>这是「谁来替玩家做决定」的抽象点。M1 阶段只有一个
 * {@link FirstChoiceResponder}（永远选第一个合法项）；
 * 后续的牌桌界面会实现同一个接口——把 {@link #answer} 变成「挂起这局、
 * 等玩家点一下、再返回」，而 {@link OcgDuel} 完全不需要改。
 *
 * <p>被调用时引擎<b>一定</b>在等待应答（{@link Msg#isQuery()} 为 true），
 * 所以实现方不必自己判断这条消息要不要回。
 */
@FunctionalInterface
public interface Responder {

    /**
     * @return 应答内容；不允许返回 null
     * @throws UnsupportedOperationException 遇到本项目还没实现应答的询问类型时。
     *         宁可在这里明确失败，也不要回一个瞎猜的值——引擎会把非法应答变成
     *         {@code MSG_RETRY}，然后无限重问，最后表现为「卡住」而不是「报错」。
     */
    Response answer(Msg msg);

    /**
     * 一条应答。
     *
     * <p>引擎只有两个入口：{@code setResponseI(JI)} 和 {@code setResponseB(J[B)}。
     * 单选类用前者，多选类（选卡、选址）用后者，所以这里二选一。
     */
    record Response(int value, byte[] bytes) {

        public static Response of(int value) {
            return new Response(value, null);
        }

        public static Response of(byte[] bytes) {
            return new Response(0, bytes);
        }

        /** true 表示走 {@code setResponseB}。 */
        public boolean isBytes() {
            return bytes != null;
        }
    }
}
