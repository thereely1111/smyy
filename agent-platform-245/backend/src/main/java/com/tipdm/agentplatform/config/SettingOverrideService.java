package com.tipdm.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.PlatformSetting;
import com.tipdm.agentplatform.repository.PlatformSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配置覆盖层：让 {@code application.yml} 里的一部分配置能在网页上改、且立即生效。
 *
 * <h2>为什么技术上可行</h2>
 *
 * <p>因为本项目绝大多数配置项的消费点是<b>方法体内读 getter</b>，不是启动期快照。
 * 典型：{@code HitlTimeoutReaper} 每次 sweep 首行重读开关与超时；
 * {@code SessionProgressService} 的多处阈值判定全在方法体内；
 * {@code AlertNotifier} 每次推送重读去重窗口与企微配置。
 * 因此把Bean 字段改掉，下一次消费就拿到新值——不需要替换任何读取机制。</p>
 *
 * <p>⚠️ 反面案例：{@code AguiEventPersistenceService} 把 flush 间隔在<b>构造器内</b>
 * 提交给了 {@code scheduleWithFixedDelay}，改字段无效。这类「启动期烧进调度器」的
 * 配置被 {@link SettingCatalog} 挡在收录范围之外，不给用户「保存成功」的假象。</p>
 *
 * <h2>优先级</h2>
 *
 * <p>本表覆盖值 &gt; 环境变量 &gt; {@code application.yml} 默认值。本类在
 * {@link ApplicationReadyEvent} 时读表并覆盖到 Bean，因此表中无行时行为与本类存在
 * 之前完全一致，部署方不需要任何改动。</p>
 *
 * <p><b>为什么覆盖发生在 ApplicationReadyEvent 而不是更早</b>：此时 Bean 绑定已全部
 * 完成。副作用是覆盖发生在 {@code UploadPolicyProperties} 的 {@code @PostConstruct}
 * 启动校验之后，所以网页写入的值不被那套校验兜住，本类需另做校验
 * （见 {@link #validateUploadLimits}）。</p>
 */
@Service
public class SettingOverrideService {

    private static final Logger log = LoggerFactory.getLogger(SettingOverrideService.class);

    private final SettingCatalog catalog;
    private final PlatformSettingRepository repository;
    private final ObjectMapper mapper;

    /** 容器 multipart 上限，用于校验上传类配置；字段注入以保证晚于本 bean 创建。 */
    @Autowired
    private MultipartProperties multipart;

    public SettingOverrideService(SettingCatalog catalog,
                                  PlatformSettingRepository repository,
                                  ObjectMapper mapper) {
        this.catalog = catalog;
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * 启动完成后把表内覆盖值应用到运行时 Bean。
     *
     * <p>逐项 try/catch：单个覆盖值非法（类型不符、被手工改成非法值等）只让该项
     * 退回默认值并记 error，<b>不阻止应用启动</b>。理由：网页写入的值都过校验，
     * 会走到这条路径的只能是直接改库；而因一行脏数据让整个平台起不来，代价远大于
     * 「这一项没生效」。真正写入时仍然拦住（见 {@link #save}）。</p>
     */
    /** 启动时应用覆盖<b>之前</b>的各键取值，供 {@link #reset} 回退用。 */
    private final Map<String, Object> defaults = new LinkedHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void applyOverridesAtStartup() {
        // 快照必须在应用覆盖之前拍：此时 Bean 里是「环境变量 / yml」的值，
        // 正是「没有覆盖时」的行为，也是恢复默认该回到的地方。
        for (String key : catalog.keys()) {
            SettingCatalog.Descriptor d = catalog.find(key);
            try {
                defaults.put(key, copyOf(d.read().get()));
            } catch (RuntimeException e) {
                log.warn("读取配置 {} 的默认值失败：{}", key, e.getMessage());
            }
        }

        List<PlatformSetting> rows = repository.findAll();
        if (rows.isEmpty()) {
            log.info("配置覆盖层为空，使用环境变量与 application.yml 的取值");
            return;
        }
        int applied = 0;
        for (PlatformSetting row : rows) {
            SettingCatalog.Descriptor d = catalog.find(row.getKey());
            if (d == null) {
                // 不静默丢弃：未登记的键要么是历史遗留，要么有人手工插了行
                log.warn("platform_setting 含未登记的配置键 {}，已忽略（不属于可热改范围）", row.getKey());
                continue;
            }
            try {
                Object value = parse(row.getValue(), d.type());
                d.validate(value);
                d.apply().apply(value);
                applied++;
                log.info("配置覆盖已应用：{} = {}", row.getKey(), display(d, value));
            } catch (RuntimeException e) {
                log.error("配置覆盖失败：{}（{}），该项退回默认值", row.getKey(), e.getMessage());
            }
        }
        log.info("配置覆盖层已应用 {}/{} 项", applied, rows.size());
    }

    /**
     * 全部可改配置项的当前生效值。
     *
     * <p>值读自运行时 Bean（即「真正在跑的那个值」），不是重新解析表里的文本——
     * 否则页面可能显示一个从未真正生效过的值。敏感项以 {@code ***} 掩码返回，
     * 页面只需知道「配没配」，不需要知道配的是什么。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> currentValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : catalog.keys()) {
            SettingCatalog.Descriptor d = catalog.find(key);
            Object raw = d.read().get();
            if (d.secret()) {
                out.put(key, raw == null || raw.toString().isEmpty() ? null : "***");
            } else if (raw instanceof List<?> list) {
                out.put(key, List.copyOf(list));
            } else {
                out.put(key, raw);
            }
        }
        return out;
    }

    /** 当前存在数据库覆盖的键；页面上用于标注「已覆盖」。 */
    @Transactional(readOnly = true)
    public Set<String> overriddenKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (PlatformSetting row : repository.findAll()) {
            if (catalog.find(row.getKey()) != null) {
                out.add(row.getKey());
            }
        }
        return out;
    }

    /**
     * 保存覆盖值：先全量校验，再逐项落库并应用。
     *
     * <p><b>先校验全部、再落库</b>：否则一次保存里前面几项已生效而后面一项被拒，
     * 用户看到报错，但配置已经改了一半。</p>
     *
     * @param changes  键 → 新值；敏感项传 {@code ***} 表示不改这一项
     * @param operator 操作者（审计）
     */
    @Transactional
    public void save(Map<String, Object> changes, UUID operator) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();

        for (Map.Entry<String, Object> e : changes.entrySet()) {
            SettingCatalog.Descriptor d = catalog.find(e.getKey());
            if (d == null) {
                // 拒绝而不是忽略：忽略会让调用方以为已改成功
                throw new IllegalArgumentException(
                        "不支持网页修改的配置项：" + e.getKey()
                                + "（该配置改了需要重启服务，或它本身不生效）");
            }
            Object value = e.getValue();
            if (d.secret() && SECRET_MASK.equals(value)) {
                continue;
            }
            try {
                d.validate(value);
                accepted.put(e.getKey(), value);
            } catch (IllegalArgumentException ex) {
                rejected.add(ex.getMessage());
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", rejected));
        }
        validateUploadLimits(accepted);

        Instant now = Instant.now();
        for (Map.Entry<String, Object> e : accepted.entrySet()) {
            SettingCatalog.Descriptor d = catalog.find(e.getKey());
            PlatformSetting row = repository.findById(e.getKey()).orElseGet(() -> {
                PlatformSetting r = new PlatformSetting();
                r.setKey(e.getKey());
                r.setCreatedAt(now);
                return r;
            });
            row.setValue(write(e.getValue()));
            row.setUpdatedBy(operator);
            row.setUpdatedAt(now);
            repository.save(row);
            d.apply().apply(e.getValue());
            log.info("配置覆盖已更新：{} = {}（操作者 {}）", d.key(), display(d, e.getValue()), operator);
        }
    }

    /**
     * 移除某项覆盖，让它回到默认值。
     *
     * <p><b>同时必须把运行时 Bean 改回默认值</b>。只删库不改 Bean 的话，当前进程
     * 仍按覆盖值跑、要重启才变——那正是「改了但没重启」的困惑来源。</p>
     *
     * <p>默认值取自 {@link #defaults}（启动时应用覆盖<b>之前</b>的快照），而不是重新
     * 读 {@code Environment}。两个原因：</p>
     * <ol>
     *   <li><b>部分键在 yml 里没有对应项</b>（如 {@code alert.unread-suppress-max-hours}），
     *       走 Environment 会取不到，于是「删了覆盖但运行时仍是覆盖值」——比不删更糟，
     *       数据库与运行时直接不一致。</li>
     *   <li><b>Environment 的文本形态与 Bean 绑定口径不同</b>：
     *       {@code upload-policy.allowed-extensions} 在 yml 里是逗号串
     *       {@code "txt, md, json"}，而 Bean 绑定会把它切成 List。按 JSON 解析逗号串
     *       会直接抛错。快照存的是已经切好的 List，无此问题。</li>
     * </ol>
     */
    @Transactional
    public void reset(String key, UUID operator) {
        SettingCatalog.Descriptor d = catalog.find(key);
        if (d == null) {
            throw new IllegalArgumentException("不支持网页修改的配置项：" + key);
        }
        repository.deleteById(key);

        Object fallback = defaults.get(key);
        if (fallback == null) {
            // 只有「覆盖后才被新增的注册项」才会走到这里：快照里没有它。
            // 此时删掉覆盖但保留运行时值，并明确记账——不能悄悄改成别的值。
            log.warn("配置 {} 无默认值快照（该注册项在本次启动后才加入），已删除覆盖但运行时值保持不变",
                    key);
        } else {
            d.apply().apply(fallback);
            log.info("配置覆盖已移除，{} 回到默认值 {}", key, display(d, fallback));
        }
        log.info("配置覆盖已移除：{}（操作者 {}）", key, operator);
    }

    /**
     * 上传上限的跨项校验。
     *
     * <p>三个上限不得超过容器 multipart 上限：超限的文件在<b>进入 Controller 之前</b>
     * 就被容器拒绝，业务层的可读提示永不生效（历史上发生过一次：上限写 100MB 而
     * multipart 25MB）。{@code UploadPolicyProperties} 的启动期校验只在启动时跑一次，
     * 覆盖值不走它，故此处补一份。</p>
     */
    private void validateUploadLimits(Map<String, Object> changes) {
        if (multipart == null) {
            return;
        }
        long limit = multipart.getMaxFileSize().toBytes();
        for (String key : List.of("upload-policy.drive-max-bytes",
                "upload-policy.attachment-max-bytes",
                "upload-policy.knowledge-document-max-bytes")) {
            Object v = changes.get(key);
            if (v instanceof Number n && n.longValue() > limit) {
                throw new IllegalArgumentException(key.replace('-', ' ') + "超过容器 multipart 上限 "
                        + (limit / (1024 * 1024)) + "MB。超限会在进入 Controller 前被容器拒绝，"
                        + "业务层的可读提示永远不生效。");
            }
        }
    }

    /**
     * 可变值的防御性拷贝。
     *
     * <p>只有集合需要拷贝（{@code normalizedExtensions} 每次返回新List，但保险起见
     * 统一处理）；字符串/数字/布尔本身不可变，直接放行。少这一步的后果：某个
     * {@code Applier} 若把入参直接赋给字段，而入参是快照里的同一个集合实例，
     * 后续读配置页拿到的是可变对象，可能被误改。</p>
     */
    private static Object copyOf(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : value;
    }

    /** 敏感项回给前端与日志时的掩码。 */
    public static final String SECRET_MASK = "***";

    private String display(SettingCatalog.Descriptor d, Object value) {
        return d.secret() ? SECRET_MASK : String.valueOf(value);
    }

    /** 按声明的类型解析库里存的文本。 */
    private Object parse(String text, SettingCatalog.Type type) {
        try {
            return switch (type) {
                case BOOLEAN -> Boolean.parseBoolean(text.trim());
                case NUMBER -> Double.valueOf(text.trim());
                case STRING, ENUM -> text;
                case STRING_LIST -> mapper.readValue(text, List.class);
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("值的格式与声明的类型不符：" + text, e);
        }
    }

    /**
     * 序列化成库里存的文本。
     *
     * <p>数字与布尔直接写字面量（人能在 psql 里看懂），其余按 JSON 存。</p>
     */
    private String write(Object value) {
        try {
            return switch (value) {
                case Boolean b -> b.toString();
                case Number n -> n.toString();
                default -> mapper.writeValueAsString(value);
            };
        } catch (Exception e) {
            throw new IllegalStateException("值的序列化失败", e);
        }
    }
}
