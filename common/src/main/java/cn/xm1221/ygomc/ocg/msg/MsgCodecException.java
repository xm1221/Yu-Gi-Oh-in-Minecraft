package cn.xm1221.ygomc.ocg.msg;

/**
 * 消息编解码失败。
 *
 * <p>只有两种触发原因，两者都必须让整条消息流<b>立刻停下</b>而不是继续往下走：
 *
 * <ol>
 *   <li><b>未知消息类型</b> —— 偏移处那一字节不是内核 {@code MSG_*} 里的任何一个。
 *       异常消息里带类型号与偏移，便于按需补解析分支；</li>
 *   <li><b>缓冲区越界</b> —— 按已知布局读字段时读过了 {@code buf} 的末尾。
 *       这种情况<b>绝不能</b>「尽力而为」返回一个长度：长度一旦算错，
 *       后面每一条消息都会错位，整个解析会永久跑偏。</li>
 * </ol>
 *
 * <p>异常消息里都包含 {@code type}（未知类型时是那个非法字节值，越界时是该消息的类型号）
 * 与 {@code offset}（消息首字节在缓冲里的下标），两者都能通过
 * {@link #type()} / {@link #offset()} 拿到。
 */
public class MsgCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 消息类型号；读不出类型时是 {@link #NO_TYPE}。 */
    private final int type;
    /** 消息首字节在缓冲里的偏移；未知时为 {@link #NO_OFFSET}。 */
    private final int offset;

    /** {@link #type()} 的哨兵值：还没读到类型字节就失败了。 */
    public static final int NO_TYPE = -1;
    /** {@link #offset()} 的哨兵值：偏移未知。 */
    public static final int NO_OFFSET = -1;

    public MsgCodecException(String message, int type, int offset) {
        super(message + " [type=" + type + " (" + MsgType.name(type) + "), offset=" + offset + "]");
        this.type = type;
        this.offset = offset;
    }

    public MsgCodecException(String message, int type, int offset, Throwable cause) {
        super(message + " [type=" + type + " (" + MsgType.name(type) + "), offset=" + offset + "]", cause);
        this.type = type;
        this.offset = offset;
    }

    /** 未知消息类型。 */
    public static MsgCodecException unknownType(int type, int offset) {
        return new MsgCodecException("未知消息类型", type, offset);
    }

    /** 缓冲区越界。 */
    public static MsgCodecException truncated(int type, int offset, int need, int available) {
        return new MsgCodecException("缓冲区越界：需要 " + need + " 字节，实际只剩 " + available + " 字节",
                type, offset);
    }

    /** 越界（已知具体字段名，便于定位）。 */
    public static MsgCodecException truncatedField(int type, int offset, String field,
                                                   int need, int available) {
        return new MsgCodecException("缓冲区越界：读取字段 " + field + " 需要 " + need
                + " 字节，实际只剩 " + available + " 字节", type, offset);
    }

    /** 消息类型号（越界时是本条消息的类型号）。 */
    public int type() {
        return type;
    }

    /** 消息首字节在缓冲里的偏移。 */
    public int offset() {
        return offset;
    }
}
