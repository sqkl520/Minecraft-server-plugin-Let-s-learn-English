# 架构报告 calendar-move

### 判定：PASS

## 审查范围

| 文件 | 包 |
|------|----|
| MainMenu.java | gui/ |
| MainMenuListener.java | listener/ |
| ProfileGUI.java | gui/ |
| SocialListener.java | listener/ |

## 维度 1 — 分层正确性

| 文件 | 结论 | 说明 |
|------|------|------|
| MainMenu.java | PASS | gui/ 纯渲染，无 LearningCalendarGUI 任何引用 |
| MainMenuListener.java | PASS | listener/ 事件处理，import 列表中无 LearningCalendarGUI |
| ProfileGUI.java | PASS | gui/ 纯渲染，slot 0 仅调用 createItem(Material.CLOCK, ...) |
| SocialListener.java | PASS | listener/ 事件处理，LearningCalendarGUI 跳转在 handleProfileGUI() 内 |

## 维度 2 — 单一职责

| 文件 | 行数 | 结论 |
|------|------|------|
| MainMenu.java | 105 | PASS |
| MainMenuListener.java | 114 | PASS |
| ProfileGUI.java | 294 | PASS（< 300，无警告） |
| SocialListener.java | 293 | PASS（< 300，无警告） |

## 维度 3 — DI 注入完整性

四个类均通过构造函数注入 `LetsLearnEnglish plugin`，无字段自赋值。
import 完整：SocialListener 第 11 行声明 `import ...gui.LearningCalendarGUI`，与实际使用一致；无冗余 import。

## 维度 4 — 事件注册

SocialListener 为既有 Listener，已在 onEnable() 注册（非新增类，无需重新验证注册）。
本次变更未新增 Listener 类，未新增命令，plugin.yml 无需修改。

## 重点检查结论

- SocialListener.handleProfileGUI() L166-174：slot 0 判断在方法体内，正确位置。
- ProfileGUI.open() L71-77：slot 0 仅 `inv.setItem(0, createItem(Material.CLOCK, ...))` 纯渲染，无业务逻辑。
- MainMenu.java 全文：无 LearningCalendarGUI 字符串，迁移清理完毕。
- MainMenuListener.java 全文：无 LearningCalendarGUI 字符串，case 9 已移除。
