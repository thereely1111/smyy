package com.tipdm.agentplatform.domain;

/**
 * 资料库节点类型：决定正文存哪、用哪套读写栈。
 *
 * <p>节点树本身不带类型语义，{@code kind} 是分流开关——这是资料库的核心设计：
 * 新增 kind 只需实现对应处理器，节点表与树结构无需改动。</p>
 *
 * <p>{@code knowledge} 是特例：载荷不落在本模块，而是指向已有的知识库
 * （{@code LibraryNode.payloadKbId}），从而零成本复用既有检索能力、存量数据零迁移。</p>
 *
 * <p>枚举常量名与 DB CHECK 约束值一致（小写下划线），配合 {@code @Enumerated(STRING)} 直接映射。</p>
 */
public enum LibraryNodeKind {
    /** 纯目录容器，无正文。 */
    folder,
    /** 在线文档，正文为 markdown。 */
    doc,
    /** 结构化数据表，正文为字段与记录。 */
    database,
    /** 数据页面，正文为 HTML 产物树。 */
    web,
    /** 网页剪藏，正文为剪藏后的 markdown。 */
    link,
    /** 网盘文件，正文为文件字节。 */
    drive,
    /** 挂载已有知识库，正文由知识库模块提供。 */
    knowledge
}

