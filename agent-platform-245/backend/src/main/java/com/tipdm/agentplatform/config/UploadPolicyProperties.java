package com.tipdm.agentplatform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 上传闸门配置（{@code agent-platform.upload-policy}）。
 *
 * <p><b>扩展名白名单只有一份</b>：资料库 drive 上传、会话附件上传、
 * 前端文件选择器的 {@code accept} 全部由它派生。三者此前各写一份常量、
 * 靠注释互相比对——新增类型漏改一处就出现「同一个文件在对话里能传、
 * 在资料库传不了」，而没有任何东西会报错。改为配置后前端从
 * {@code GET /api/upload-policy} 读，新增类型只改本配置一节。</p>
 *
 * <p><b>前端 {@code accept} 只是选择器提示，不是闸门</b>：拖拽与目录上传
 * 都能绕过它，真正的判定在后端 {@link #isAllowed(String)}。因此前端在本配置
 * 拉到之前一律放行（见前端 {@code upload-policy.ts}）——配置拉不到不该
 * 表现为「所有文件都传不了」。</p>
 *
 * <p><b>三个上限各配各的</b>：drive 25MB、附件 20MB、知识库文档 20MB。
 * 会话附件与资料库是两条业务线（前者随会话销毁、后者长期留存），
 * 上限可以不同，但扩展名口径保持一致。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.upload-policy")
public class UploadPolicyProperties {

    private static final Logger log = LoggerFactory.getLogger(UploadPolicyProperties.class);

    /** MB 换算基数，用于日志与报错文案。 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 内置默认白名单，与 {@code application.yml} 的取值一致。 */
    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            "txt", "md", "markdown", "json", "csv", "tsv",
            "py", "java", "js", "ts", "tsx", "jsx", "html", "css", "sql", "xml", "yaml", "yml", "sh",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg");

    /**
     * 允许的扩展名（小写、不带点；配置里写 {@code .MD} 也按 {@code md} 归一）。
     *
     * <p>空列表会在启动时被拒（见 {@link #validate()}）——闸门全开等于没有闸门，
     * 而「配置漏写」比「闸门失效」更难被发现。</p>
     */
    private List<String> allowedExtensions = DEFAULT_EXTENSIONS;

    /** 资料库 drive 文件单文件上限（字节）。 */
    private long driveMaxBytes = 25L * BYTES_PER_MB;

    /** 会话附件单文件上限（字节）。 */
    private long attachmentMaxBytes = 20L * BYTES_PER_MB;

    /** 知识库文档单文件上限（字节）。 */
    private long knowledgeDocumentMaxBytes = 20L * BYTES_PER_MB;

    /**
     * 归一化后的扩展名集合，判定热路径用；由 {@link #setAllowedExtensions} 维护，
     * 不参与配置绑定。
     */
    private Set<String> normalizedExtensions = normalize(DEFAULT_EXTENSIONS);

    /**
     * 容器 multipart 上限，只用于启动校验。
     *
     * <p>字段注入而非构造参数：{@code @ConfigurationProperties} 用 setter 绑定，
     * 构造器注入会抢在建实例之前求值；这里只在 {@link #validate()} 里读一次，
     * 字段注入的时序（容器先于本 bean 初始化）足够。</p>
     */
    @Autowired
    private MultipartProperties multipart;

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
        this.normalizedExtensions = normalize(allowedExtensions);
    }

    public long getDriveMaxBytes() {
        return driveMaxBytes;
    }

    public void setDriveMaxBytes(long driveMaxBytes) {
        this.driveMaxBytes = driveMaxBytes;
    }

    public long getAttachmentMaxBytes() {
        return attachmentMaxBytes;
    }

    public void setAttachmentMaxBytes(long attachmentMaxBytes) {
        this.attachmentMaxBytes = attachmentMaxBytes;
    }

    public long getKnowledgeDocumentMaxBytes() {
        return knowledgeDocumentMaxBytes;
    }

    public void setKnowledgeDocumentMaxBytes(long knowledgeDocumentMaxBytes) {
        this.knowledgeDocumentMaxBytes = knowledgeDocumentMaxBytes;
    }

    /** 归一化后的扩展名集合（供前端 {@code accept} 派生，顺序与配置一致）。 */
    public List<String> normalizedExtensions() {
        return List.copyOf(normalizedExtensions);
    }

    /**
     * 扩展名是否放行；无扩展名或取不到扩展名时拒绝。
     *
     * <p>取最后一个 {@code .} 之后的部分——目录上传时文件名可能带路径成分
     * （{@code a/b/c.md}），按 basename 判定才不会误伤。</p>
     *
     * @param filename 原始文件名，可为 {@code null}
     * @return 在白名单内为 {@code true}
     */
    public boolean isAllowed(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return normalizedExtensions.contains(ext);
    }

    /**
     * 启动即校验配置，任一项不合法直接让应用启动失败。
     *
     * <p>两项校验各自的失败模式都是「静默失效」，因此一律 fail-fast：</p>
     * <ol>
     *   <li><b>白名单为空</b>：所有上传全被拒，用户看到的是每类上传都报
     *       「不支持的文件类型」，而根因在配置里；</li>
     *   <li><b>上限超过容器 multipart 限制</b>：超限在进 Controller 前就被容器抛
     *       {@code MaxUploadSizeExceededException}，本层的检查永远走不到，
     *       前端拿到的是通用错误而非可读提示——历史上就发生过一次
     *       （上限写 100MB、multipart 25MB）。</li>
     * </ol>
     */
    @PostConstruct
    public void validate() {
        if (normalizedExtensions.isEmpty()) {
            throw new IllegalStateException(
                    "上传白名单为空（agent-platform.upload-policy.allowed-extensions）。"
                    + "空列表会让所有上传被拒；确需全禁请显式配一个必然不存在的扩展名。");
        }
        long containerLimit = multipart.getMaxFileSize().toBytes();
        requireWithinContainer("drive-max-bytes", driveMaxBytes, containerLimit);
        requireWithinContainer("attachment-max-bytes", attachmentMaxBytes, containerLimit);
        requireWithinContainer("knowledge-document-max-bytes", knowledgeDocumentMaxBytes, containerLimit);
        log.info("上传闸门已装载：扩展名 {} 项，drive {}MB / 附件 {}MB / 知识库文档 {}MB"
                        + "（容器 multipart 上限 {}MB）",
                normalizedExtensions.size(),
                driveMaxBytes / BYTES_PER_MB,
                attachmentMaxBytes / BYTES_PER_MB,
                knowledgeDocumentMaxBytes / BYTES_PER_MB,
                containerLimit / BYTES_PER_MB);
    }

    /** 单个上限越界的报错；用配置键而非字段名，便于直接照着改。 */
    private void requireWithinContainer(String key, long configured, long containerLimit) {
        if (configured > containerLimit) {
            throw new IllegalStateException(
                    "agent-platform.upload-policy." + key + " = " + (configured / BYTES_PER_MB)
                    + "MB，超过 spring.servlet.multipart.max-file-size（"
                    + (containerLimit / BYTES_PER_MB) + "MB）。"
                    + "超限会在进入 Controller 前被容器拒绝，业务层的可读提示永远不会生效。");
        }
    }

    /**
     * 归一化：去空白、去前导点、转小写、去重且保持配置顺序。
     *
     * <p>去前导点是刻意的——运维写 {@code .md} 与 {@code md} 都该对，
     * 而带点的项参与后缀比对永远不匹配，属于纯静默失效。</p>
     */
    private static Set<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String v = item.trim();
            while (v.startsWith(".")) {
                v = v.substring(1);
            }
            v = v.toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}
