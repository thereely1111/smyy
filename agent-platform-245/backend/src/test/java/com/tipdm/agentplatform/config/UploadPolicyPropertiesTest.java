package com.tipdm.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传闸门配置的绑定与校验回归测试。
 *
 * <p>守两件容易静默失效的事：</p>
 * <ol>
 *   <li>{@code application.yml} 里那份逗号串真的能绑成 {@code List<String>}——
 *       绑不上时字段退回内置默认值，配置看起来「写了」却毫无作用；</li>
 *   <li>两条 fail-fast 仍然拦得住：白名单为空、上限超过容器 multipart 限制。</li>
 * </ol>
 */
class UploadPolicyPropertiesTest {

    @Test
    void allowedExtensions_bindsFromApplicationYml() throws IOException {
        UploadPolicyProperties props = bindFromApplicationYml();

        List<String> exts = props.normalizedExtensions();
        // 冒烟：几条典型的（配置项写错成空串/未绑定时这几条会消失）
        assertTrue(exts.contains("md"), "md 应在白名单内，实际=" + exts);
        assertTrue(exts.contains("xlsx"), "xlsx 应在白名单内，实际=" + exts);
        assertTrue(exts.contains("png"), "png 应在白名单内，实际=" + exts);
        // 配置默认值大小与条目数相符（漏项会在新增类型时静默改变行为）
        assertEquals(30, exts.size(), "白名单条目数应与配置一致，实际=" + exts);
    }

    @Test
    void defaults_satisfyContainerLimit() throws IOException {
        UploadPolicyProperties props = withContainerLimit(bindFromApplicationYml());

        // 默认值必须能通过启动校验：容器 multipart 是 25MB，drive 也是 25MB（相等放行）
        assertDoesNotThrow(props::validate);
    }

    @Test
    void normalize_stripsDotsAndLowercases() {
        UploadPolicyProperties props = new UploadPolicyProperties();
        props.setAllowedExtensions(List.of(" .MD ", "TXT", "json", "..XLSX", ""));

        assertEquals(List.of("md", "txt", "json", "xlsx"), props.normalizedExtensions());
        // 归一后判定才成立：带点的项参与后缀比对永远不匹配，属纯静默失效
        assertTrue(props.isAllowed("报告.MD"));
        assertTrue(props.isAllowed("a/b/表.XLSX"), "目录上传带路径成分时按 basename 判定");
        assertFalse(props.isAllowed("脚本.jmx"), "未列入白名单的应拒绝");
        assertFalse(props.isAllowed("无扩展名"));
    }

    @Test
    void validate_rejectsEmptyWhitelist() {
        UploadPolicyProperties props = withContainerLimit(new UploadPolicyProperties());
        props.setAllowedExtensions(List.of());

        // 闸门全开等于没有闸门，而「配置漏写」比「闸门失效」更难被发现
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("allowed-extensions"), "报错须点名配置键：" + e.getMessage());
    }

    @Test
    void validate_rejectsLimitAboveContainerMultipart() {
        UploadPolicyProperties props = withContainerLimit(new UploadPolicyProperties());
        props.setDriveMaxBytes(DataSize.ofMegabytes(100).toBytes());

        // 超限会在进 Controller 前被容器拒，本层检查永不生效 ⇒ 启动期就该拦下
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("drive-max-bytes"), "报错须点名配置键：" + e.getMessage());
        assertTrue(e.getMessage().contains("max-file-size"), "报错须说明与 multipart 的关系：" + e.getMessage());
    }

    /**
     * 从 application.yml 绑出配置。
     *
     * <p>刻意用裸 {@link Binder}：它与 Spring 启动时的绑定器行为一致，但**不解析
     * {@code ${VAR:默认值}} 占位符**——这正是本测试要守的隐患：配置里写了占位符，
     * 裸绑定读到字面量，「配了等于没配」且启动日志里看不出来。</p>
     */
    private static UploadPolicyProperties bindFromApplicationYml() throws IOException {
        Resource resource = new DefaultResourceLoader().getResource("classpath:application.yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", resource);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder
                .bind("agent-platform.upload-policy", Bindable.of(UploadPolicyProperties.class))
                .orElseGet(UploadPolicyProperties::new);
    }

    /** 注入容器上限（生产由 Spring 字段注入完成，测试里手动补上）。 */
    private static UploadPolicyProperties withContainerLimit(UploadPolicyProperties props) {
        try {
            var field = UploadPolicyProperties.class.getDeclaredField("multipart");
            field.setAccessible(true);
            field.set(props, containerLimit(DataSize.ofMegabytes(25)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 multipart 上限失败", e);
        }
        return props;
    }

    /** 构造带容器上限的 MultipartProperties。 */
    private static MultipartProperties containerLimit(DataSize maxFileSize) {
        MultipartProperties props = new MultipartProperties();
        props.setMaxFileSize(maxFileSize);
        return props;
    }
}