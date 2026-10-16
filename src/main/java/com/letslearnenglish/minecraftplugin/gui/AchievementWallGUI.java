package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 成就墙（plan-achievements §2.1/§2.2/§3.6，US-ACH-4）。
 *
 * 信息架构：单页 54 格、28 个内容格（slot 10-16 / 19-25 / 28-34 / 37-43），
 * 22 个成就按 5 个类别稳定排序（enum ordinal 分组），类别名作为每个条目
 * lore 第一行——不用 header 格（22+5=27 贴顶且脆弱，见规划 §2.1 裁决）。
 *
 * 标题附带计数"成就墙 (n/22)"：{@code SocialListener}（L110/L133/L159）与
 * {@code GUIUtils.isPluginGUI} 对标题的判定都是 contains("成就墙"/"Achievement Wall")，
 * 追加计数后缀不断匹配（改标题词元前必须先反向核对这两处，见 R-86 标题契约）。
 */
public class AchievementWallGUI {

    private static final int GUI_SIZE = 54;

    /**
     * 内容格容量：4 行 × 7 列 = 28（slot 10-16 / 19-25 / 28-34 / 37-43）。
     */
    private static final int MAX_GRID_SLOTS = 28;

    /**
     * PF-ACH-07 护栏：超过 28 个可见成就时 {@code slot > 43} 会静默丢弃多余条目，
     * WARNING 只报一次（静态防刷屏；成就数超容量需要分页时手动定位此提示）。
     */
    private static volatile boolean truncationWarningLogged = false;

    private final LetsLearnEnglish plugin;

    public AchievementWallGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, UUID targetId) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        AchievementManager am = plugin.getAchievementManager();
        Set<Achievement> unlocked = am.getUnlockedAchievements(targetId);
        String lang = plugin.getPlayerLanguage(viewer);

        FileConfiguration achConfig = loadAchievementConfig();

        // 可见成就（管理员禁用的不渲染）先集齐，再按类别稳定排序
        // （List.sort 是稳定排序：同类别内保持 enum 声明序，旧展示序不被扰动）。
        List<Achievement> visible = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            if (isAchievementEnabled(achConfig, a)) {
                visible.add(a);
            }
        }
        visible.sort(Comparator.comparingInt(a -> a.getCategory().ordinal()));

        // PF-ACH-07：容量护栏（>28 时下方 slot > 43 break 会截断，WARNING-once 防静默丢弃）
        if (visible.size() > MAX_GRID_SLOTS) {
            warnTruncationOnce(visible.size());
        }

        int unlockedAmongVisible = 0;
        for (Achievement a : visible) {
            if (unlocked.contains(a)) unlockedAmongVisible++;
        }

        // 标题计数为可选增强（规划 §2.1）：统计口径 = 墙内可见成就（管理员禁用的不计入，
        // 与渲染集一致）；后缀不影响 SocialListener/GUIUtils 的 contains 判定契约。
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "achievement-wall.title"))
                + " (" + unlockedAmongVisible + "/" + visible.size() + ")";
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        ConfigManager cm = plugin.getConfigManager();

        // 进度快照一次性组装（规划 §2.2 / R2：≤3 条有界索引查询，主线程，可接受）。
        // S-E 流提供（AchievementManager.getProgressSnapshot）。
        Map<Achievement, int[]> progressSnapshot = am.getProgressSnapshot(targetId);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        int slot = 10;
        int col = 0;
        Material unlockedMat = cm.getGuiMaterial("achievement-wall", "unlocked-material", Material.GREEN_STAINED_GLASS_PANE);
        Material lockedMat = cm.getGuiMaterial("achievement-wall", "locked-material", Material.GRAY_STAINED_GLASS_PANE);
        for (Achievement a : visible) {
            boolean isUnlocked = unlocked.contains(a);
            Material mat = isUnlocked ? unlockedMat : lockedMat;
            String name = isUnlocked
                    ? ChatColor.translateAlternateColorCodes('&', "&a" + a.getDisplayName(lang))
                    : ChatColor.translateAlternateColorCodes('&', "&8&m" + a.getDisplayName(lang));

            List<String> lore = new ArrayList<>();
            // 行 1：类别（键由 fan-in 落 zh/en.yml：achievement-wall.category-line +
            // category-learning/review/consistency/social/economy，枚举名小写即键后缀）
            String categoryName = MessageUtil.getGuiMsg(messages,
                    "achievement-wall.category-" + a.getCategory().name().toLowerCase(Locale.ROOT));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "achievement-wall.category-line")
                            .replace("{category}", categoryName)));
            // 行 2：描述
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + a.getDisplayDesc(lang)));

            if (isUnlocked) {
                // 行 3：解锁日期（PF-ACH-09 数据本就在库；S-E 流提供 getUnlockedAt）
                Long unlockedAt = am.getUnlockedAt(targetId, a);
                if (unlockedAt != null) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "achievement-wall.unlocked-at")
                                    .replace("{date}", dateFormat.format(new Date(unlockedAt)))));
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "profile.achievement-unlocked")));
            } else {
                // 进度行：仅对可量化数据源（progressSource != NONE）显示。
                // achievement.progress 为复活死键（zh/en.yml:106），值里带 {prefix}
                // （设计给聊天的），墙内 lore 无前缀语境 → 置空替换。
                if (a.getProgressSource() != Achievement.ProgressSource.NONE) {
                    int[] progress = progressSnapshot != null ? progressSnapshot.get(a) : null;
                    if (progress != null && progress.length >= 2) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "achievement.progress")
                                        .replace("{prefix}", "")
                                        .replace("{name}", a.getDisplayName(lang))
                                        .replace("{current}", String.valueOf(progress[0]))
                                        .replace("{target}", String.valueOf(progress[1]))));
                    }
                }
                // 前置未满足提示（S-E 流提供 meetsPrerequisites；boolean 接口不提供
                // 缺失前置的名字，{name} 填本成就名，语义 = "{name} 的前置尚未满足"）
                if (!am.meetsPrerequisites(targetId, a)) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "achievement.prereq-hint")
                                    .replace("{name}", a.getDisplayName(lang))));
                }
                String timeLeft = getTimeLeftString(achConfig, a, messages);
                if (timeLeft != null && !timeLeft.isEmpty()) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', timeLeft));
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "profile.achievement-locked")));
            }

            inv.setItem(slot, createItem(mat, name, lore));

            slot++;
            col++;
            if (col == 7) { col = 0; slot += 2; }
            if (slot > 43) break;
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        viewer.openInventory(inv);
    }

    private void warnTruncationOnce(int visibleCount) {
        if (truncationWarningLogged) return;
        truncationWarningLogged = true;
        plugin.getLogger().warning("Achievement wall grid overflow: " + visibleCount
                + " enabled achievements exceed the " + MAX_GRID_SLOTS
                + " display slots — the remainder is silently dropped (PF-ACH-07). "
                + "Add paging before expanding the achievement list further.");
    }

    /**
     * 直读 dataFolder 下的 achievements-config.yml。
     *
     * <p>诚实性审查收口：原"四个真相源"中的 {@code ConfigManager.achievements.yml}
     * 死链已随废弃文件整链移除（2026-10-16）——运行期真正生效的只有
     * {@code achievements-config.yml}，本方法与
     * {@code AchievementManager.getAchievementConfig} 是仅有的两个消费点。
     */
    private FileConfiguration loadAchievementConfig() {
        File file = new File(plugin.getDataFolder(), "achievements-config.yml");
        if (!file.exists()) {
            plugin.saveResource("achievements-config.yml", false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private boolean isAchievementEnabled(FileConfiguration config, Achievement a) {
        if (config == null) return true;
        return config.getBoolean("achievements." + a.getId() + ".enabled", true);
    }

    private String getTimeLeftString(FileConfiguration config, Achievement a,
                                      FileConfiguration messages) {
        if (config == null) return null;
        if (!config.getBoolean("achievements." + a.getId() + ".time-limit.enabled", false)) return null;

        String startStr = config.getString("achievements." + a.getId() + ".time-limit.start");
        String endStr = config.getString("achievements." + a.getId() + ".time-limit.end");
        if (startStr == null || endStr == null) return null;

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            Date start = sdf.parse(startStr);
            Date end = sdf.parse(endStr);
            Date now = new Date();

            if (now.before(start)) {
                long diff = start.getTime() - now.getTime();
                return MessageUtil.getGuiMsg(messages, "achievement-wall.time-opens")
                        .replace("{time}", formatDuration(diff));
            }
            if (now.after(end)) {
                return MessageUtil.getGuiMsg(messages, "achievement-wall.time-expired");
            }
            long diff = end.getTime() - now.getTime();
            return MessageUtil.getGuiMsg(messages, "achievement-wall.time-remaining")
                    .replace("{time}", formatDuration(diff));
        } catch (Exception e) {
            return null;
        }
    }

    private String formatDuration(long millis) {
        long days = millis / 86400000;
        long hours = (millis % 86400000) / 3600000;
        long minutes = (millis % 3600000) / 60000;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
