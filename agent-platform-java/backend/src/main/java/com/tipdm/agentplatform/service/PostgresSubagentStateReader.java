package com.tipdm.agentplatform.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link SubagentReportService.SubagentStateReader} 的 Postgres 实现：从框架持久化的
 * {@link AgentState} 里取出该会话**助手角色的全部文本**（按时间序）。
 *
 * <p>与运行时用的是同一个存储（{@code public} schema 的 {@code agentscope_sessions}），
 * 启动时通过框架初始化状态表，兼容首次部署的空库；业务方法只做只读查询。</p>
 *
 * <p>只挑助手文本、且返回全部：汇报块在哪一条由调用方（从后往前找）决定，读侧不预设
 * "最后一条就是最终回复"——模型写完产物后继续调工具时，最后一条文本可能只是过程说明。</p>
 */
@Component
public class PostgresSubagentStateReader implements SubagentReportService.SubagentStateReader {

    private static final Logger log = LoggerFactory.getLogger(PostgresSubagentStateReader.class);

    private static final String AGENT_STATE_KEY = "agent_state";

    private final PostgresAgentStateStore stateStore;

    public PostgresSubagentStateReader(DataSource dataSource) {
        this.stateStore = PostgresAgentStateStore.builder(dataSource)
                .schemaName("public")
                .createIfNotExist(true)
                .build();
    }

    @Override
    public List<String> assistantTexts(String userId, String sessionId) {
        Optional<AgentState> state = stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        if (state.isEmpty() || state.get().getContext() == null) {
            log.debug("Subagent state read: no persisted context (session={})", sessionId);
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Msg msg : state.get().getContext()) {
            if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        log.debug("Subagent state read: session={}, assistant texts={}", sessionId, texts.size());
        return texts;
    }
}
