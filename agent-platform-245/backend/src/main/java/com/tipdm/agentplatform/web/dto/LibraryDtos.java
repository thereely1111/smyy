package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.domain.LibrarySpaceCategory;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupSummary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 资料库接口契约。
 *
 * <p>沿用知识库的自定义包装风格（{@code {items:[], total}}）而非 PageResponse，
 * 与 {@code KnowledgeBaseDtos} 保持一致。</p>
 */
public final class LibraryDtos {

    /** 对话引用的协议前缀；与前端 {@code LIBRARY_REF_TOKEN} 必须一致。 */
    public static final String REF_SCHEME = "library://node/";

    private LibraryDtos() {
    }

    // ── 空间 ──────────────────────────────────────────────────────────

    public record CreateSpaceRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull LibrarySpaceCategory category,
            @Size(max = 2000) String description,
            /** 仅团队空间有意义：给未授权成员的默认权限；null 表示仅成员可见。 */
            LibraryPermission defaultPermission) {
    }

    public record SpaceView(
            UUID id,
            String name,
            LibrarySpaceCategory category,
            UUID ownerUserId,
            LibraryPermission defaultPermission,
            String description,
            /** 当前用户对该空间的权限；null 表示不可见（不会出现在列表里）。 */
            LibraryPermission permission,
            /** 是否有写权限（能新建/上传/改节点）。 */
            boolean editable,
            /** 是否为空间所有者：决定成员管理与删除空间入口的显隐。 */
            boolean owner,
            long nodeCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ListSpacesResponse(List<SpaceView> spaces) {
    }

    // ── 节点 ──────────────────────────────────────────────────────────

    public record CreateNodeRequest(
            @NotNull LibraryNodeKind kind,
            @NotBlank @Size(max = 255) String title,
            /** 为空表示建在空间根下。 */
            UUID parentId,
            /** kind=knowledge 时必填：指向已挂载的知识库。 */
            UUID payloadKbId) {
    }

    /**
     * 移动节点。
     *
     * @param parentId   目标父节点；null 表示移到空间根
     * @param afterNodeId 同级排序锚点：插在该节点之后；null 表示插到最前
     */
    public record MoveNodeRequest(
            UUID parentId,
            UUID afterNodeId) {
    }

    public record RenameNodeRequest(
            @NotBlank @Size(max = 255) String title) {
    }

    public record UpdateNodeRequest(
            @Size(max = 2000) String excerpt) {
    }

    public record NodeView(
            UUID id,
            UUID spaceId,
            UUID parentId,
            LibraryNodeKind kind,
            String title,
            int sortOrder,
            UUID payloadKbId,
            String excerpt,
            boolean hasChildren,
            LibraryPermission permission,
            boolean editable,
            Instant updatedAt) {
    }

    /** 列子节点响应；{@code total} 为该层子节点总数。 */
    public record ListNodesResponse(List<NodeView> nodes, long total) {
    }

    /**
     * 资料库文件被对话引用时暴露给agent 的引用串。
     * 前端直接把它作为消息文本的一部分发送，模型据此调用 library_read 回读。
     */
    public record NodeRefView(
            UUID nodeId,
            String ref,
            String title,
            LibraryNodeKind kind) {

        /** 按协议拼引用串；与前端 LIBRARY_REF_TOKEN 格式一致。 */
        public static String refOf(UUID nodeId) {
            return REF_SCHEME + nodeId;
        }
    }

    // ── 正文 ──────────────────────────────────────────────────────────

    public record WriteDocRequest(
            @Size(max = 2_000_000) String markdown) {
    }

    /**
     * 节点正文（按 kind 分派后的结果）。
     *
     * @param text     文本类正文；drive 为空串
     * @param truncated 是否因超长被截断
     * @param fileName drive 文件名；其他 kind 为 null
     * @param fileSize drive 字节数；其他 kind 为 -1
     * @param hint     调用提示（如"请改用 knowledge_search"）
     */
    public record NodeContentView(
            UUID nodeId,
            LibraryNodeKind kind,
            String text,
            boolean truncated,
            String fileName,
            long fileSize,
            String hint) {
    }

    public record UploadDriveFileResponse(
            UUID nodeId,
            String title,
            String fileName,
            long fileSize,
            String ref) {
    }

    // ── 空间成员（团队空间授权）────────────────────────────────────────

    /**
     * 空间成员。
     *
     * @param principalType USER=单个成员；GROUP=群组（组内全员获得该权限）
     * @param displayName   用户名或群组名；主体已被删除时为 null（行仍在，展示为「已删除用户」）
     */
    public record MemberView(
            UUID grantId,
            GrantPrincipalType principalType,
            UUID principalId,
            String displayName,
            LibraryPermission permission,
            UUID grantedBy,
            Instant createdAt) {
    }

    /**
     * 授予 / 调整成员权限。
     *
     * <p>同一主体重复提交时按 {@code permission} <b>覆盖</b>而非取最大：
     * 「降级」是owner 的正常操作（把人从可写降为只读），取最大值会让降级永远不生效，
     * 表现为「明明改回去了他还是能上传」。</p>
     */
    public record GrantMemberRequest(
            @NotNull GrantPrincipalType principalType,
            @NotNull UUID principalId,
            @NotNull LibraryPermission permission) {
    }

    /** 成员列表响应；同时带上可授予的群组，避免前端为选群组多发一次请求。 */
    public record ListMembersResponse(
            List<MemberView> members,
            List<GroupSummary> grantableGroups) {
    }

}



