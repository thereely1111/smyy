package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.LibraryNode;
import com.tipdm.agentplatform.domain.LibraryNodeKind;
import com.tipdm.agentplatform.domain.LibraryPermission;
import com.tipdm.agentplatform.repository.KnowledgeBaseRepository;
import com.tipdm.agentplatform.repository.LibraryNodeRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.LibraryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目录（folder）引用的正文读取。
 *
 * <p><b>为什么这些不变量必须有测试</b>：目录是唯一「内容长度与子节点数成正比」的 kind，
 * 且父目录可见<b>不代表</b>子节点可见（ACL 只沿祖先链向上冒泡，不向下）。
 * 一旦目录清单漏了逐个判权，就会用父节点的权限泄漏未授权子节点的标题——
 * 而这类越权在任何单测缺席的环境（本仓零集成基座）里都会静默上线。</p>
 */
class LibraryContentServiceFolderTest {

    private LibraryAccessService accessService;
    private LibraryNodeRepository nodeRepository;
    private LibraryContentService service;

    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Viewer VIEWER = Viewer.runtime(USER);

    @BeforeEach
    void setUp() {
        accessService = mock(LibraryAccessService.class);
        nodeRepository = mock(LibraryNodeRepository.class);
        service = new LibraryContentService(
                mock(LibraryBlobStore.class),
                accessService,
                mock(KnowledgeBaseRepository.class),
                nodeRepository);
    }

    private static LibraryNode node(LibraryNodeKind kind, String title) {
        LibraryNode n = new LibraryNode();
        n.setId(UUID.randomUUID());
        n.setSpaceId(SPACE);
        n.setKind(kind);
        n.setTitle(title);
        return n;
    }

    /** 让 mock 的 requireVisibleNode / 子节点查询返回给定值。 */
    private void givenFolder(LibraryNode folder, List<LibraryNode> children) {
        when(accessService.requireVisibleNode(eq(folder.getId()), any())).thenReturn(folder);
        when(nodeRepository
                .findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(SPACE, folder.getId()))
                .thenReturn(children);
    }

    @Test
    void folderReturnsChildListingWithRefsInsteadOfFailing() {
        LibraryNode folder = node(LibraryNodeKind.folder, "教材");
        LibraryNode doc = node(LibraryNodeKind.doc, "第一章.md");
        givenFolder(folder, List.of(doc));
        // resolveNodePermission 返回 null 表示**无权限**（见 LibraryAccessService），
        // 所以「可见」必须 mock 成非 null 的权限值，不是 null。
        when(accessService.resolveNodePermission(eq(doc), any()))
                .thenReturn(LibraryPermission.READ);

        LibraryContentService.Content c = service.read(folder.getId(), VIEWER);

        // 目录不再是废引用：返回清单而不是 501/异常
        assertTrue(c.text().contains("第一章.md"), "清单应含子节点标题，实际=" + c.text());
        assertTrue(c.text().contains("library://node/" + doc.getId()),
                "清单必须给出可继续读取的引用串，实际=" + c.text());
    }

    @Test
    void folderListingHidesChildrenTheUserCannotSee() {
        LibraryNode folder = node(LibraryNodeKind.folder, "共享目录");
        LibraryNode allowed = node(LibraryNodeKind.doc, "permitted.md");
        LibraryNode denied = node(LibraryNodeKind.doc, "secret.md");
        givenFolder(folder, List.of(allowed, denied));
        // allowed 可见、denied 不可见（null）；父目录本身可见
        when(accessService.resolveNodePermission(eq(allowed), any()))
                .thenReturn(LibraryPermission.READ);
        when(accessService.resolveNodePermission(eq(denied), any())).thenReturn(null);
        when(accessService.resolveNodePermission(eq(folder), any()))
                .thenReturn(LibraryPermission.READ);

        LibraryContentService.Content c = service.read(folder.getId(), VIEWER);

        assertTrue(c.text().contains("permitted.md"), "有权子节点应列出，实际=" + c.text());
        assertFalse(c.text().contains("secret.md"),
                "无权限子节点不得出现在清单里——父可见不代表子可见，实际=" + c.text());
    }

    @Test
    void folderListingTruncatesBeyondCapAndSaysSo() {
        LibraryNode folder = node(LibraryNodeKind.folder, "大目录");
        List<LibraryNode> children = new ArrayList<>();
        for (int i = 0; i < LibraryContentService.MAX_FOLDER_CHILDREN + 30; i++) {
            children.add(node(LibraryNodeKind.doc, "文件" + i + ".md"));
        }
        givenFolder(folder, children);
        for (LibraryNode child : children) {
            when(accessService.resolveNodePermission(eq(child), any()))
                    .thenReturn(LibraryPermission.READ);
        }

        LibraryContentService.Content c = service.read(folder.getId(), VIEWER);

        assertTrue(c.text().contains("另有 30 个条目未列出"),
                "截断必须显式告知模型，否则它会以为看到了全部，实际=" + c.text());
    }

    @Test
    void emptyFolderSaysEmptyRatherThanReturningNothing() {
        LibraryNode folder = node(LibraryNodeKind.folder, "空目录");
        givenFolder(folder, List.of());

        LibraryContentService.Content c = service.read(folder.getId(), VIEWER);

        assertTrue(c.text().contains("该目录为空"), "空目录应有明确说明，实际=" + c.text());
    }

    @Test
    void folderListingTellsModelNotToEchoRefsBackToUser() {
        LibraryNode folder = node(LibraryNodeKind.folder, "教材");
        LibraryNode doc = node(LibraryNodeKind.doc, "第一章.md");
        givenFolder(folder, List.of(doc));
        when(accessService.resolveNodePermission(eq(doc), any()))
                .thenReturn(LibraryPermission.READ);

        LibraryContentService.Content c = service.read(folder.getId(), VIEWER);

        // 用户实测反馈：模型把整份清单连同 nodeId 原样复述出来，用户看到一屏协议串。
        // 尖括号包裹 + 显式「不要复述」指引是唯一可靠的抑制手段（模型默认行为就是复述）。
        assertTrue(c.text().contains("<" + LibraryDtos.REF_SCHEME + doc.getId() + ">"),
                "引用串应被尖括号包裹，与正文区分，实际=" + c.text());
        assertTrue(c.text().contains("不要把它们复述给用户"),
                "清单必须显式禁止复述引用串，实际=" + c.text());
    }
}
