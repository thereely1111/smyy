package com.tipdm.agentplatform.service;

/**
 * 一次 AG-UI run 的模型选择覆盖（对话页「模型凭证 → 选模型」需求）。
 *
 * <p><b>传递机制</b>：AG-UI 请求处理顺序是
 * {@code AguiRequestProcessor.process} 里先 {@code runtimeContextResolver.resolve(request)}
 * （平台侧 {@code PlatformAguiRuntimeContextResolver}，此时已解析 JWT 用户并可读取
 * {@code RunAgentInput.forwardedProps}），随后在同一请求线程上调用
 * {@code AgentResolver.resolveAgent} → 注册表工厂 → {@code AgentAssemblyService}。
 * 因此用 ThreadLocal 在「resolver 设置 → 工厂消费」间传递本次 run 的模型覆盖，
 * 无需改动框架代码；下次 run 的 resolve() 无条件 set/clear，旧值不会跨 run 泄漏。</p>
 *
 * <p><b>安全边界</b>：apiKey 来自凭证表解密，仅存在于本次 run 的装配链路内存中，
 * 不落日志、不进 RuntimeContext 出参。</p>
 *
 * @param provider 供应商标识（凭证 credType，如 dashscope / openai）
 * @param model    模型名（可为静态目录内的，也可是自定义模型名）
 * @param apiKey   解密后的 API Key
 * @param baseUrl  可选 Base URL（凭证未配置时为 null，走供应商默认入口）
 * @param cacheKey 装配缓存键因子：credentialId + model + apiKey 指纹（换 key 即换实例）
 */
public record RunModelSelection(
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        String cacheKey) {

    private static final ThreadLocal<RunModelSelection> CURRENT = new ThreadLocal<>();

    public static void set(RunModelSelection selection) {
        CURRENT.set(selection);
    }

    /** 本次 run 是否携带模型覆盖（工厂装配前查询）。 */
    public static RunModelSelection current() {
        return CURRENT.get();
    }

    /** 消费后清理：工厂取走即清，防止线程复用场景残留。 */
    public static void clear() {
        CURRENT.remove();
    }
}
