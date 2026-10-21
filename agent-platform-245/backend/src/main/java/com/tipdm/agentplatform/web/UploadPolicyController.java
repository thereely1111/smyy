package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.config.UploadPolicyProperties;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 上传闸门的对外只读视图：让前端的文件选择器 {@code accept} 与上传前预检
 * 从后端配置派生，而不是在前端再写一份常量。
 *
 * <p>此前前端有两份硬编码清单（资料库 {@code DRIVE_ACCEPT} 与会话附件
 * {@code ACCEPTED_EXTENSIONS}），靠注释与后端保持同步。漏改一处就出现
 * 「同一个文件在对话里能传、在资料库传不了」，且没有任何东西会报错。</p>
 *
 * <p><b>这是提示不是闸门</b>：{@code accept} 只影响选择器的默认过滤，
 * 拖拽与目录上传都能绕过；真正的判定在各自 Controller 调
 * {@link UploadPolicyProperties#isAllowed(String)}。因此前端在本接口
 * 不可用时必须放行而不是拦死——否则配置接口一挂，全站上传都不可用，
 * 故障面比「多显示几个本该被后端拒的类型」大得多。</p>
 *
 * <p>挂在 {@code /api/config/**}：与工作台其余接口同在 {@code JwtAuthFilter}
 * 的强制登录范围内（前端只在登录后才需要它），无需登记白名单。</p>
 */
@RestController
@RequestMapping("/api/config")
public class UploadPolicyController {

    private final UploadPolicyProperties uploadPolicy;

    public UploadPolicyController(UploadPolicyProperties uploadPolicy) {
        this.uploadPolicy = uploadPolicy;
    }

    /** 上传闸门当前生效的配置（前端 {@code accept} 与上限预检的唯一来源）。 */
    @GetMapping("/upload-policy")
    public UploadPolicyView uploadPolicy() {
        return new UploadPolicyView(
                uploadPolicy.normalizedExtensions(),
                uploadPolicy.getDriveMaxBytes(),
                uploadPolicy.getAttachmentMaxBytes(),
                uploadPolicy.getKnowledgeDocumentMaxBytes());
    }

    /**
     * @param allowedExtensions 归一化后的扩展名（小写、不带点）
     * @param driveMaxBytes 资料库 drive 单文件上限（字节）
     * @param attachmentMaxBytes 会话附件单文件上限（字节）
     * @param knowledgeDocumentMaxBytes 知识库文档单文件上限（字节）
     */
    public record UploadPolicyView(
            List<String> allowedExtensions,
            long driveMaxBytes,
            long attachmentMaxBytes,
            long knowledgeDocumentMaxBytes) {
    }
}
