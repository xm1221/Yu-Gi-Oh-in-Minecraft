package cn.xm1221.ygomc.common.duel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 决斗台右键的判据。顺序不能随手调：自己已经有局时，不该先报「别人正在打」。
 */
class TableRulesTest {

    @Test
    void emptyTableSitsOrStartsSolo() {
        assertEquals(TableRules.Action.SIT, TableRules.decide(empty(false, false, false)));
        assertEquals(TableRules.Action.SIT, TableRules.decide(empty(false, true, false)));
        assertEquals(TableRules.Action.NEED_MY_DECK, TableRules.decide(empty(true, false, false)));
        assertEquals(TableRules.Action.START_SOLO, TableRules.decide(empty(true, true, false)));
        assertEquals(TableRules.Action.BUSY, TableRules.decide(empty(true, true, true)));
    }

    @Test
    void hostLeavesOrStartsSolo() {
        assertEquals(TableRules.Action.LEAVE, TableRules.decide(waiting(true, false, true, true, true, false)));
        assertEquals(TableRules.Action.NEED_MY_DECK, TableRules.decide(waiting(true, true, true, false, true, false)));
        assertEquals(TableRules.Action.START_SOLO, TableRules.decide(waiting(true, true, true, true, true, false)));
        assertEquals(TableRules.Action.BUSY, TableRules.decide(waiting(true, true, true, true, true, true)));
    }

    @Test
    void secondPlayerJoinsOnlyWhenBothDecksAreReady() {
        assertEquals(TableRules.Action.NEED_MY_DECK, TableRules.decide(waiting(false, false, true, false, false, false)));
        assertEquals(TableRules.Action.NEED_MY_DECK, TableRules.decide(waiting(false, false, true, false, true, false)));
        assertEquals(TableRules.Action.NEED_THEIR_DECK, TableRules.decide(waiting(false, false, true, true, false, false)));
        assertEquals(TableRules.Action.JOIN, TableRules.decide(waiting(false, false, true, true, true, false)));
        assertEquals(TableRules.Action.JOIN, TableRules.decide(waiting(false, true, true, true, true, false)));
        assertEquals(TableRules.Action.HOST_OFFLINE, TableRules.decide(waiting(false, false, false, true, true, false)));
        assertEquals(TableRules.Action.HOST_STARTED, TableRules.decide(waiting(false, false, true, true, true, false, true)));
        assertEquals(TableRules.Action.BUSY, TableRules.decide(waiting(false, false, true, true, true, true)));
    }

    @Test
    void liveTableReopensForParticipantsAndRefusesEveryoneElse() {
        assertEquals(TableRules.Action.REOPEN, TableRules.decide(live(true, true)));
        assertEquals(TableRules.Action.REOPEN, TableRules.decide(live(true, false)));
        assertEquals(TableRules.Action.BUSY, TableRules.decide(live(false, true)));
        assertEquals(TableRules.Action.SPECTATE, TableRules.decide(live(false, false)));
    }

    private static TableRules.View empty(boolean sneak, boolean myDeck, boolean dueling) {
        return new TableRules.View(TableRules.Phase.EMPTY, false, false, dueling,
                false, myDeck, false, sneak, false);
    }

    private static TableRules.View waiting(boolean host, boolean sneak, boolean hostOnline,
                                           boolean myDeck, boolean hostDeck, boolean dueling) {
        return waiting(host, sneak, hostOnline, myDeck, hostDeck, dueling, false);
    }

    private static TableRules.View waiting(boolean host, boolean sneak, boolean hostOnline,
                                           boolean myDeck, boolean hostDeck, boolean dueling,
                                           boolean hostDueling) {
        return new TableRules.View(TableRules.Phase.WAITING, host, false, dueling,
                hostOnline, myDeck, hostDeck, sneak, hostDueling);
    }

    private static TableRules.View live(boolean participant, boolean dueling) {
        return new TableRules.View(TableRules.Phase.LIVE, false, participant, dueling,
                false, false, false, false, false);
    }
}
