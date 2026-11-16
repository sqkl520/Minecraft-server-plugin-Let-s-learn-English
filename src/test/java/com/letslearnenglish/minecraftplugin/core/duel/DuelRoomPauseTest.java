package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.core.word.Word;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14：{@code DuelRoom.paused} 的停盘语义 —— 双掉线修复的<b>房间级</b>那一半。
 *
 * <p><b>为什么这条必须存在</b>：R14 修的双掉线缺陷有两个半边 ——
 * <ul>
 *   <li>{@code DuelManager} 半边：{@code handleReconnect} 不再无条件解暂停
 *       （由 {@code DuelDisconnectRoomIdentityTest} 钉住）；</li>
 *   <li>{@code DuelRoom} 半边：{@code paused=true} 时 {@code submitAnswer} 必须
 *       <b>完全拒收</b>并返回 {@code NOT_IN_DUEL} —— 这是「在场方连答打满 WIN_SCORE」
 *       这条原始缺陷链的最后一环。若这里漏了，Manager 侧判得再对也没用。</li>
 * </ul>
 * 两半分开测试是刻意的：Manager 侧靠反射驱动、这一侧是纯 JVM 直调，后者能给出
 * 「暂停时一题都拿不到分」的确定性断言，而不是推理。
 *
 * <p><b>为什么 {@code NOT_IN_DUEL} 而不是一个新枚举</b>：这是既有契约（R4-07-logic-F01
 * 落地时的选择）。暂停态对玩家表现为「现在答不了」，与「不在对局中」在
 * {@code DuelManager.submitAnswer} 的 switch 里走同一个分支（发 {@code duel.not-in-duel}），
 * 不新增枚举值的理由是不给调用方制造一个必须处理却无差别的新状态。
 */
class DuelRoomPauseTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static Word word(String en) {
        // DuelRoom 只用到 getEnglish()；其余字段给最小合法值。
        // 签名：english, chinese, partOfSpeech, phonetic, exampleSentence,
        //       synonyms, antonyms, difficulty, difficultyLevel
        return new Word(en, "中文", "n.", "/" + en + "/", "a " + en + " here",
                java.util.List.of(), java.util.List.of(), "beginner", 1);
    }

    private static DuelRoom roomWithWord() {
        DuelRoom room = new DuelRoom(P1, "Alice", P2, "Bob");
        room.setCurrentWord(word("apple"));
        return room;
    }

    @Test
    @DisplayName("暂停时答对也拿不到分（NOT_IN_DUEL），一方都刷不了")
    void pausedRejectsCorrectAnswer() {
        DuelRoom room = roomWithWord();
        room.setPaused(true);

        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, room.submitAnswer(P1, "apple"),
                "暂停期间在场的 P1 必须被拒收，否则连答 5 题就把掉线局写成 5-0 正常战绩");
        assertEquals(0, room.getScore1(), "被拒收不得计分");
        assertEquals(0, room.getScore2(), "被拒收不得给对手计分");
        assertFalse(room.isFinished(), "一题都未成，绝不能已结束");
    }

    @Test
    @DisplayName("暂停时答错也不给对手送分 —— 双向都封死")
    void pausedRejectsWrongAnswer() {
        DuelRoom room = roomWithWord();
        room.setPaused(true);

        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, room.submitAnswer(P2, "banana"),
                "暂停期间答错也必须拒收（否则对手白拿一分）");
        assertEquals(0, room.getScore1());
        assertEquals(0, room.getScore2());
    }

    @Test
    @DisplayName("解除暂停后立即恢复计分，且停在原题上（暂停不消耗题目）")
    void resumeRestoresScoring() {
        DuelRoom room = roomWithWord();
        room.setPaused(true);
        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, room.submitAnswer(P1, "apple"));

        room.setPaused(false);
        assertEquals(DuelRoom.AnswerResult.CORRECT, room.submitAnswer(P1, "apple"),
                "解除暂停后同一题必须还能答（暂停不应消耗掉它）");
        assertEquals(1, room.getScore1());
    }

    @Test
    @DisplayName("暂停期间连答 5 次都不得凑出 WIN_SCORE")
    void pausedCannotFarmToWinScore() {
        DuelRoom room = roomWithWord();
        room.setPaused(true);
        for (int i = 0; i < 10; i++) {
            room.submitAnswer(P1, "apple");
        }
        assertTrue(room.getScore1() < DuelRoom.WIN_SCORE,
                "暂停期连答 10 次后分数仍须低于 WIN_SCORE，实际=" + room.getScore1());
        assertFalse(room.isFinished(), "暂停期不得判定结束");
    }

    @Test
    @DisplayName("暂停不改变 active 位 —— 房间仍在，只是不接答案")
    void pauseDoesNotDeactivate() {
        DuelRoom room = roomWithWord();
        room.setPaused(true);
        assertTrue(room.isActive(), "暂停 ≠ 结束，active 必须保持");
        room.setPaused(false);
        assertTrue(room.isActive());
    }

    @Test
    @DisplayName("对局结束后再暂停/解暂停都不复活计分")
    void inactiveRoomStaysRejected() {
        DuelRoom room = roomWithWord();
        room.setActive(false);
        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, room.submitAnswer(P1, "apple"));

        room.setPaused(false);
        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL, room.submitAnswer(P1, "apple"),
                "解暂停不得把已结束的房间拉回可答状态");
        assertEquals(0, room.getScore1());
    }
}
