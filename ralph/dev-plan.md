# 开发计划 — GUI Bug 修复 (PRD-less)

## 项目信息
- PRD：无（用户直接报告 Bug）
- 主类：`com.letslearnenglish.minecraftplugin.LetsLearnEnglish`
- 总 Story 数：4
- 创建时间：2026-06-19

## 任务清单

| # | Story ID | 标题 | 涉及文件 | 依赖 | 状态 |
|---|---------|------|---------|------|------|
| 1 | US-001 | Learning Calendar 主菜单项缺少 i18n 翻译 | messages/zh.yml, messages/en.yml | 无 | 待办 |
| 2 | US-002 | 错题本页面：标题拼接错误 / 装饰行冗余 / 返回按钮位置 | WrongBookGUI.java, WrongBookListener.java, messages/zh.yml, messages/en.yml | 无 | 待办 |
| 3 | US-003 | 页面切换按钮风格统一 | WrongBookGUI.java, EnglishSquareGUI.java, GUIUtils.java, ChatHistoryGUI.java | US-002 | 待办 |
| 4 | US-004 | GUI 导航栈：返回路径断裂（单槽覆盖导致深层导航丢失） | GuiNavigationManager.java, SocialListener.java, MiscGUIListener.java, MainMenuListener.java | 无 | 待办 |

状态：待办 | 进行中 | 完成 | 低质量通过
