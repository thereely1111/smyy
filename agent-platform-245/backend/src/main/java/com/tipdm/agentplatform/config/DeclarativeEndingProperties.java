package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 纯文本收尾登记开关。
 *
 * <p>宣告式收尾检测器（run 级）只做一件事：run 以「末条助手文本后无工具调用」
 * 正常收场时，把时间戳登记进进度台账（V61），决策全部由停滞看护结合完成契约
 * （session-complete.json 交付物指纹核验）、等人工、停止抑制等完整事实判定。
 * 早期版本在此按文本 pattern 决定是否自动续跑——判定押在措辞上，列举式/汇报式
 * 宣告追不完（会话停滞 293 分钟实证），已随 V61 移除。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.declarative-ending")
public class DeclarativeEndingProperties {

    /** 是否启用纯文本收尾登记。 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
