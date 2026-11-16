package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.Collection;

public class ChatAnswerListener implements Listener {

    private final LetsLearnEnglish plugin;
    // P2-21: 3 秒在线玩家快照缓存 —— 意图：答对广播是逐题高频路径，
    // 每次直接 Bukkit.getOnlinePlayers() 会产生高频集合分配/拷贝；
    // 3 秒粒度对本广播（纯聊天通知，无正确性要求）足够新鲜。
    // 已知副作用（刻意保留，B 流核实裁定）：新进服玩家在快照刷新前
    // （最多 3 秒）收不到广播。
    // R4-choice：改为 static —— GUI 点选作答（WordLearningListener）与聊天作答共用
    // 同一个广播入口 presentAnswerResult，两条通道共享这份缓存，不各自再建一份。
    /**
     * 在线玩家快照 + 生成时刻，<b>作为一个不可变整体</b>发布。
     *
     * <p><b>slice-16-concurrency-04（round1 遗留 LOW，本轮补修）：原实现是两个各自
     * volatile 的字段</b>（{@code cachedOnlinePlayers} + {@code playersCacheTimestamp}）。
     * 单字段可见性有保证，但「先读快照、再读时间戳」这两次 volatile read 之间可以被
     * 另一次完整写入插入，于是读者可能拿到「<b>旧快照配新时间戳</b>」，把过期快照
     * 再多用满 3 秒。两个字段之间没有任何原子性保证。
     *
     * <p>当前所有调用点都在主线程，所以撕裂不可观测 —— 但这两个字段被显式声明为
     * {@code static} 正是为了两条通道共享（聊天作答 + GUI 点选），一旦将来有第三条
     * 从异步线程调用的通道，缺陷立刻生效。改成一个 {@link AtomicReference} 持有的
     * record，读写都是一次原子操作，代价是一次对象分配 / 3 秒。
     */
    private record PlayerSnapshot(Collection<? extends Player> players, long takenAt) { }

    private static final java.util.concurrent.atomic.AtomicReference<PlayerSnapshot> PLAYER_SNAPSHOT =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 广播玩家名最大长度：stripColor 后截断，防超长刷屏（C2） */
    private static final int BROADCAST_NAME_MAX_LENGTH = 50;

    public ChatAnswerListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 清洗用于广播的玩家名：去颜色码 + 截断。
     * 防颜色码注入（&/§）与超长名刷屏。
     */
    private static String sanitizeBroadcastName(String name) {
        if (name == null) {
            return "?";
        }
        // 先去 Bukkit 颜色码（§），再去 & 风格颜色码（广播模板走颜色翻译，& 会被二次解析）
        String clean = ChatColor.stripColor(name).replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        if (clean.length() > BROADCAST_NAME_MAX_LENGTH) {
            clean = clean.substring(0, BROADCAST_NAME_MAX_LENGTH);
        }
        return clean.isEmpty() ? "?" : clean;
    }

    /**
     * C2：判分后广播"答对+分"（不含答案文本）。
     * 答案文本永不出现在广播里 — 此方法只接收玩家名与得分，不接收答案参数，
     * 即使 showAnswers 被手动打开也只走这条无答案路径。
     *
     * <p>R4-choice：改为 static —— 聊天作答与 GUI 点选作答（WordLearningListener）
     * 共用本方法，两条通道的广播口径（含隐私开关、缓存、i18n）完全一致。
     */
    private static void broadcastCorrectAnswer(LetsLearnEnglish plugin, Player answerer, int score) {
        long now = System.currentTimeMillis();
        // 一次 atomic read 拿到「快照 + 时刻」的一致组合，不存在撕裂。
        PlayerSnapshot snap = PLAYER_SNAPSHOT.get();
        Collection<? extends Player> snapshot;
        if (snap == null || now - snap.takenAt() > 3000) {
            snapshot = new ArrayList<>(Bukkit.getOnlinePlayers());
            PLAYER_SNAPSHOT.set(new PlayerSnapshot(snapshot, now));
        } else {
            snapshot = snap.players();
        }
        String safeName = sanitizeBroadcastName(answerer.getName());
        String scoreText = String.valueOf(score);
        // R-86m（P2 批次）：按每位收件人自己的语言渲染——不再是"答题者语言全员共用"。
        // 双语各渲染一次后按收件人语言取用（最多 2 次模板解析）。
        String zhMsg = renderBroadcastTemplate(plugin, "zh", safeName, scoreText);
        String enMsg = renderBroadcastTemplate(plugin, "en", safeName, scoreText);
        for (Player online : snapshot) {
            if (!online.equals(answerer) && online.isOnline()) {
                // B2：隐私开关查的是收件人自己的设置，不是答题者的。
                // L-写路径收口：这里必须用只读版。收件人虽然在线，但缓存未必有条目
                // （join 读库失败时 loadProfileSettings 刻意不 put）。旧写法 getProfileSettings
                // 会 computeIfAbsent 往 profileSettingsCache 里种一份「全公开」条目，
                // 而 getActivePlayerIds() = 缓存键 ∪ 在线玩家 —— 一次广播就把隐私未知的玩家
                // 当成公开写进广场候选集。只读版 miss 时回 unresolvedFallback()（showAnswers=false），
                // 即「隐私优先」：宁可少发这条广播，也不把答案推给隐私状态未知的人。
                PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore()
                        .getProfileSettingsReadOnly(online.getUniqueId());
                if (ps.isShowAnswers()) {
                    String rendered = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(online))
                            ? zhMsg : enMsg;
                    if (rendered != null) {
                        online.sendMessage(rendered);
                    }
                }
            }
        }
    }

    /**
     * R-86m：按指定语言渲染广播模板；该语言缺键时回落 zh，两语言都缺返回 null（放弃广播）。
     */
    private static String renderBroadcastTemplate(LetsLearnEnglish plugin, String lang,
                                                  String safeName, String scoreText) {
        String template = plugin.getConfigManager().getMessageConfig(lang)
                .getString("word-learning-gui.answer-broadcast-correct");
        if (template == null && !"zh".equalsIgnoreCase(lang)) {
            template = plugin.getConfigManager().getMessageConfig("zh")
                    .getString("word-learning-gui.answer-broadcast-correct");
        }
        if (template == null) {
            return null;
        }
        return ChatColor.translateAlternateColorCodes('&',
                template.replace("{player}", safeName).replace("{score}", scoreText));
    }

    /**
     * M4: tell a dueling player (on the main thread) to finish their duel
     * before quiz/practice can consume chat input again. The message
     * resolves in the player's own language; strictly one-way — the
     * chat input itself is never forwarded to the duel as an answer.
     */
    private void notifyFinishDuelFirst(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "duel.finish-duel-first"));
            }
        });
    }

    /**
     * F-16-05：消费型 handler 只能用 LOWEST —— 必须抢在 {@link ChatScenarioListener}
     * （HIGH）的 {@code isCancelled()} 让路检查之前 cancel，否则 AI 对话先吞答题输入
     * （与 {@code GlobalGuiActionGuardListener} 的"只守不消费"LOWEST 意图相反）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        // Check duel first — duel answers take priority over word learning
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            event.setCancelled(true);
            event.getRecipients().clear();
            String answer = event.getMessage();
            Bukkit.getScheduler().runTask(plugin, () -> {
                duelManager.submitAnswer(player, answer);
            });
            return;
        }

        WordLearningManager wlManager = plugin.getWordLearningManager();

        // Check practice mode first (Wrong Book practice)
        if (wlManager.isPracticingWord(player.getUniqueId())) {
            // M4: duel-in-progress guard — don't consume a dueling player's
            // chat as a practice answer; leave the event for public chat
            if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
                notifyFinishDuelFirst(player);
                return;
            }
            event.setCancelled(true);
            // R-16（低危积压 FIX 4）：**补齐**收件人清空 —— 与决斗抢答分支（本方法开头）和
            // 主答题分支（本方法末尾）对齐。原实现是三条消费分支里唯一漏掉这条的，
            // 属真疏漏而非有意为之：
            //   1) {@link ChatScenarioListener} 的 F-16-05 javadoc 明确把「答题 / 错题练习 /
            //      决斗抢答」三条并列为「命中即 setCancelled(true) + getRecipients().clear()」，
            //      文档契约与代码原本不符；
            //   2) 三条分支的意图完全相同 —— 玩家敲的这行字是<b>答案</b>，不该被任何人看见。
            //
            // 为什么 setCancelled(true) 不够：它只让 Bukkit 的默认发送路径不投递，而
            // {@code getRecipients()} 是别的插件（聊天管理、记录/日志、聊天过滤、分析插件）
            // 在**同一个事件**上读的收件人列表 —— 只要它们不看 isCancelled（或只看自己的
            // 判定）就仍会把答案当公开聊天记下来。错题练习泄漏的内容比普通答题更敏感：
            // Wrong Book 里躺的正是玩家自己不认识的词，泄漏的就是"他不会什么"。
            //
            // 清空放在 cancel 之后、分支分流之前：本段下面有"cancel 指令退练习"和"提交判分"
            // 两个出口，两条都已被 cancel，一次清空即可覆盖，不必在两处各清一遍。
            event.getRecipients().clear();
            String answer = event.getMessage();

            // Check for cancel command
            // R4-choice：cancel 也是教学词条（intermediate 词库 English="cancel"）。练习到它时
            // 正确答案恰好等于退出指令 —— 此时把输入让给判分（跳退出分支），否则玩家永远
            // 答不对、也永远退不出去。只比英文词原文（trim + 忽略大小写），不引入第二条
            // 判定链；practiceWords 存的是小写英文，getPracticingWord 不会为 null。
            String practiceWord = wlManager.getPracticingWord(player.getUniqueId());
            boolean practicingCancelWord = "cancel".equalsIgnoreCase(
                    practiceWord == null ? "" : practiceWord.trim());
            if (!practicingCancelWord && answer.trim().equalsIgnoreCase("cancel")) {
                wlManager.cancelPractice(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "wrong-book.practice-cancelled"));
                    new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
                });
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                wlManager.submitPracticeAnswer(player, answer);
                // Re-open WrongBookGUI after answer
                new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
            });
            return;
        }

        // 16-L-16-01：题号票据 — 异步栈帧内**立即取值**快照（留到任务里再读会恒等，且必须早于
        // isAnswering 门才能覆盖"门与快照之间正好超时"）。同一 tick 连发两条 chat 包时两条都过
        // 得了该门，第二条会被结算到玩家还没看到提示的下一题。等价闸见 GUI 通道 drawnQuestion 比对。
        //
        // R10-s16dc-04：票据从「题号」升级为「题号 + 结算序号」。题号是 Session 的维度，
        // 普通会话每题结算都 advance()，所以题号单调递增、天然能区分"同一题被结算第二次"；
        // 但**教程会话的答错/超时是原地重试**（recordTutorialRetry 只加计数，不 advance，
        // 见 WordLearningManager 教程分支），题号在整段教程会话里可能一直是 0 ——
        // 票据退化为恒真，同一 tick 连发的两条包都能穿过闸门，incorrectCount 被重复累加。
        // settleSeq 由 QuizState 在每次结算的 finally 里 +1，是"同一道题被结算了几次"的
        // 单调递增维度：题号不动时它仍然前进，两个维度组合后票据才真正不可绕过。
        final WordLearningManager.QuizState ticket = wlManager.getQuizState(player.getUniqueId());
        final int ticketIndex = ticket == null ? -1 : ticket.session.getCurrentIndex();
        final int ticketSeq = ticket == null ? -1 : ticket.settleSeq.get();

        if (!wlManager.isAnswering(player.getUniqueId())) {
            return;
        }

        // M4: duel-in-progress guard — a dueling player must never have
        // their chat consumed as a quiz answer (covers the async-chat
        // branch check / main-thread acceptDuel race window).
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            notifyFinishDuelFirst(player);
            return;
        }

        event.setCancelled(true);

        String answer = event.getMessage();

        event.getRecipients().clear();
        // C2：答题广播（含答案文本的旧 answer-broadcast）已删除 — 改为判分后仅答对广播
        // "答对+分"（broadcastCorrectAnswer，不含答案文本），见 presentAnswerResult。
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 16-L-16-01：执行时刻复核票据 — 会话已换或题号已推进 = 预输入的第二条消息，
            // 按迟到答案丢弃，不得改记到当前题（否则误判错 + 误记错题本）。
            // R10-s16dc-04：除题号外还比对结算序号 —— 教程会话原地重试时题号不变，
            // 只有序号能区分"同一题的第一次与第二次结算"。缺了它，同一 tick 连发的
            // 两条包都会拿 ticketIndex==liveIndex 通过闸门并各记一次错误。
            WordLearningManager.QuizState live = wlManager.getQuizState(player.getUniqueId());
            if (ticket != null && live != null && (live.session != ticket.session
                    || live.session.getCurrentIndex() != ticketIndex
                    || live.settleSeq.get() != ticketSeq)) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word-learning-gui.answer-expired"));
                return;
            }
            // FEAT-MODE-01：按会话 mode 分流判定 — mode 取自 WordLearningManager，
            // 判分收敛于 AnswerChecker.check（submitAnswer 内部调用），本分支只喂答案。
            String mode = wlManager.getMode(player.getUniqueId());

            // R-10：choice 模式下敲了越界选项字母（小词库只有 A/B 却输入 C/D）属"无效输入"，
            // 不当答错结算（不清连击 / 不扣分 / 不进错题本）。判定收敛在 AnswerChecker，
            // 这里仅在 submitAnswer 之前拦截并提示；计时器未被取消，玩家可在剩余倒计时内重答。
            // MC-3：caseSensitive 走会话快照（与 submitAnswer 内部判分同参数，
            // 防"判分判对的输入被前置无效判定抢先拦下"）。
            if (AnswerChecker.isChoice(mode)) {
                Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
                java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
                if (AnswerChecker.isInvalidChoiceInput(mode, currentWord, answer, options,
                        wlManager.isCaseSensitive(player.getUniqueId()))) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "word-learning-gui.invalid-option"));
                    return;
                }
            }

            WordLearningManager.AnswerResult result = wlManager.submitAnswer(player, answer);
            if (result == null) {
                // R-15：迟到答案 —— 倒计时已判定或会话已结束（state 被移出 map / 正被占用），
                // submitAnswer 返回 null。不再静默丢弃，给玩家一句"本题已过"提示。
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word-learning-gui.answer-expired"));
                return;
            }

            // R4-choice：结果呈现抽成公共入口 —— 聊天作答与 GUI 点选作答共用同一套
            // 音效/文案/广播/结算/推进逻辑（避免两条通道行为漂移）。区别只在收尾：
            // 聊天路径重开 GUI（openWithMessage，= 原行为），GUI 路径就地刷新不关界面。
            presentAnswerResult(plugin, player, result, false);
            return;
        });
    }

    /**
     * R4-choice：答题结果的唯一呈现出口（聊天作答 + GUI 点选作答共用）。
     *
     * <p>必须是主线程：内部会发消息、播音效、结算会话、操作背包视图。
     *
     * <p>与 {@code submitAnswer} 的分工：判分/计分/推进全部在 manager 内完成，
     * 本方法只消费 {@link WordLearningManager.AnswerResult} 快照，不再读会话状态
     * （会话完成时 state 已移出 map，重查必为 null —— Q1 同款约束）。
     *
     * @param player      答题玩家
     * @param result      submitAnswer 的非 null 返回值
     * @param keepGuiOpen true = 保持并刷新已打开的单词学习界面（GUI 点选通道）；
     *                    false = 重开界面（聊天通道，沿用 openWithMessage 的既有行为）
     */
    public static void presentAnswerResult(LetsLearnEnglish plugin, Player player,
                                           WordLearningManager.AnswerResult result,
                                           boolean keepGuiOpen) {
        WordLearningManager wlManager = plugin.getWordLearningManager();
        FileConfiguration msgs = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(player));
        String mode = result.mode;

        if (result.correct) {
            // Phase 3 3A-2（plan-audio-feedback B3）：连击反馈升级。
            // 里程碑 tick（combo 命中 3/5/7/10 且 milestone-enabled）：铃音
            // 替代升调 correct 音（不叠加，避免双音浑浊）+ ActionBar 闪里程碑文案；
            // 其余答对：correct 音 pitch 随连击递增（getComboPitch，断连击自然回落）。
            if (wlManager.isComboMilestone(result.consecutiveCorrect)) {
                SoundManager.playSound(player, "combo-milestone");
                plugin.getVersionAdapter().sendActionBar(player,
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(msgs, "word-learning-gui.combo-milestone")
                                        .replace("{count}", String.valueOf(result.consecutiveCorrect))
                                        .replace("{multiplier}", String.valueOf(result.comboMultiplier))));
            } else {
                SoundManager.playSound(player, "correct",
                        wlManager.getComboPitch(result.consecutiveCorrect));
            }
            String comboMsg = "";
            if (result.consecutiveCorrect > 1) {
                // P2 收尾：裸 getString 收敛为 getGuiMsg（缺键返回 [?key] 不 NPE）
                comboMsg = " " + ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msgs, "word-learning-gui.combo-message")
                                .replace("{count}", String.valueOf(result.consecutiveCorrect))
                                .replace("{multiplier}", String.valueOf(result.comboMultiplier)));
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(msgs, "word-learning-gui.correct")
                            .replace("{score}", String.valueOf(result.comboScore))
                            .replace("{combo}", comboMsg)));

            // C2：答对后广播"答对+分"（不含答案文本）
            broadcastCorrectAnswer(plugin, player, result.comboScore);

            plugin.getAchievementManager().checkStreakAchievement(
                    player.getUniqueId(), result.consecutiveCorrect);
        } else {
            SoundManager.playSound(player, "incorrect");
            // Phase 3 onboarding（plan-onboarding §3.5）：教程迷你会话答错 = 原地重试
            // 提示（不显示答案）；音效保持 incorrect 不变；随后既有
            // else { openWithMessage } 循环原样复用 —— 会话未推进，重开的仍是同题。
            // 教程文案走 getPlayerMessage（per-player 语言 + {prefix} 替换；
            // onboarding.* 聊天键按规划带 {prefix}，getGuiMsg 不做该替换）。
            if (result.tutorial) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "onboarding.retry-wrong"));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msgs, "word-learning-gui.incorrect")
                                .replace("{answer}", result.correctAnswer)));
            }
        }

        if (result.sessionComplete) {
            SoundManager.playSound(player, "complete");
            wlManager.showSessionComplete(player, result);
            return;
        }

        // 会话未完成：GUI 通道就地刷新（不关界面、不触发 InventoryCloseEvent，
        // 因此不会重开倒计时）；聊天通道沿用原行为重开界面。
        if (keepGuiOpen) {
            // 下一 tick 再重画：不在 InventoryClickEvent 的同步栈里改视图（避免在事件
            // 分发过程中回写触发点击的那份 Inventory，也避开事件重入）。refresh 自带
            // 菜单归属闸 —— 玩家若在这 1 tick 内关了界面/换到别处，只会空跑。
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> new WordLearningGUI(plugin).refresh(player));
        } else {
            String difficulty = wlManager.getDifficulty(player.getUniqueId());
            new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
        }
    }
}
