package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.OcgDuel;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

import java.util.ArrayList;
import java.util.List;

/**
 * 快照自检探针：验证「内核快照」这条链路真的能用、而且内容对得上。
 *
 * <h2>为什么要对账</h2>
 * {@code queryFieldInfo} 在本项目里此前<b>一次都没被调用过</b>（M1 只用消息流）。
 * 一条从未执行过的路径，编译通过说明不了任何事——这正是上一轮紫黑块的教训。
 * 所以这里不满足于「快照能解出来不报错」，而是拿它与<b>另一条独立路径</b>对账：
 *
 * <ul>
 *   <li>生命值：{@code MSG_LPUPDATE} 报绝对值，{@code MSG_DAMAGE/RECOVER/PAY_LPCOST}
 *       报增量。由这些消息推算出一个 LP，再和快照里的 LP 比。
 *       两者由内核的不同代码路径产生，同时错成一样的概率很低。</li>
 *   <li>结构：区域数量、以及「占用则位置必非零」。</li>
 * </ul>
 *
 * <p>注意这套对账能抓到的是<b>不一致</b>，抓不到「两边同时按同一个错误理解写」。
 * 但它至少把「快照根本没工作」和「快照能用但对不上」区分开了。
 */
public final class DuelSnapshotProbe implements OcgDuel.Observer {

    private static final int START_LP = 8000;
    private static final int MAX_REPORTED = 6;

    /** 由消息推算出的 LP，起点是开局生命值。 */
    private final int[] lpFromMessages = {START_LP, START_LP};
    /** 最后一次 LPUPDATE 报的绝对值；-1 表示没报过。 */
    private final int[] lpFromUpdate = {-1, -1};

    private final List<String> problems = new ArrayList<>();
    private final List<Integer> askedTypes = new ArrayList<>();

    private int queriesSeen;
    private int snapshots;
    private int lpChecks;
    private int lpMismatches;
    private int zoneChecks;
    private String lastBoard;
    private int lastBoardAt = -1;

    @Override
    public void onMessage(OcgDuel duel, Msg m, boolean awaitingAnswer) {
        trackLp(m);
        if (!awaitingAnswer) {
            return;
        }
        queriesSeen++;
        if (askedTypes.size() < 64) {
            askedTypes.add(m.type());
        }

        Msg.ReloadField field;
        try {
            field = duel.snapshot();
        } catch (RuntimeException e) {
            problem("第 " + queriesSeen + " 次提问时取快照失败：" + e);
            return;
        }
        if (field == null) {
            problem("第 " + queriesSeen + " 次提问时快照返回 null（内核没给内容）");
            return;
        }

        DuelBoard board;
        try {
            board = DuelBoard.of(field);
        } catch (RuntimeException e) {
            problem("第 " + queriesSeen + " 次提问时牌桌构造失败：" + e.getMessage());
            return;
        }

        snapshots++;
        lastBoard = board.describe();
        lastBoardAt = queriesSeen;

        for (int i = 0; i < 2; i++) {
            checkZones(board.playerAt(i), i);
            lpChecks++;
            int snapLp = board.playerAt(i).lp();
            if (snapLp != lpFromMessages[i]) {
                lpMismatches++;
                problem("LP 对不上 p" + i + "：快照=" + snapLp
                        + "，由消息推算=" + lpFromMessages[i]
                        + "，LPUPDATE 最后一次报的是 " + lpFromUpdate[i]
                        + "（第 " + queriesSeen + " 次提问）");
            }
        }
    }

    private void trackLp(Msg m) {
        switch (m.type()) {
            case MsgType.LPUPDATE -> {
                Msg.LpUpdate u = (Msg.LpUpdate) m;
                lpFromUpdate[u.player()] = u.lp();
                lpFromMessages[u.player()] = u.lp();
            }
            case MsgType.DAMAGE -> {
                Msg.Damage d = (Msg.Damage) m;
                lpFromMessages[d.player()] -= d.amount();
            }
            case MsgType.RECOVER -> {
                Msg.Recover r = (Msg.Recover) m;
                lpFromMessages[r.player()] += r.value();
            }
            case MsgType.PAY_LPCOST -> {
                Msg.PayLpCost c = (Msg.PayLpCost) m;
                lpFromMessages[c.player()] -= c.cost();
            }
            default -> {
            }
        }
    }

    /** 快照里一格「占用」时，位置位必须非零；否则就是我解析偏了。 */
    private void checkZones(DuelBoard.PlayerBoard p, int player) {
        zoneChecks += checkZoneList(p.monsterZones(), player, "怪兽");
        zoneChecks += checkZoneList(p.spellZones(), player, "魔陷");
    }

    private int checkZoneList(List<DuelBoard.Zone> zones, int player, String what) {
        int checked = 0;
        for (int i = 0; i < zones.size(); i++) {
            DuelBoard.Zone z = zones.get(i);
            checked++;
            if (z.occupied() && z.position() == 0) {
                problem("p" + player + " " + what + "区 " + i
                        + " 标记为占用但位置位为 0：快照字段对不上");
            }
            if (!z.occupied() && z.position() != 0) {
                problem("p" + player + " " + what + "区 " + i
                        + " 标记为空但位置位是 " + z.position() + "：快照字段对不上");
            }
        }
        return checked;
    }

    private void problem(String text) {
        if (problems.size() < MAX_REPORTED) {
            problems.add(text);
        } else if (problems.size() == MAX_REPORTED) {
            problems.add("（更多异常已省略）");
        }
    }

    /** 至少取到过一份快照、且没有异常。 */
    public boolean ok() {
        return snapshots > 0 && problems.isEmpty();
    }

    public int snapshots() {
        return snapshots;
    }

    /** 一行结论 + 异常明细，给日志用。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("快照探针：提问 ").append(queriesSeen)
                .append(" 次，取到快照 ").append(snapshots).append(" 份；")
                .append("LP 对账 ").append(lpChecks - lpMismatches).append('/').append(lpChecks)
                .append(" 一致，区域检查 ").append(zoneChecks).append(" 格");
        if (problems.isEmpty()) {
            sb.append("，无异常");
        } else {
            sb.append("，异常 ").append(problems.size()).append(" 条：");
            for (String p : problems) {
                sb.append("\n    ").append(p);
            }
        }
        if (lastBoard != null) {
            sb.append("\n  第 ").append(lastBoardAt).append(" 次提问时的牌桌：")
                    .append(lastBoard.replace("\n", "\n  "));
        } else {
            sb.append("\n  一份快照都没取到——queryFieldInfo 这条路径没有真正跑起来");
        }
        return sb.toString();
    }
}
