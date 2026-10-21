package com.tipdm.agentplatform.service;

/**
 * 一次 AG-UI run 的联网搜索开关（对话页输入框「联网搜索」toggle）。
 *
 * <p><b>传递机制</b>：与 {@link RunModelSelection} 同款 ThreadLocal 通道——
 * {@code PlatformAguiRuntimeContextResolver.resolve()} 每 run 无条件 set/clear
 * （显式关闭时 set(false)，其余一律 clear()=默认开启），旧值不会跨 run 泄漏；
 * {@code AgentAssemblyService.assembleForRun} 在装配期消费，联网搜索关闭时
 * 不注册平台搜索工具 {@code bocha_web_search}。</p>
 *
 * <p><b>语义</b>：ThreadLocal 缺省 = 默认开启（贴合「默认联网收集，除非明确
 * 禁止」的平台口径）；仅当前端显式携带 {@code forwardedProps.web_search=false}
 * 时才关闭。装配缓存键含 on/off 因子，两种形态的实例互不串用。</p>
 *
 * <p>框架自带的 web_fetch / web_search（Tavily）由框架 build() 无条件注册，
 * 本开关不覆盖——前者只是拉取指定 URL（无 Key 依赖），后者缺
 * TAVILY_API_KEY 时返回错误文本，均无害。</p>
 */
public final class RunWebSearchSelection {

    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<>();

    private RunWebSearchSelection() {
    }

    /** 标记本次 run 联网搜索被显式关闭（装配期跳过搜索工具注册）。 */
    public static void setDisabled() {
        ENABLED.set(Boolean.FALSE);
    }

    /** 清除标记（默认开启语义），防止线程复用残留。 */
    public static void clear() {
        ENABLED.remove();
    }

    /** 本次 run 是否启用联网搜索：无标记 = 默认开启。 */
    public static boolean isEnabled() {
        return ENABLED.get() != Boolean.FALSE;
    }
}
