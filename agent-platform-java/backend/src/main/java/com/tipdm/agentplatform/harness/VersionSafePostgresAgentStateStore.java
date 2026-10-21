package com.tipdm.agentplatform.harness;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.State;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;

import javax.sql.DataSource;

/**
 * 平台版会话状态库：只修框架 {@code saveIfVersion(expectedVersion = -1)} 那条分支的必崩缺陷，
 * 其余行为原样交给父类。
 *
 * <p><b>事故背景（2026-09-30 10:51 实测，用户会话 main-20da1032）</b>：唤醒 run 收尾持久化时抛
 * {@code RuntimeException: Failed to get versioned state: agent_state}，堆栈为</p>
 *
 * <pre>
 * PostgresAgentStateStore.getVersioned(:324)
 *   &lt;- PostgresAgentStateStore.saveIfVersion(:333)
 *     &lt;- ReActAgent.persistAgentStateCas(:567)
 * Caused by: JsonException: Failed to deserialize JSON to io.agentscope.core.state.State
 * Caused by: InvalidDefinitionException: Cannot construct instance of
 *            `io.agentscope.core.state.State` (abstract types either need to be mapped …)
 * </pre>
 *
 * <p><b>根因</b>：框架在 {@code expectedVersion == -1} 分支里「先 save、再回读拿版本号」，
 * 回读时把具体类型写成了<b>抽象基类</b> {@code State.class} —— Jackson 实例化不了，必抛。
 * 该分支只有 {@code ReActAgent.persistAgentStateCas} 的
 * <b>CAS 冲突 → {@code ConflictPolicy.OVERWRITE}</b> 会走到，也就是内存 {@code slotVersions}
 * 缓存版本与库中行版本不一致的时候（唤醒 run 与前台 run 交错、平台自身也在写同一行时极易发生）。
 * 平台里另一处 {@code saveIfVersion} 调用（HitlTimeoutReaper）传的是真实版本号，不走这条分支。</p>
 *
 * <p><b>为什么必须修</b>：唤醒 run 一旦保存失败，inbox hint 就永远消费不掉，
 * {@code InboxWatchdogWakeupService} 会每 30–60s 重新唤醒同一会话 —— 无限循环，
 * 每轮烧一次完整的 ReAct 模型调用（实测单会话循环 17 次，日志里表现为智能体反复自语
 * 「第 N 次空恢复」）。</p>
 *
 * <p><b>修法（最小）</b>：只覆盖这一条分支，回读时按实际写入状态的具体类型取类
 * （{@code agent_state} 就是 {@code AgentState}）；语义与框架原意一致 —— 无已知版本即直接覆盖写、
 * 返回新版本号。构建参数沿用 {@code AgentAssemblyService} 原来的取值
 * （schema {@code public}、表名取框架默认 {@code agentscope_sessions}）。</p>
 *
 * <p><b>履历</b>：2026-09-30 首次落地于 smyy 侧 commit {@code 4964fea}；2026-10-10 后端切到
 * 本仓库时未随迁，2026-10-13 按用户指示补回并同步 smyy 副本（kuaike / smyy 共用本后端，
 * 一次修复两端受益）。</p>
 */
public class VersionSafePostgresAgentStateStore extends PostgresAgentStateStore {

    public VersionSafePostgresAgentStateStore(DataSource dataSource, String schemaName,
                                             String tableName, boolean createIfNotExist) {
        super(dataSource, schemaName, tableName, createIfNotExist);
    }

    @Override
    public long saveIfVersion(String userId, String sessionId, String stateKey,
                              State state, long expectedVersion) {
        if (expectedVersion != -1L || state == null) {
            return super.saveIfVersion(userId, sessionId, stateKey, state, expectedVersion);
        }
        // 走到这里 ＝ 无已知版本，直接覆盖写；随后回读一次，只为拿到写后的新版本号。
        save(userId, sessionId, stateKey, state);
        @SuppressWarnings("unchecked")
        Class<State> readAs = (Class<State>) (state instanceof AgentState
                ? AgentState.class : state.getClass());
        return getVersioned(userId, sessionId, stateKey, readAs).version();
    }
}
