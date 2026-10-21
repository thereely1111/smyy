package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部技能仓库接入配置（{@code agent-platform.skill-repository}）。
 *
 * <p>技能仓库是平台技能（skills 表）的远程来源，类型与地址都在配置文件指定，
 * 支持四种类型：</p>
 * <ul>
 *   <li>{@code git} —— url 为 git 仓库地址（https / ssh / 本地路径均可，凭证直接
 *       写进 url 或走 ssh-agent）。同步时 shallow clone 到 cache-dir，之后增量
 *       fetch + reset，再扫描仓库内所有 {@code SKILL.md}。</li>
 *   <li>{@code http} —— url 指向一个 JSON 索引文件（格式见 application.yml 注释），
 *       每个条目声明名称与 SKILL.md 下载地址；正文在查看详情 / 导入时懒加载。</li>
 *   <li>{@code clawhub} —— ClawHub 公共技能注册表（clawhub.ai，对齐官方
 *       ClawSkillHub 示例）；url 可缺省用官方站点，token（clh_...）可选。</li>
 *   <li>{@code skillhub} —— SkillHub 中国区 Skills 社区（skillhub.cn，腾讯云
 *       背书）；url 可缺省用 API 域 api.skillhub.cn（配主站域名自动归一化），
 *       token 可选。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.skill-repository")
public class SkillRepositoryProperties {

    /** 是否启用技能仓库（默认关；未启用时列表为空、详情/导入报 400）。 */
    private boolean enabled = false;

    /** 仓库类型：git | http | clawhub | skillhub。 */
    private String type = "git";

    /** 仓库地址：git 仓库 url，或 JSON 索引文件的 http(s) 地址。 */
    private String url;

    /** git 分支（可选，缺省用远端默认分支）。http 类型忽略。 */
    private String branch;

    /** 访问令牌（可选）：http 类型作为 {@code Authorization: Bearer <token>}；
     *  git 类型不使用（凭证写进 url 或走 ssh）。 */
    private String token;

    /** git 仓库本地缓存目录（shallow clone 落点）。http 类型不使用。 */
    private String cacheDir;

    /** 缓存有效期（分钟）：列表/详情访问时若缓存超龄则自动重新同步；0 表示每次访问都同步。 */
    private long cacheTtlMinutes = 60;

    /** git / http 网络操作超时（秒）。 */
    private long timeoutSeconds = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public long getCacheTtlMinutes() {
        return cacheTtlMinutes;
    }

    public void setCacheTtlMinutes(long cacheTtlMinutes) {
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
