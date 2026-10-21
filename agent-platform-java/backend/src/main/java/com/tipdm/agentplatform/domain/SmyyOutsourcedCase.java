package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 数模演易外包案例。
 *
 * <p>该表只承载当前用于对外展示的人工/外包完整案例，与智能体运行得到的会话产物隔离。</p>
 */
@Entity
@Table(name = "smyy_outsourced_cases")
public class SmyyOutsourcedCase extends BaseEntity {

    @Column(name = "proto_id", nullable = false, unique = true, length = 32)
    private String protoId;

    @Column(nullable = false, unique = true, length = 128)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 80)
    private String tag;

    @Column(name = "category", length = 80)
    private String category;

    @Column(nullable = false, length = 40)
    private String source = "OUTSOURCED";

    @Column(nullable = false, length = 20)
    private String status = "PUBLISHED";

    /*
     * 以下内容列是「正文内联」时代的遗留字段：
     * 新模型下正文已落到 public/cases/<protoId>/<板块>/，库表只存 *_file_name / *_files_json 里的相对路径，
     * 由 SmyyCaseContentResolver 读文件回填。这些列保留只为给尚未迁移的案例兜底（路径为空时回落到内联值），
     * 全量迁移完成后可整体删除（参见 docs/sql/20261020_smyy_case_content_paths.sql）。
     */
    @Column(name = "guide_html", columnDefinition = "TEXT")
    private String guideHtml;

    @Column(name = "animation_html", columnDefinition = "TEXT")
    private String animationHtml;

    @Column(name = "animation_segments_json", columnDefinition = "TEXT")
    private String animationSegmentsJson;

    @Column(name = "code_text", columnDefinition = "TEXT")
    private String codeText;

    @Column(name = "result_html", columnDefinition = "TEXT")
    private String resultHtml;

    @Column(name = "replay_html", columnDefinition = "TEXT")
    private String replayHtml;

    @Column(name = "guide_file_name", length = 220)
    private String guideFileName;

    @Column(name = "guide_files_json", columnDefinition = "TEXT")
    private String guideFilesJson;

    @Column(name = "animation_file_name", length = 220)
    private String animationFileName;

    @Column(name = "code_files_json", columnDefinition = "TEXT")
    private String codeFilesJson;

    @Column(name = "result_files_json", columnDefinition = "TEXT")
    private String resultFilesJson;

    @Column(name = "code_file_name", length = 220)
    private String codeFileName;

    @Column(name = "result_file_name", length = 220)
    private String resultFileName;

    @Column(name = "replay_file_name", length = 220)
    private String replayFileName;

    @Column(nullable = false)
    private int version = 1;

    public String getProtoId() {
        return protoId;
    }

    public void setProtoId(String protoId) {
        this.protoId = protoId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGuideHtml() {
        return guideHtml;
    }

    public void setGuideHtml(String guideHtml) {
        this.guideHtml = guideHtml;
    }

    public String getAnimationHtml() {
        return animationHtml;
    }

    public void setAnimationHtml(String animationHtml) {
        this.animationHtml = animationHtml;
    }

    public String getAnimationSegmentsJson() {
        return animationSegmentsJson;
    }

    public void setAnimationSegmentsJson(String animationSegmentsJson) {
        this.animationSegmentsJson = animationSegmentsJson;
    }

    public String getCodeText() {
        return codeText;
    }

    public void setCodeText(String codeText) {
        this.codeText = codeText;
    }

    public String getResultHtml() {
        return resultHtml;
    }

    public void setResultHtml(String resultHtml) {
        this.resultHtml = resultHtml;
    }

    public String getReplayHtml() {
        return replayHtml;
    }

    public void setReplayHtml(String replayHtml) {
        this.replayHtml = replayHtml;
    }

    public String getGuideFileName() {
        return guideFileName;
    }

    public void setGuideFileName(String guideFileName) {
        this.guideFileName = guideFileName;
    }

    public String getAnimationFileName() {
        return animationFileName;
    }

    public void setAnimationFileName(String animationFileName) {
        this.animationFileName = animationFileName;
    }

    public String getGuideFilesJson() {
        return guideFilesJson;
    }

    public void setGuideFilesJson(String guideFilesJson) {
        this.guideFilesJson = guideFilesJson;
    }

    public String getCodeFilesJson() {
        return codeFilesJson;
    }

    public void setCodeFilesJson(String codeFilesJson) {
        this.codeFilesJson = codeFilesJson;
    }

    public String getResultFilesJson() {
        return resultFilesJson;
    }

    public void setResultFilesJson(String resultFilesJson) {
        this.resultFilesJson = resultFilesJson;
    }

    public String getCodeFileName() {
        return codeFileName;
    }

    public void setCodeFileName(String codeFileName) {
        this.codeFileName = codeFileName;
    }

    public String getResultFileName() {
        return resultFileName;
    }

    public void setResultFileName(String resultFileName) {
        this.resultFileName = resultFileName;
    }

    public String getReplayFileName() {
        return replayFileName;
    }

    public void setReplayFileName(String replayFileName) {
        this.replayFileName = replayFileName;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
