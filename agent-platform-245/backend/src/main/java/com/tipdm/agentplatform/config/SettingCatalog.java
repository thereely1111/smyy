package com.tipdm.agentplatform.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 网页可改配置项的注册表：<b>唯一真源</b>。
 *
 * <p>一份配置项的 key、类型、范围、分组、说明、读写方式全部在这里声明一次。
 * 前端表单、启动覆盖、保存校验、页面显示当前值，四处都从本注册表派生——
 * 不在任何地方另写一份键名清单或「键→Bean 字段」的映射，那正是
 * 「同一判据写多处必然漂移」的起点。</p>
 *
 * <h2>收录标准：消费方必须每次读 getter</h2>
 *
 * <p>只有「消费点在方法体内、每次都重新读 getter」的字段才出现在页面上。
 * 下面几类一律不收录，因为它们改了不生效，而页面给用户「保存成功」比不提供更糟
 * （用户会按新值行事，行为却没变）：</p>
 * <ul>
 *   <li><b>启动期烧进调度器</b>：{@code agui-event-log.flush-interval-ms} 的值在
 *       {@code AguiEventPersistenceService} 构造器内提交给
 *       {@code scheduleWithFixedDelay}，Java 无API 能改已注册任务的周期。
 *       它<b>有 setter</b>（死 setter）——正因为有 setter 才容易被误收。</li>
 *   <li><b>无消费方</b>：{@code alert.retention-days} 全仓只有 getter 自身，
 *       仓储的 {@code deleteOlderThan} 零调用者，没有任何清理任务在消费。</li>
 *   <li><b>字段可改但消费方已拷贝</b>：{@code auth.jwt-secret-key} 的三个消费方
 *       各自在构造器内派生成 {@code final SecretKey}。</li>
 *   <li><b>语言级锁死</b>：{@code document-preview.*}、{@code library.root}、
 *       {@code knowledge-base.root} 是 {@code private final}。</li>
 *   <li><b>框架读取</b>：{@code agentscope.agui.*} 由 agentscope starter 读取且
 *       存为 final 字段，启动期取一次。</li>
 *   <li><b>cron 占位符</b>：{@code @Scheduled(cron="${...}")} 的表达式在启动期
 *       解析并注册 trigger，改配置不改调度（{@code runtime-gc-cron} 等）。</li>
 * </ul>
 *
 * <p>⚠️ 新增配置项时，先确认消费点是<b>方法体内读 getter</b>，再登记到本类。
 * 只看到「它是个 properties bean」不足以判断——上面那个死 setter 就是反例。</p>
 */
@Component
public class SettingCatalog {

    /** 值的类型，决定前端渲染哪种控件、以及读写时怎么解析。 */
    public enum Type {
        /** 开关。 */
        BOOLEAN,
        /** 数字，配合 {@link Descriptor} 的 min/max。 */
        NUMBER,
        /** 单行文本。 */
        STRING,
        /** 受限枚举，配合 {@link Descriptor} 的 options。 */
        ENUM,
        /** 字符串列表（前端按逗号或换行提交）。 */
        STRING_LIST
    }

    /** 从运行时 Bean 读当前生效值。 */
    @FunctionalInterface
    public interface Reader {
        Object get();
    }

    /**
     * 把值应用到运行时 Bean。
     *
     * <p><b>必须走 setter，不能反射写字段</b>：{@code UploadPolicyProperties} 的
     * {@code normalizedExtensions} 派生 Set 在其 setter 内同步重建
     * （见 {@code UploadPolicyProperties} 的 setter）。绕过 setter 写字段，
     * {@code isAllowed()} 仍读旧 Set——字段看起来改了，判定用的还是旧的。</p>
     */
    @FunctionalInterface
    public interface Applier {
        void apply(Object value);
    }

    /**
     * 一个可改配置项的完整声明。
     *
     * @param key 配置键，与 {@code application.yml} 路径一致（如 {@code alert.wecom.enabled}）
     * @param type 值类型
     * @param group 分组 id，对应 {@link Group#id()}
     * @param label 中文名，前端直接显示
     * @param description 说明。写「这个值控制什么」，不是「这是什么字段」
     * @param unit 单位，无单位为 {@code null}；前端显示在数值输入框后
     * @param min 数值下界，仅 {@link Type#NUMBER} 用
     * @param max 数值上界，仅 {@link Type#NUMBER} 用
     * @param options 枚举可选值，仅 {@link Type#ENUM} 用
     * @param secret 是否敏感
     * @param read 读当前生效值
     * @param apply 写入运行时 Bean
     */
    public record Descriptor(
            String key,
            Type type,
            String group,
            String label,
            String description,
            String unit,
            Double min,
            Double max,
            List<String> options,
            boolean secret,
            Reader read,
            Applier apply) {

        /** 校验一个提交值；不合法时抛出的文案要能直接指导用户怎么改。 */
        public void validate(Object value) {
            if (value == null) {
                throw new IllegalArgumentException(label + "不能为空");
            }
            switch (type) {
                case BOOLEAN -> {
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException(label + "只接受 true / false");
                    }
                }
                case NUMBER -> {
                    double d;
                    if (value instanceof Number n) {
                        d = n.doubleValue();
                    } else {
                        try {
                            d = Double.parseDouble(value.toString().trim());
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException(label + "需要一个数字，收到：" + value);
                        }
                    }
                    if (min != null && d < min) {
                        throw new IllegalArgumentException(label + "不能小于 " + fmt(min));
                    }
                    if (max != null && d > max) {
                        throw new IllegalArgumentException(label + "不能大于 " + fmt(max));
                    }
                }
                case ENUM -> {
                    String s = value.toString();
                    if (!options.contains(s)) {
                        throw new IllegalArgumentException(
                                label + "只接受 " + String.join(" / ", options) + "，收到：" + s);
                    }
                }
                case STRING_LIST -> {
                    if (!(value instanceof List<?>)) {
                        throw new IllegalArgumentException(label + "需要一个列表");
                    }
                }
                case STRING -> {
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException(label + "需要一个字符串");
                    }
                }
                default -> throw new IllegalStateException("未处理的类型：" + type);
            }
        }
    }

    /** 配置分组，一个分组对应页面上的一个区块。 */
    public record Group(String id, String label, String description) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("watchdog", "停滞看护", "会话多久没动静算停滞、多久补醒一次、什么时候通知人"),
            new Group("hitl", "审批超时", "挂起的审批/表单等多久自动取消"),
            new Group("alert", "告警", "告警总开关、去重窗口，以及是否外发到企业微信"),
            new Group("upload", "上传限制", "允许上传的扩展名与各类文件的大小上限"),
            new Group("skillrepo", "技能仓库", "外部技能仓库的接入方式与缓存策略"));

    private final Map<String, Descriptor> byKey = new LinkedHashMap<>();
    private final Map<String, List<Descriptor>> byGroup = new LinkedHashMap<>();

    public SettingCatalog(ProgressWatchdogProperties watchdog,
                          HitlTimeoutProperties hitl,
                          AlertProperties alert,
                          UploadPolicyProperties upload,
                          SkillRepositoryProperties skillRepo) {
        AlertProperties.Wecom wecom = alert.getWecom();

        // ——停滞看护：SessionProgressService 每次 scanOne 都重读这些阈值 ——
        num("watchdog", "progress-watchdog.stall-min-minutes",
                "停滞判定阈值", "会话超过该分钟数没有活动、且判定为「还需推进」时，视为疑似停滞",
                "分钟", 1d, 1440d,
                v -> watchdog.setStallMinMinutes(toLong(v)), watchdog::getStallMinMinutes);
        num("watchdog", "progress-watchdog.wake-cooldown-minutes",
                "补醒冷却", "同一会话两次自动补醒的最小间隔，防止反复唤醒烧 token",
                "分钟", 1d, 1440d,
                v -> watchdog.setWakeCooldownMinutes(toLong(v)), watchdog::getWakeCooldownMinutes);
        num("watchdog", "progress-watchdog.max-wake-attempts",
                "补醒上限", "同一轮停滞最多自动补醒几次；达上限即停止自愈并转人工告警",
                "次", 0d, 100d,
                v -> watchdog.setMaxWakeAttempts(toInt(v)), watchdog::getMaxWakeAttempts);
        num("watchdog", "progress-watchdog.active-window-hours",
                "扫描时间窗", "只扫描最近该小时数内活跃过的会话，更早的不再纳入看护",
                "小时", 1d, 8760d,
                v -> watchdog.setActiveWindowHours(toLong(v)), watchdog::getActiveWindowHours);
        num("watchdog", "progress-watchdog.stall-alert-minutes",
                "停滞告警阈值", "空闲超过该值且未判定为已完成时，推送一条停滞告警",
                "分钟", 1d, 10080d,
                v -> watchdog.setStallAlertMinMinutes(toLong(v)), watchdog::getStallAlertMinMinutes);
        num("watchdog", "progress-watchdog.hitl-alert-minutes",
                "等待输入告警阈值", "存在挂起的表单/确认且空闲超过该值时，推送「等待你输入」通知",
                "分钟", 1d, 10080d,
                v -> watchdog.setHitlAlertMinMinutes(toLong(v)), watchdog::getHitlAlertMinMinutes);
        num("watchdog", "progress-watchdog.text-ending-grace-minutes",
                "纯文本收尾宽限期", "模型用纯文本宣告收尾后，该时长内仍无新消息就提前补醒",
                "分钟", 0d, 1440d,
                v -> watchdog.setTextEndingGraceMinutes(toLong(v)), watchdog::getTextEndingGraceMinutes);
        num("watchdog", "progress-watchdog.lease-stale-minutes",
                "租约心跳新鲜度", "心跳超过该分钟数未续写的租约视为僵尸，停滞闸门不再被它关死",
                "分钟", 1d, 60d,
                v -> watchdog.setLeaseStaleMinutes(toLong(v)), watchdog::getLeaseStaleMinutes);

        // ——HITL 超时：HitlTimeoutReaper 每次 sweep 首行重读 ——
        bool("hitl", "hitl-timeout.enabled",
                "启用审批超时", "关闭后挂起的审批/表单将无限期等待，不再自动取消",
                v -> hitl.setEnabled(toBool(v)), hitl::isEnabled);
        num("hitl", "hitl-timeout.approval-timeout-minutes",
                "审批超时时长", "闸门/表单挂起超过该时长即自动取消并通知用户",
                "分钟", 1d, 10080d,
                v -> hitl.setApprovalTimeoutMinutes(toLong(v)), hitl::getApprovalTimeoutMinutes);

        // ——告警：AlertNotifier 每次 publish / isSuppressed / maybePushWecom 重读 ——
        bool("alert", "alert.enabled",
                "告警总开关", "关闭后告警只记日志，不落库、不推送",
                v -> alert.setEnabled(toBool(v)), alert::isEnabled);
        num("alert", "alert.dedup-cooldown-minutes",
                "去重冷却窗口", "同一告警在该窗口内只推一次，防止看护反复命中同一问题",
                "分钟", 0d, 10080d,
                v -> alert.setDedupCooldownMinutes(toLong(v)), alert::getDedupCooldownMinutes);
        num("alert", "alert.unread-suppress-max-hours",
                "未读抑制时效", "「提醒到你看过为止」的最长时效；超时后降级为按冷却窗口去重",
                "小时", 1d, 8760d,
                v -> alert.setUnreadSuppressMaxHours(toLong(v)), alert::getUnreadSuppressMaxHours);
        bool("alert", "alert.wecom.enabled",
                "外发企业微信", "关闭时告警只走站内通知",
                v -> wecom.setEnabled(toBool(v)), wecom::isEnabled);
        secretStr("alert", "alert.wecom.webhook-url",
                "群机器人地址",
                "形如 https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx 的群机器人 webhook",
                v -> wecom.setWebhookUrl(toText(v)), wecom::getWebhookUrl);
        choice("alert", "alert.wecom.min-level",
                "外发最低级别", "低于该级别的告警不外发，避免打扰人",
                List.of("info", "warn", "error"),
                v -> wecom.setMinLevel(toText(v)), wecom::getMinLevel);
        num("alert", "alert.wecom.timeout-seconds",
                "外发超时", "单次调用 webhook 的超时时间；失败一律吞掉，不影响主流程",
                "秒", 1d, 60d,
                v -> wecom.setTimeoutSeconds(toLong(v)), wecom::getTimeoutSeconds);

        // ——上传限制：各上传端点每次请求都读 ——
        list("upload", "upload-policy.allowed-extensions",
                "允许的扩展名",
                "资料库上传、会话附件、前端文件选择器共用这一份；改这里三处同时生效",
                v -> upload.setAllowedExtensions(toStringList(v)), upload::normalizedExtensions);
        num("upload", "upload-policy.drive-max-bytes",
                "资料库文件上限", "单个资料库文件的大小上限；不得超过容器的 multipart 上限",
                null, 1d, null,
                v -> upload.setDriveMaxBytes(toLong(v)), upload::getDriveMaxBytes);
        num("upload", "upload-policy.attachment-max-bytes",
                "会话附件上限", "单个会话附件的大小上限；不得超过容器的 multipart 上限",
                null, 1d, null,
                v -> upload.setAttachmentMaxBytes(toLong(v)), upload::getAttachmentMaxBytes);
        num("upload", "upload-policy.knowledge-document-max-bytes",
                "知识库文档上限", "单个知识库文档的大小上限；不得超过容器的 multipart 上限",
                null, 1d, null,
                v -> upload.setKnowledgeDocumentMaxBytes(toLong(v)), upload::getKnowledgeDocumentMaxBytes);

        // ——技能仓库：SkillRepositoryService 每次同步/读取都重读 ——
        bool("skillrepo", "skill-repository.enabled",
                "启用技能仓库", "关闭后技能仓库列表为空，导入入口不可用",
                v -> skillRepo.setEnabled(toBool(v)), skillRepo::isEnabled);
        choice("skillrepo", "skill-repository.type",
                "仓库类型", "git 仓库 / http JSON 索引 / ClawHub / SkillHub 中国区",
                List.of("git", "http", "clawhub", "skillhub"),
                v -> skillRepo.setType(toText(v)), skillRepo::getType);
        str("skillrepo", "skill-repository.url",
                "仓库地址",
                "git 仓库地址，或 http 类型的 JSON 索引文件地址；ClawHub / SkillHub 可留空用官方站点",
                v -> skillRepo.setUrl(toText(v)), skillRepo::getUrl);
        str("skillrepo", "skill-repository.branch",
                "git 分支", "留空则用远端默认分支；仅 git 类型有效",
                v -> skillRepo.setBranch(toText(v)), skillRepo::getBranch);
        secretStr("skillrepo", "skill-repository.token",
                "访问令牌", "http 类型作为 Bearer 令牌；ClawHub / SkillHub 的限流额度提升也用它",
                v -> skillRepo.setToken(toText(v)), skillRepo::getToken);
        str("skillrepo", "skill-repository.cache-dir",
                "本地缓存目录", "git 仓库的 clone 落点；留空则用 {项目根}/.skill-repository-cache",
                v -> skillRepo.setCacheDir(toText(v)), skillRepo::getCacheDir);
        num("skillrepo", "skill-repository.cache-ttl-minutes",
                "缓存有效期", "超过该时长后下次访问会自动重新同步；0 表示每次都同步",
                "分钟", 0d, 10080d,
                v -> skillRepo.setCacheTtlMinutes(toLong(v)), skillRepo::getCacheTtlMinutes);
        num("skillrepo", "skill-repository.timeout-seconds",
                "网络超时", "git / http 操作的超时时间",
                "秒", 1d, 3600d,
                v -> skillRepo.setTimeoutSeconds(toLong(v)), skillRepo::getTimeoutSeconds);
    }

    private void bool(String group, String key, String label, String desc,
                      Applier apply, Reader read) {
        add(new Descriptor(key, Type.BOOLEAN, group, label, desc, null,
                null, null, null, false, read, apply));
    }

    private void num(String group, String key, String label, String desc, String unit,
                     Double min, Double max, Applier apply, Reader read) {
        add(new Descriptor(key, Type.NUMBER, group, label, desc, unit,
                min, max, null, false, read, apply));
    }

    private void str(String group, String key, String label, String desc,
                     Applier apply, Reader read) {
        add(new Descriptor(key, Type.STRING, group, label, desc, null,
                null, null, null, false, read, apply));
    }

    private void secretStr(String group, String key, String label, String desc,
                           Applier apply, Reader read) {
        add(new Descriptor(key, Type.STRING, group, label, desc, null,
                null, null, null, true, read, apply));
    }

    private void choice(String group, String key, String label, String desc,
                        List<String> options, Applier apply, Reader read) {
        add(new Descriptor(key, Type.ENUM, group, label, desc, null,
                null, null, options, false, read, apply));
    }

    private void list(String group, String key, String label, String desc,
                      Applier apply, Reader read) {
        add(new Descriptor(key, Type.STRING_LIST, group, label, desc, null,
                null, null, null, false, read, apply));
    }

    private void add(Descriptor d) {
        byKey.put(d.key(), d);
        byGroup.computeIfAbsent(d.group(), k -> new ArrayList<>()).add(d);
    }

    /** 按注册顺序的全部分组。 */
    public List<Group> groups() {
        return GROUPS;
    }

    /** 某分组下的配置项；未登记的分组返回空表而非 null。 */
    public List<Descriptor> inGroup(String group) {
        return byGroup.getOrDefault(group, List.of());
    }

    /** 按键取声明；未登记返回 {@code null}（调用方据此拒绝写入）。 */
    public Descriptor find(String key) {
        return byKey.get(key);
    }

    /** 全部登记的配置键，注册顺序。 */
    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static long toLong(Object v) {
        return ((Number) v).longValue();
    }

    private static int toInt(Object v) {
        return ((Number) v).intValue();
    }

    private static boolean toBool(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }

    /**
     * 字符串取值的宽松解析：null 归一为空串。
     *
     * <p>存在的理由是 {@code SkillRepositoryProperties} 的 url / branch / token /
     * cacheDir 默认是 {@code null}，而提交空值时前端发的是 {@code ""}；不归一的话
     * 一个字段会因为 {@code ""} 与 {@code null} 拿到不同的默认值。</p>
     */
    private static String toText(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 列表值归一：去空、去空白项。扩展名的去点与转小写由被调方自己负责。 */
    static List<String> toStringList(Object v) {
        if (!(v instanceof List<?> raw)) {
            throw new IllegalArgumentException("需要一个列表");
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
