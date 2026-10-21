package com.tipdm.agentplatform.harness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentStateStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 派发前对账守卫的触发回归。
 *
 * <p>要锁的不变量是「守卫在真实调用形态下真的会跑」：中间件链以<b>内层 delegate
 * ReActAgent</b> 为 agent 构建，HarnessAgent 只是外层包装。若按 {@code HarnessAgent}
 * 判型，守卫会静默返回且不留任何日志——测试若只 mock 外层 HarnessAgent 传进去，
 * 这条路径永远绿，缺陷也就永远看不见。</p>
 */
class SubagentPendingCallGuardMiddlewareTest {

    private static final String USER = "user-1";
    private static final String SESSION = "session-1";

    private static ActingInput acting(String... toolNames) {
        List<ToolUseBlock> calls = Arrays.stream(toolNames)
                .map(name -> new ToolUseBlock("call-" + name, name, Map.of()))
                .toList();
        return new ActingInput(calls);
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId(USER).sessionId(SESSION).build();
    }

    private static Flux<AgentEvent> passThrough(ActingInput input) {
        return Flux.empty();
    }

    @Test
    void reconcile_runsWhenAgentIsInnerReactAgent() {
        AgentStateStore store = mock(AgentStateStore.class);
        ReActAgent inner = mock(ReActAgent.class);
        when(inner.getStateStore()).thenReturn(store);

        SubagentSessionMaintenance maintenance = mock(SubagentSessionMaintenance.class);
        SubagentPendingCallGuardMiddleware guard =
                new SubagentPendingCallGuardMiddleware(() -> maintenance);

        guard.onActing(inner, ctx(), acting("task_list", "agent_spawn"),
                SubagentPendingCallGuardMiddlewareTest::passThrough).blockLast();

        verify(maintenance).repairPendingSubSessionsForUser(store, USER);
    }

    @Test
    void reconcile_skippedWhenNoDispatchCall() {
        AgentStateStore store = mock(AgentStateStore.class);
        ReActAgent inner = mock(ReActAgent.class);
        when(inner.getStateStore()).thenReturn(store);

        SubagentSessionMaintenance maintenance = mock(SubagentSessionMaintenance.class);
        SubagentPendingCallGuardMiddleware guard =
                new SubagentPendingCallGuardMiddleware(() -> maintenance);

        guard.onActing(inner, ctx(), acting("read_file", "edit_file"),
                SubagentPendingCallGuardMiddlewareTest::passThrough).blockLast();

        verify(maintenance, never()).repairPendingSubSessionsForUser(any(), any());
    }

    @Test
    void reconcile_skippedWhenStoreUnresolvable() {
        Agent foreign = mock(Agent.class);

        SubagentSessionMaintenance maintenance = mock(SubagentSessionMaintenance.class);
        SubagentPendingCallGuardMiddleware guard =
                new SubagentPendingCallGuardMiddleware(() -> maintenance);

        guard.onActing(foreign, ctx(), acting("agent_spawn"),
                SubagentPendingCallGuardMiddlewareTest::passThrough).blockLast();

        verify(maintenance, never()).repairPendingSubSessionsForUser(any(), any());
    }
}
