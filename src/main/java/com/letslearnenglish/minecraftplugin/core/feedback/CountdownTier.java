package com.letslearnenglish.minecraftplugin.core.feedback;

/**
 * Phase 3 倒计时档位（plan-audio-feedback §3.2 / 触及文件清单）。
 *
 * 学习倒计时（WordLearningManager timer）与对决倒计时（DuelManager BossBar）
 * 共用的纯函数判定：剩余秒数是否已进入"紧急"区间。
 * 紧急区间的表现由各调用方自定（红色文案 &c / BossBar 变红 / 每秒 tick 音），
 * 本类只做 tier 归类，零依赖、可单测。
 */
public enum CountdownTier {

    /** 正常：剩余秒数大于紧急阈值。 */
    NORMAL,

    /** 紧急：剩余秒数 ≤ 紧急阈值（含到点 remaining == 0，由调用方据 0 分流超时音）。 */
    URGENT;

    /**
     * 按剩余秒数与紧急阈值归类。
     *
     * @param remaining     剩余秒数（&lt;= 0 一律 URGENT）
     * @param urgentSeconds 进入紧急区的阈值秒数（该值及以下为 URGENT）
     * @return {@link #NORMAL}（remaining &gt; urgentSeconds）或 {@link #URGENT}
     */
    public static CountdownTier tierOf(int remaining, int urgentSeconds) {
        return remaining > urgentSeconds ? NORMAL : URGENT;
    }
}
