package com.letslearnenglish.minecraftplugin.core.feedback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯逻辑断言 for CountdownTier（一致性审计 C-9 收口：学习侧接线同源后补单测）。
 *
 * <p>契约（plan-audio-feedback §3.2）：{@code remaining > urgentSeconds → NORMAL}，
 * 其余（含 0 与负数）一律 URGENT。学习侧（countdown 文案/ tick 音）与对决侧
 * （BossBar 变红）共享此判定的语义边界，用单测钉死。
 */
class CountdownTierTest {

    @Test
    void aboveThresholdIsNormal() {
        assertEquals(CountdownTier.NORMAL, CountdownTier.tierOf(6, 5));
        assertEquals(CountdownTier.NORMAL, CountdownTier.tierOf(30, 5));
    }

    @Test
    void atThresholdIsUrgent() {
        assertEquals(CountdownTier.URGENT, CountdownTier.tierOf(5, 5));
    }

    @Test
    void zeroAndNegativeRemainingAreUrgent() {
        assertEquals(CountdownTier.URGENT, CountdownTier.tierOf(0, 5));
        assertEquals(CountdownTier.URGENT, CountdownTier.tierOf(-1, 5));
    }

    @Test
    void boundaryIsStrictlyGreater() {
        // 阈值边界双侧：urgent+1 → NORMAL，urgent 本身 → URGENT（与学习侧原 `<=` 逐位等价）
        assertEquals(CountdownTier.NORMAL, CountdownTier.tierOf(5, 4));
        assertEquals(CountdownTier.URGENT, CountdownTier.tierOf(4, 4));
    }

    @Test
    void zeroThresholdOnlyMakesZeroUrgent() {
        // urgent-seconds=0 的退化配置：语义为"剩余 ≤ 0 才紧急"——正数剩余仍 NORMAL
        // （与 tierOf 的严格大于判定一致：0 不是"所有都紧急"的哨兵值）
        assertEquals(CountdownTier.NORMAL, CountdownTier.tierOf(10, 0));
        assertEquals(CountdownTier.URGENT, CountdownTier.tierOf(0, 0));
    }
}
