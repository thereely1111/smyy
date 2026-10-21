package com.tipdm.agentplatform.config;

import com.tipdm.agentplatform.service.AguiEventPersistenceService;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AG-UI 事件落库拦截器（框架 {@link AguiEventEnricher} 扩展点的平台实现）。
 *
 * <p>agentscope-agui-spring-boot-starter 自动装配会把容器内所有
 * {@code AguiEventEnricher} bean 收进 {@code AguiAdapterConfig}（
 * {@code ObjectProvider.orderedStream().toList()}），因此本 bean 一旦注册，
 * 所有经 AguiAgentAdapter 转换输出的 AG-UI 事件都会经过 {@link #enrich}。</p>
 *
 * <p>本实现是<b>纯旁路</b>：捕获后原样返回事件，不修改协议内容、不增加额外事件。
 * capture 内部只做入队（序列化与落库在后台 flush 线程），对 SSE 输出延迟的影响
 * 是一次 ConcurrentLinkedQueue.offer。</p>
 *
 * <p>覆盖范围与已知边界见 {@link com.tipdm.agentplatform.service.AguiEventPersistenceService}。</p>
 */
@Component
public class AguiEventLogEnricher implements AguiEventEnricher {

    private static final Logger log = LoggerFactory.getLogger(AguiEventLogEnricher.class);

    private final AguiEventPersistenceService persistenceService;
    private final AguiEventLogProperties properties;

    public AguiEventLogEnricher(
            AguiEventPersistenceService persistenceService,
            AguiEventLogProperties properties) {
        this.persistenceService = persistenceService;
        this.properties = properties;
        log.info("AG-UI 事件落库拦截器已装配（enabled={}）", properties.isEnabled());
    }

    @Override
    public List<AguiEvent> enrich(AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        if (properties.isEnabled() && events != null && !events.isEmpty()) {
            for (AguiEvent event : events) {
                persistenceService.capture(event);
            }
        }
        return events;
    }
}
