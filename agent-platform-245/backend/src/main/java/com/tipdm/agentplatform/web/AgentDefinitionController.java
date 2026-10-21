package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.service.AgentConfigService;
import com.tipdm.agentplatform.service.AgentDefinitionContentService;
import com.tipdm.agentplatform.service.AgentDefinitionContentService.RemovalResult;
import com.tipdm.agentplatform.service.AgentDefinitionContentService.SkillUploadResult;
import com.tipdm.agentplatform.service.AgentDefinitionImportService;
import com.tipdm.agentplatform.service.SkillService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.AgentDtos;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能体智能体目录导入与单体重载。
 *
 * <p>与 {@link AgentManagementController} 分开的原因：这两组端点都围绕<b>定义目录</b>
 * 而非平台配置——导入产出的是 {@code workspace/agents/{名称}/}，重载刷新的是装配缓存，
 * 都不落在 {@code agent_configs} 的字段上。
 *
 * <p>导入与在线装配、上传文件夹三条入口<b>产出同构</b>：落盘后即进入同样的两条管线
 * （配置更新 / 定义内容修改），入口不参与后续分流。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionController.class);

    /** 多文件导入的相对路径参数名（前端用相对路径表达目录结构）。 */
    private static final String RELATIVE_PATH_PARAM = "relativePaths";

    /** 多文件导入的内容参数名；同一顺序下与 relativePaths 一一对应。 */
    private static final String FILES_PARAM = "files";

    private final AgentDefinitionImportService importService;
    private final AgentConfigService agentConfigService;
    private final AgentDefinitionContentService contentService;
    private final SkillService skillService;
    private final SessionAccessGuard accessGuard;

    public AgentDefinitionController(AgentDefinitionImportService importService,
                                     AgentConfigService agentConfigService,
                                     AgentDefinitionContentService contentService,
                                     SkillService skillService,
                                     SessionAccessGuard accessGuard) {
        this.importService = importService;
        this.agentConfigService = agentConfigService;
        this.contentService = contentService;
        this.skillService = skillService;
        this.accessGuard = accessGuard;
    }

    /**
     * 从 zip 包导入智能体智能体目录。
     *
     * <p>包内根目录必须存在 {@code AGENTS.md}；多包一层目录时该层内必须有它。
     *
     * @param file zip 文件
     * @param name 智能体名称（可含中文；为空时取包内目录名）
     * @return 落盘位置与派生 id
     */
    @PostMapping(value = "/import/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("agent:create")
    public AgentDtos.AgentImportResponse importFromZip(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name) throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择 zip 文件");
        }
        AgentDefinitionImportService.ImportResult r =
                importService.importFromZip(file.getInputStream(), name);
        return AgentDtos.AgentImportResponse.from(r);
    }

    /**
     * 从多文件（浏览器文件夹上传）导入智能体智能体目录。
     *
     * <p>相对路径由前端 {@code webkitRelativePath} 提供，顶层目录被前端剥掉后逐项上传。
     *
     * @param files          文件内容
     * @param relativePaths  与 files 一一对应的相对路径
     * @param name           智能体名称
     * @return 落盘位置与派生 id
     */
    @PostMapping(value = "/import/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("agent:create")
    public AgentDtos.AgentImportResponse importFromFiles(
            @RequestParam(FILES_PARAM) List<MultipartFile> files,
            @RequestParam(RELATIVE_PATH_PARAM) List<String> relativePaths,
            @RequestParam(value = "name", required = false) String name) throws IOException {
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("未收到任何文件");
        }
        if (relativePaths == null || relativePaths.size() != files.size()) {
            throw ApiException.badRequest("relativePaths 与 files 数量必须一致");
        }
        List<AgentDefinitionImportService.PathContent> entries = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            entries.add(new AgentDefinitionImportService.PathContent(
                    relativePaths.get(i), files.get(i).getBytes()));
        }
        AgentDefinitionImportService.ImportResult r = importService.importFromFiles(entries, name);
        return AgentDtos.AgentImportResponse.from(r);
    }

    /**
     * 重载单个智能体的装配缓存。
     *
     * <p>供「定义内容修改」管线在发布后调用：定义目录变了，但 {@code HarnessAgent} 实例
     * 仍驻留缓存，不重载则改动不生效。已有 {@code POST /reload-files} 只覆盖目录型
     * （绑在目录全量扫描上），此端点对<b>任意</b> agent 生效。
     *
     * @param id 智能体 id
     * @return 是否命中（false = 该 id 无缓存或不存在）
     */
    @PostMapping("/{id}/reload")
    @RequirePermission("agent:update")
    public AgentDtos.ReloadResponse reloadOne(@PathVariable UUID id) {
        return agentConfigService.reloadOne(id);
    }

    // ==================== 定义内容的增删 ====================

    /**
     * 上传一个私有技能目录到该智能体的 {@code skills/} 下。
     *
     * <p>技能必然位于本智能体目录内，不进平台技能库、不与其他智能体共享。
     * 包内可多包一层目录，剥开后必须含 {@code SKILL.md}；技能名以目录名为准。
     * 同名已存在时拒绝（不覆盖）。
     */
    @PostMapping(value = "/{id}/skills", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("agent:update")
    public SkillResponse uploadSkill(@PathVariable UUID id,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        SkillUploadResult result = contentService.uploadSkillFromZip(id, file.getInputStream());
        return new SkillResponse(result.skillName(), result.workspacePath(), result.fileCount());
    }

    /**
     * 列出某个技能目录下的文件（用于文件树展开一层）。
     *
     * <p>真实结构不止 SKILL.md：多数技能还有 {@code scripts/}（.py 脚本）与
     * {@code references/}（可能是子目录）。前端展开时逐层请求，避免一次返回整棵大树。
     *
     * <p>路径用 {@code ?path=} 而非 {@code {name}}：子目录形如 {@code jc-0/scripts} 含斜杠，
     * 放进 path segment 会被 Tomcat 拦在匹配阶段（404）。斜杠属于路径语义，必须走 query。
     *
     * <p>权限码沿用 {@code agent:menu}，与技能列表同一口径（本仓无法自助新增权限码）。
     */
    /**
     * 读取技能目录下的文本文件（供编辑器打开）。
     *
     * <p>路径用 {@code ?path=}：子目录形如 {@code jc-0/scripts} 含斜杠，
     * 放进 path segment 会被 Tomcat 拦在匹配阶段（404）。
     */
    @GetMapping("/{id}/skills/file")
    @RequirePermission("agent:menu")
    public SkillFileContentResponse readSkillFile(@PathVariable UUID id,
                                                @RequestParam("path") String path) throws IOException {
        var r = contentService.readSkillFile(id, path);
        return new SkillFileContentResponse(r.path(), r.content(), r.sizeBytes());
    }

    /** 覆写（不存在则创建）技能目录下的文本文件。 */
    @PutMapping("/{id}/skills/file")
    @RequirePermission("agent:update")
    public SkillFileSavedResponse writeSkillFile(@PathVariable UUID id,
                                                @RequestParam("path") String path,
                                                @RequestBody SkillFileWrite body) throws IOException {
        var r = contentService.writeSkillFile(id, path, body.content());
        return new SkillFileSavedResponse(r.path(), r.sizeBytes());
    }

    /**
     * 新建文件或目录。
     *
     * @param directory true 建目录；目录路径带结尾 {@code /}，与文件树一致
     */
    @PostMapping("/{id}/skills/entry")
    @RequirePermission("agent:update")
    public SkillFileSavedResponse createSkillEntry(@PathVariable UUID id,
                                                  @RequestParam("path") String path,
                                                  @RequestParam(defaultValue = "false") boolean directory)
            throws IOException {
        var r = contentService.createSkillEntry(id, path, directory);
        return new SkillFileSavedResponse(r.path(), r.sizeBytes());
    }

    /** 删除技能目录下的文件或子目录（技能根本身受保护，删它要走「移除技能」）。 */
    @DeleteMapping("/{id}/skills/file")
    @RequirePermission("agent:update")
    public RemovalResponse deleteSkillFile(@PathVariable UUID id,
                                          @RequestParam("path") String path) throws IOException {
        var r = contentService.deleteSkillEntry(id, path);
        return new RemovalResponse(r.name(), r.workspacePath());
    }

    /** 重命名（移动）技能目录下的条目。 */
    @PostMapping("/{id}/skills/entry/rename")
    @RequirePermission("agent:update")
    public SkillFileSavedResponse renameSkillEntry(@PathVariable UUID id,
                                                  @RequestParam("from") String from,
                                                  @RequestParam("to") String to) throws IOException {
        var r = contentService.renameSkillEntry(id, from, to);
        return new SkillFileSavedResponse(r.path(), r.sizeBytes());
    }

    @GetMapping("/{id}/skills/files")
    @RequirePermission("agent:menu")
    public SkillFilesResponse listSkillFiles(@PathVariable UUID id,
                                           @RequestParam("path") String path) throws IOException {
        return new SkillFilesResponse(contentService.listSkillFiles(id, path));
    }

    // ==================== 定义目录根级附加文件 ====================
    //
    // AGENTS.md、subagents/、skills/ 之外的文件与目录（导入包带的参考资料、用户
    // 手工放的笔记等）。与技能文件同一条「落盘直写」路线：不进草稿模型，改完即存。
    // 受管条目在服务的路径解析层整体拒绝，不会从这里漏出去。

    /** 列出定义目录根级（path 为空）或某个附加目录下的一层条目。 */
    @GetMapping("/{id}/files")
    @RequirePermission("agent:menu")
    public SkillFilesResponse listRootFiles(@PathVariable UUID id,
                                            @RequestParam(value = "path", required = false) String path)
            throws IOException {
        return new SkillFilesResponse(contentService.listRootEntries(id, path));
    }

    /** 读取定义目录下的附加文本文件。 */
    @GetMapping("/{id}/files/file")
    @RequirePermission("agent:menu")
    public SkillFileContentResponse readRootFile(@PathVariable UUID id,
                                                 @RequestParam("path") String path) throws IOException {
        var r = contentService.readRootFile(id, path);
        return new SkillFileContentResponse(r.path(), r.content(), r.sizeBytes());
    }

    /** 覆写（不存在则创建）定义目录下的附加文本文件。 */
    @PutMapping("/{id}/files/file")
    @RequirePermission("agent:update")
    public SkillFileSavedResponse writeRootFile(@PathVariable UUID id,
                                                @RequestParam("path") String path,
                                                @RequestBody SkillFileWrite body) throws IOException {
        var r = contentService.writeRootFile(id, path, body.content());
        return new SkillFileSavedResponse(r.path(), r.sizeBytes());
    }

    /** 在定义目录下新建附加文件或目录（已存在时返 409，不覆盖）。 */
    @PostMapping("/{id}/files/entry")
    @RequirePermission("agent:update")
    public SkillFileSavedResponse createRootEntry(@PathVariable UUID id,
                                                  @RequestParam("path") String path,
                                                  @RequestParam(defaultValue = "false") boolean directory)
            throws IOException {
        var r = contentService.createRootEntry(id, path, directory);
        return new SkillFileSavedResponse(r.path(), r.sizeBytes());
    }

    /** 删除定义目录下的附加文件或子目录（受管条目不可删）。 */
    @DeleteMapping("/{id}/files/file")
    @RequirePermission("agent:update")
    public RemovalResponse deleteRootFile(@PathVariable UUID id,
                                          @RequestParam("path") String path) throws IOException {
        var r = contentService.deleteRootEntry(id, path);
        return new RemovalResponse(r.name(), r.workspacePath());
    }

    /** 重命名（移动）定义目录下的附加条目。 */
    @PostMapping("/{id}/files/entry/rename")
    @RequirePermission("agent:update")
    public SkillFileSavedResponse renameRootEntry(@PathVariable UUID id,
                                                  @RequestParam("from") String from,
                                                  @RequestParam("to") String to) throws IOException {
        var r = contentService.renameRootEntry(id, from, to);
        return new SkillFileSavedResponse(r.path(), r.sizeBytes());
    }

    /**
     * 移除一个私有技能目录（含其下 {@code scripts/} 等全部文件）。
     *
     * <p>被 {@code subagents/*.md} 引用时返回 409，并在 message 里带出引用者。
     */
    @DeleteMapping("/{id}/skills/{name}")
    @RequirePermission("agent:update")
    public RemovalResponse removeSkill(@PathVariable UUID id,
                                       @PathVariable String name) throws IOException {
        RemovalResult result = contentService.removeSkill(id, name);
        return new RemovalResponse(result.name(), result.workspacePath());
    }

    /**
     * 列出该智能体 {@code skills/} 下的技能目录名。
     *
     * <p>权限码用 {@code agent:menu}：本仓无法自助新增权限码，权威清单在外部统一认证
     * 服务。{@code agent:read} 并不存在，写上去会让端点对所有账号恒返 403。
     */
    @GetMapping("/{id}/skills")
    @RequirePermission("agent:menu")
    public SkillListResponse listSkills(@PathVariable UUID id) throws IOException {
        return new SkillListResponse(contentService.listSkillNames(id));
    }

    /** 启用一个平台工具（真相源是 {@code agent_configs.tool_ids}）。 */
    @PostMapping("/{id}/tools/{name}")
    @RequirePermission("agent:update")
    public ToolListResponse enableTool(@PathVariable UUID id, @PathVariable String name) {
        return new ToolListResponse(contentService.enableTool(id, name));
    }

    /**
     * 停用一个工具。被 {@code subagents/*.md} 引用时返回 409 并带出引用者——
     * 那些子智能体会在运行时因缺少该工具而失败。
     */
    @DeleteMapping("/{id}/tools/{name}")
    @RequirePermission("agent:update")
    public ToolListResponse disableTool(@PathVariable UUID id, @PathVariable String name)
            throws IOException {
        return new ToolListResponse(contentService.disableTool(id, name));
    }

    /**
     * 从平台/个人技能库复制一个技能到该智能体的 {@code skills/} 下。
     *
     * <p>走 {@link AgentDefinitionContentService#copySkillFromLibrary}，
     * 同名已存在时拒绝。技能可见性按平台库/个人库分别校验。
     */
    @PostMapping("/{id}/skills/copy-from-library")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("agent:update")
    public SkillResponse copySkillFromLibrary(@PathVariable UUID id,
                                              @RequestParam("skillId") UUID skillId,
                                              HttpServletRequest request) throws IOException {
        Viewer viewer = accessGuard.viewer(request);
        Skill skill = skillService.requireVisible(skillId, viewer);
        SkillUploadResult result = contentService.copySkillFromLibrary(id, skill);
        return new SkillResponse(result.skillName(), result.workspacePath(), result.fileCount());
    }

    /** 上传技能结果。 */
    public record SkillResponse(String name, String workspacePath, int fileCount) {
    }

    /** 技能列表。 */
    public record SkillListResponse(List<String> names) {
    }

    /** 读文件请求体。 */
    public record SkillFileWrite(String content) {
    }

    /** 读文件响应。 */
    public record SkillFileContentResponse(String path, String content, long sizeBytes) {
    }

    /** 写入/新建/重命名响应。 */
    public record SkillFileSavedResponse(String path, long sizeBytes) {
    }

    /** 某技能目录下的条目列表。 */
    public record SkillFilesResponse(List<AgentDefinitionContentService.SkillFileNode> files) {
    }

    /** 移除结果。 */
    public record RemovalResponse(String name, String workspacePath) {
    }

    /** 启用工具名列表。 */
    public record ToolListResponse(List<String> enabledTools) {
    }
}
