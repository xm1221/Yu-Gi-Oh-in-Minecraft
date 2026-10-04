package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.ocg.msg.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code SELECT_SUM}（按合计值选卡）的求解与校验。
 *
 * <p>内核出处 {@code playerop.cpp:640-725}（{@code select_sum_check1} 在 640-648，
 * 应答解析在 692-717）。每张候选卡带一个 {@code sum_param}，
 * 要选出若干张，使<b>所有计入项各取一个值后的总和恰好等于 {@code acc}</b>。
 *
 * <h2>问题模型与自动应答共用同一个求解器</h2>
 * 这个类同时服务两条路：{@link PlayerResponder} 在界面答不了时拿它兜底，
 * {@link cn.xm1221.ygomc.common.duel.DuelQuestion} 拿它算「默认取向」。
 * 两边共用一个 {@link #pickIndices}，所以「默认答哪个」与「求解器会答哪个」
 * 在构造上就不可能不一致——而这正是离线交叉验证能成立的前提。
 *
 * <h2>三个容易写错的点</h2>
 * <ol>
 *   <li><b>{@code sum_param} 里装的是两个值，不是一个</b>：低 16 位是 {@code op1}、
 *       高 16 位是 {@code op2}；若 {@code op2} 的最高位为 1，则整个 31 位是一个值、
 *       {@code op2 = 0}（{@code field.cpp:2926}）。所以「这张卡的和是 6」可能只是
 *       它两个可选值之一。</li>
 *   <li><b>强制选的卡不出现在应答里，但要占位</b>：内核读应答是从
 *       {@code bvalue[mcount + 1]} 开始（{@code playerop.cpp:704-705}，
 *       {@code mcount} = 强制卡数），{@code bvalue[1..mcount]} 这 mcount 个字节
 *       <b>根本没被读过</b>；数量校验是 {@code [min + mcount, max + mcount]}
 *       （{@code playerop.cpp:697}），即 {@code bvalue[0]} 把强制卡也算进去。
 *       当 {@code mcount == 0} 时它才退化成「普通多选」{@code [数量, 下标…]}——
 *       只在没有任何强制卡的局面上验证过的话，这个坑完全看不出来。</li>
 *   <li><b>合法性是「恰好等于 acc」而不是「不超过 acc」</b>，且要存在一种逐项取
 *       {@code op1}/{@code op2} 的取法。所以不能贪心累加，必须真找一个组合。</li>
 * </ol>
 *
 * <p>本类把内核的校验函数<b>照搬</b>过来当验证器，搜到的候选必须通过它才返回。
 * 这样就不必去推理那个 {@code opmin} 续参条件——它由验证器负责，
 * 而不是靠我理解对了它。
 *
 * <h2>为什么按「选中数量」从小到大枚举</h2>
 * 逐项 include-first 的深度优先会先把 22 层全走完才回溯（2^22 远超任何节点上限），
 * 于是最简单的答案反而最后才被找到、实际表现为「找不到」。
 * 改成先试 1 张、再试 2 张……第一个解通常在 k=1 或 k=2 命中。
 */
public final class SumSelect {

    /** 组合枚举的节点上限。超过就明确失败，不返回一个确定会被拒的答案。 */
    private static final int NODE_LIMIT = 200_000;

    private SumSelect() {
    }

    /** {@code get_sum_params} 的移植：一个 {@code sum_param} 拆成两个可选值。 */
    public static int[] params(int sumParam) {
        int op1 = sumParam & 0xFFFF;
        int op2 = (sumParam >>> 16) & 0xFFFF;
        if ((op2 & 0x8000) != 0) {
            op1 = sumParam & 0x7FFFFFFF;
            op2 = 0;
        }
        return new int[]{op1, op2};
    }

    /**
     * {@code select_sum_check1} 的移植：是否存在逐项取 {@code op1}/{@code op2}
     * 的取法使总和恰好为 {@code acc}。
     *
     * @param sumParams 计入项的<b>原始</b> {@code sum_param}，顺序与内核的
     *                  {@code oparam} 一致（强制卡在前）
     */
    public static boolean check(int[] sumParams, int acc) {
        return check(sumParams, 0, acc, 0xFFFF);
    }

    private static boolean check(int[] p, int index, int acc, int opmin) {
        if (acc == 0 || index == p.length) {
            return false;
        }
        int[] v = params(p[index]);
        int op1 = v[0];
        int op2 = v[1];
        if (index == p.length - 1) {
            return (acc == op1 && acc + opmin > op1)
                    || (op2 != 0 && acc == op2 && acc + opmin > op2);
        }
        return (acc > op1 && check(p, index + 1, acc - op1, Math.min(op1, opmin)))
                || (op2 > 0 && acc > op2 && check(p, index + 1, acc - op2, Math.min(op2, opmin)));
    }

    /**
     * 找一组可选的候卡下标，使总和恰好为 {@code acc}。
     *
     * @param forcedParams    强制卡的 {@code sum_param}（不参与选择，但计入总和）
     * @param candidateParams 候卡的 {@code sum_param}
     * @param min             可选张数下限（<b>不含</b>强制卡，即内核消息里的 min）
     * @param max             可选张数上限（同上）
     * @return 选中的候卡下标（升序）；无解返回 {@code null}
     */
    public static int[] pickIndices(int[] forcedParams, int[] candidateParams,
                                   int acc, int min, int max) {
        int mcount = forcedParams.length;
        int[] all = new int[mcount + candidateParams.length];
        System.arraycopy(forcedParams, 0, all, 0, mcount);
        System.arraycopy(candidateParams, 0, all, mcount, candidateParams.length);

        int kMin = Math.max(0, min);
        int kMax = Math.min(candidateParams.length, max);
        int[] nodes = {0};
        for (int k = kMin; k <= kMax; k++) {
            int[] pick = new int[k];
            List<Integer> hit = comb(all, mcount, acc, pick, 0, 0, nodes);
            if (hit != null) {
                int[] out = new int[k];
                for (int i = 0; i < k; i++) {
                    out[i] = hit.get(i);
                }
                return out;
            }
            if (nodes[0] > NODE_LIMIT) {
                break;
            }
        }
        return null;
    }

    /**
     * 把一个解编成内核要的应答字节。
     *
     * <pre>
     * [0]            = mcount + k        ─ 总数把强制卡也算进去（playerop.cpp:697）
     * [1 .. mcount]  = 0                 ─ 强制卡的占位，内核根本不读（704-705）
     * [1+mcount ..]  = 选中的候卡下标
     * </pre>
     */
    public static byte[] encode(int mcount, int[] picked) {
        byte[] resp = new byte[1 + mcount + picked.length];
        resp[0] = (byte) (mcount + picked.length);
        for (int i = 0; i < picked.length; i++) {
            resp[1 + mcount + i] = (byte) picked[i];
        }
        return resp;
    }

    /**
     * 求一个合法应答。
     *
     * @throws IllegalStateException 在节点上限内没找到合法组合，或遇到还没核实的
     *         「无上限」分支（{@code flag != 0}）。宁可明确失败，也不要返回一个
     *         会被 {@code MSG_RETRY} 打回的答案——那会变成卡住而不是报错。
     */
    public static Responder.Response solve(Msg.SelectSum m) {
        if (m.unlimited()) {
            // flag != 0 对应内核 max == 0 的另一条分支（playerop.cpp:718 起），
            // 它的校验是「和落在 [最小值之和, 最大值之和] 区间内」而不是「恰好等于 acc」，
            // 尚未逐行核实，不能凭猜作答。
            throw new IllegalStateException("SELECT_SUM 的无上限分支（flag=" + m.flag()
                    + "）还没核实过校验规则，不能凭猜作答");
        }
        int mcount = m.mustCount();
        int[] forced = new int[mcount];
        for (int i = 0; i < mcount; i++) {
            forced[i] = m.mustSelect()[i].sumParam();
        }
        int[] cand = new int[m.selectableCount()];
        for (int i = 0; i < cand.length; i++) {
            cand[i] = m.selectable()[i].sumParam();
        }
        int[] pick = pickIndices(forced, cand, m.acc(), m.min(), m.max());
        if (pick == null) {
            throw new IllegalStateException(String.format(
                    "SELECT_SUM 没找到合法组合（acc=%d min=%d max=%d must=%d 候选=%d）",
                    m.acc(), m.min(), m.max(), mcount, cand.length));
        }
        return Responder.Response.of(encode(mcount, pick));
    }

    /**
     * 枚举候卡里大小为 {@code pick.length} 的组合，返回第一个通过内核校验的。
     *
     * @return 选中的候卡下标（相对候卡表，不含强制卡）；没有返回 null
     */
    private static List<Integer> comb(int[] all, int mustItems, int acc,
                                      int[] pick, int depth, int start, int[] nodes) {
        if (depth == pick.length) {
            if (++nodes[0] > NODE_LIMIT) {
                return null;
            }
            int[] ps = new int[mustItems + pick.length];
            System.arraycopy(all, 0, ps, 0, mustItems);
            for (int i = 0; i < pick.length; i++) {
                ps[mustItems + i] = all[mustItems + pick[i]];
            }
            if (!check(ps, acc)) {
                return null;
            }
            List<Integer> out = new ArrayList<>(pick.length);
            for (int v : pick) {
                out.add(v);
            }
            return out;
        }
        for (int i = start; i < all.length - mustItems; i++) {
            pick[depth] = i;
            List<Integer> r = comb(all, mustItems, acc, pick, depth + 1, i + 1, nodes);
            if (r != null) {
                return r;
            }
            if (nodes[0] > NODE_LIMIT) {
                return null;
            }
        }
        return null;
    }
}
