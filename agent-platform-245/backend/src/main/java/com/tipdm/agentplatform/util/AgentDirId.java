package com.tipdm.agentplatform.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 智能体目录名 → 智能体 id 的派生规则。
 *
 * <p><b>这是唯一的实现</b>。智能体目录名决定 agent id，而会话按 id 分区、版本存档与
 * 标签关联都挂在 id 上——一旦 id 漂移，会话会串到别的智能体上，且这种错误在界面上完全
 * 不可见。因此目录型加载、导入登记、迁移补齐三条路径必须调用同一处。
 */
public final class AgentDirId {

    private AgentDirId() {
    }

    /**
     * 由智能体目录名派生稳定 id。
     *
     * @param dirName 智能体目录名（人工可读，可能含中文）
     * @return 稳定 id
     */
    public static UUID of(String dirName) {
        return UUID.nameUUIDFromBytes(dirName.getBytes(StandardCharsets.UTF_8));
    }
}
