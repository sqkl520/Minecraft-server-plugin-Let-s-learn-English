package com.letslearnenglish.minecraftplugin.api;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code getInstance()} 是第三方插件拿本插件 API 的<b>唯一</b>入口，而它在停用后
 * 必须变成 null —— 否则第三方拿到的是一个「所有 getter 都返回 null Manager」的僵尸对象，
 * 调用方通常只判 {@code getInstance() != null} 就继续用，NPE 反而落在<b>调用方</b>那一侧，
 * 而「本插件未启用」这个本可理解的事实反而消失了。
 *
 * <p>这条契约必须在「启动时就是 server.enabled=false」与「运行中被 /lea reload 拆掉」
 * 两种情形下<b>完全同构</b>：两种情况下第三方看到的东西应当一模一样。
 *
 * <p><b>为什么必须有这个测试</b>：{@code instance} 是静态字段，只靠读代码看不出
 * 「置空」与「不置空」的差别 —— 而漏掉它不会有任何编译错误、没有任何现有测试变红，
 * 第三方插件却会在插件重载后静默 NPE。这类「静态可达状态未清」的缺陷，
 * 只有断言 {@code getInstance() == null} 能钉住。
 */
@DisplayName("LetsLearnEnglishAPI —— 停用后的单例可见性")
class LetsLearnEnglishAPILifecycleTest {

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        // 静态字段跨用例残留：每个用例前后都清一次，避免顺序依赖。
        LetsLearnEnglishAPI.forceClearInstance();
    }

    @Test
    @DisplayName("构造即注册：getInstance() 返回刚创建的那个实例")
    void constructionRegistersInstance() {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        LetsLearnEnglishAPI api = new LetsLearnEnglishAPI(plugin);

        assertNotNull(LetsLearnEnglishAPI.getInstance(), "构造后 getInstance() 必须立刻可用");
        org.junit.jupiter.api.Assertions.assertSame(api, LetsLearnEnglishAPI.getInstance(),
                "getInstance() 返回的必须是刚构造的那个实例，而不是另一份");
    }

    @Test
    @DisplayName("forceClearInstance()：停用后 getInstance() 变成 null（与「启动即关闭」同构）")
    void forceClearMakesInstanceNull() {
        new LetsLearnEnglishAPI(mock(LetsLearnEnglish.class));
        assertNotNull(LetsLearnEnglishAPI.getInstance());

        // disableAll() 的 D10 调的就是这个 —— 无条件清。
        LetsLearnEnglishAPI.forceClearInstance();

        assertNull(LetsLearnEnglishAPI.getInstance(),
                "forceClearInstance() 之后 getInstance() 必须是 null 喵。\n"
                        + "否则第三方插件拿到的是一个所有 getter 都返回 null Manager 的僵尸对象，"
                        + "而「本插件未启用」这个事实对它不可见。");
    }

    @Test
    @DisplayName("clearInstance()：插件仍 enabled 时不动（别抹掉一个活着实例的注册）")
    void clearInstanceKeepsLiveRegistration() {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        // 关键：模拟「插件还活着」——迟到的停用清理绝不能把刚启用的注册抹掉。
        when(plugin.isEnabled()).thenReturn(true);
        new LetsLearnEnglishAPI(plugin);

        LetsLearnEnglishAPI.clearInstance();

        assertNotNull(LetsLearnEnglishAPI.getInstance(),
                "插件仍 enabled 时 clearInstance() 不该清 —— 那会把一个活着实例的 API 变成 null，"
                        + "第三方在重载窗口里会突然拿不到 API。");
    }

    @Test
    @DisplayName("clearInstance()：插件已 disabled 时清掉自己的残留")
    void clearInstanceClearsDisabledPlugin() {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.isEnabled()).thenReturn(false);
        new LetsLearnEnglishAPI(plugin);

        LetsLearnEnglishAPI.clearInstance();

        assertNull(LetsLearnEnglishAPI.getInstance(),
                "插件已 disabled 时 clearInstance() 必须清 —— 那正是「我们自己的残留」。");
    }
}
