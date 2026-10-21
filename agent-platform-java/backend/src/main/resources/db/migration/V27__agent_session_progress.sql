-- 会话进度台账 + 停滞看护状态（L3：平台级超长任务监听，适用所有智能体）。
--
-- 目的：把「编排智能体当前做到哪、下一步该干嘛」从对话文本外化成结构化记录，
-- 供：(a) 前端/脚本渲染进度；(b) 平台停滞看护（idle 超阈值且 next_action 需继续时
-- 补发一次 wakeup 让父自检，避免「父以为在等后台、实际子任务已结束」的隔夜停摆）。
--
-- 数据来源：平台周期扫描会话转录尾文本，解析父智能体自报的 `[进度]` 行（AGENTS 约定），
-- 无自报行时按尾语关键词做保守分类（闸门等待=WAIT_HITL、完成语=DONE、等待后台语=CONTINUE）。
-- next_action 语义：CONTINUE(应继续推进) / WAIT_HITL(等主编闸门/表单) / DONE / IDLE / UNKNOWN。
CREATE TABLE agent_session_progress (
    id                  UUID PRIMARY KEY,
    agent_id            VARCHAR(64)  NOT NULL,
    session_id          VARCHAR(255) NOT NULL,
    user_id             VARCHAR(64)  NOT NULL,
    phase               VARCHAR(64),
    next_action         VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN',
    progress_line       TEXT,
    last_text           VARCHAR(2000),
    last_active_at      TIMESTAMPTZ,
    last_stall_wake_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, session_id)
);
CREATE INDEX idx_session_progress_user ON agent_session_progress (user_id);
CREATE INDEX idx_session_progress_active ON agent_session_progress (last_active_at);
