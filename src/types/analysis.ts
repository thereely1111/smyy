/**
 * 「题目分析」产物契约 —— 与 smyy 自带「数学建模解答助手」的产物对齐
 *
 * 为什么在这里再写一份类型：那边是文件型 Agent 的 JSON 产物，形状由它的
 * `mm-1-*` 系列 Skill 规定（`truth/problem_contract.json`、`truth/solution_map.json`、
 * `analysis_result.json`）。这边只需要「渲染用得到的那几个字段」。
 * **不要求全量建模** —— 契约里多出来的字段一律忽略，渲染层对缺字段取兜底
 * 文案而不是抛错：智能体是 LLM，产物形状会有波动，前端不能因为少一个
 * 可选字段就白屏。
 *
 * 三个产物各管一段：
 *   ProblemContract  —— 题目事实层（来源、事实、子问题、依赖、假设、未知、符号）
 *   SolutionMap      —— 逐问路线图（机理、候选模型、推荐路线、步骤、验证计划、风险）
 *   AnalysisResult   —— 结果索引（状态、数据评估、待确认项、下游交接、局限）
 */

/** 证据类型：题面明写的「事实」与推出来的「推断」必须分开 */
export type EvidenceType = 'explicit_fact' | 'inference'

/** 来源读取状态 —— 决定页面上是否要标注「未读取」 */
export type SourceStatus = 'read' | 'partial' | 'unread' | 'missing'

export interface ProblemSource {
  source_id: string
  name: string
  kind?: string
  status: SourceStatus
  notes?: string
}

export interface ResearchObject {
  description: string
  background?: string
}

export interface EvidencedItem {
  item_id: string
  statement: string
  evidence_type: EvidenceType
  source_refs: string[]
}

export interface ContractTask {
  question_id: string
  task_verb: string
  objective: string
  problem_type?: string
  inputs: unknown[]
  outputs: unknown[]
  constraints: unknown[]
  evaluation_criteria: unknown[]
  deliverables: unknown[]
  source_refs: string[]
}

export interface ContractDependency {
  from_question_id: string
  to_question_id: string
  dependency_type: string
  rationale: string
}

export interface ContractAssumption {
  assumption_id: string
  statement: string
  rationale: string
  scope: string
  potential_impact: string
  status: 'proposed' | 'confirmed' | 'rejected'
  source_refs?: string[]
}

export interface ContractUnknown {
  unknown_id: string
  description: string
  impact: string
  blocking: boolean
  question_for_user?: string | null
}

export interface ContractSymbol {
  symbol_id: string
  symbol: string
  name: string
  role: 'state' | 'input' | 'decision' | 'parameter' | 'metric' | 'unknown'
  definition: string
  unit: string | null
  question_ids?: string[]
  source_refs?: string[]
  status?: string
}

export interface ProblemContract {
  schema_version: string
  case_id: string
  revision: string
  sources: ProblemSource[]
  research_object: ResearchObject
  facts: EvidencedItem[]
  tasks: ContractTask[]
  dependencies: ContractDependency[]
  assumptions: ContractAssumption[]
  unknowns: ContractUnknown[]
  symbols: ContractSymbol[]
}

export interface CandidateModel {
  model_id: string
  name: string
  rationale: string
  data_needs: unknown[]
  advantages: unknown[]
  limitations: unknown[]
  applicability: string
}

export interface RecommendedRoute {
  model_id?: string
  reason?: string
  status?: string
}

export interface SolutionRoute {
  question_id: string
  objective: string
  mechanism: string
  candidate_models: CandidateModel[]
  recommended_route: RecommendedRoute | null
  alternatives: unknown[]
  required_inputs: unknown[]
  parameters: unknown[]
  decision_variables: unknown[]
  metrics: unknown[]
  conditions: Record<string, unknown>
  data_preprocessing: unknown[]
  solving_steps: unknown[]
  validation_plan: unknown[]
  risks: unknown[]
}

export interface SolutionMap {
  schema_version: string
  case_id: string
  routes: SolutionRoute[]
}

export interface DataAssessment {
  available?: unknown[]
  missing?: unknown[]
  recommendations?: unknown[]
}

export interface AnalysisResult {
  schema_version: string
  case_id: string
  status: 'COMPLETED' | 'PARTIAL' | 'BLOCKED' | 'FAILED'
  source_inventory: Array<Record<string, unknown>>
  problem_contract: { path: string; revision: string }
  solution_map: { path: string }
  data_assessment: DataAssessment
  evaluation_plan: unknown[]
  open_questions: unknown[]
  handoff: {
    coding?: unknown[]
    paper?: unknown[]
    quality_review?: unknown[]
    visualization_interface_only?: unknown[]
  }
  limitations: unknown[]
}

/** 请求体 —— 与 `problem-analysis-request.schema.json` 对齐 */
export interface ProblemAnalysisRequest {
  schema_version: '1.0'
  case_id: string
  problem: {
    title?: string | null
    text: string
    source?: string | null
  }
  attachments: Array<{
    attachment_id: string
    name: string
    description?: string | null
    content?: string | null
    path?: string | null
    media_type?: string | null
    /** 站点侧增补：文件已真上传到本会话工作区（不是只有名字） */
    uploaded?: boolean
    /** 站点侧增补：'problem' = 题目文件（题面本身）／'data' = 数据附件 */
    role?: 'problem' | 'data'
  }>
  data_descriptions?: Array<Record<string, unknown>>
  user_constraints?: string[]
  output_preferences?: Record<string, unknown>
}

/** 一次分析的全部产出（缺项一律为 null，调用方据此判断覆盖率） */
export interface AnalysisOutcome {
  status: 'COMPLETED' | 'PARTIAL' | 'BLOCKED' | 'FAILED'
  caseId: string
  contract: ProblemContract | null
  solutionMap: SolutionMap | null
  result: AnalysisResult | null
  /** 分析报告 Markdown 原文（版本号由智能体决定，可能为空） */
  report: string
  /** 助手在流里输出的文本（产物缺失时的兜底展示） */
  replyText: string
  /**
   * 没拿到结果时的**真实原因**（HTTP 状态、平台回的错误事件、超时…）。
   *
   * 平台把「同会话已有活跃 run」这类拒绝包成 HTTP 200 + 一个 RAW 错误事件，
   * request 层面看不出任何异常；早先这里只写「没有返回可用的分析结果」，
   * 排查时完全无从下手。有就照实带出去。
   */
  failureNote?: string
  /** 实际读到的产物路径，逐条列出，便于使用者核对 */
  artifacts: string[]
}
