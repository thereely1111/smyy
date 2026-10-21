/** Phase 0 deterministic contract checks; no agent calls or business-state writes. */
export const WORKFLOW = ['PENDING_S1B', 'INITIAL_ANALYSIS_RUNNING', 'WAIT_MODEL_SELECTION',
  'FULL_ANALYSIS_RUNNING', 'WAIT_QUESTION_CONFIRMATION', 'QUESTION_COMPLETED',
  'BLOCKED', 'FAILED', 'CANCELLED', 'SKIPPED']
export const VALIDITY = ['CURRENT', 'STALE', 'NEEDS_REVIEW', 'INVALIDATED_BY_DEPENDENCY']
export const VERIFICATION = ['NOT_RUN', 'RUNNING', 'PASS', 'FAIL', 'INCONCLUSIVE']

/** This checks explicit metadata, never mathematical truth or required-output completeness. */
export function checkSolutionMap(map) {
  const errors = []
  if (!map || !Array.isArray(map.questions) || !map.questions.length) return ['questions must be a nonempty array']
  const ids = new Set()
  for (const q of map.questions) {
    if (!q || typeof q.question_id !== 'string' || !q.question_id.trim()) {
      errors.push('question_id must be a nonempty string'); continue
    }
    if (ids.has(q.question_id)) errors.push(`duplicate question_id: ${q.question_id}`)
    ids.add(q.question_id)
    if (typeof q.required !== 'boolean') errors.push(`${q.question_id}: required must be boolean`)
    if (typeof q.objective !== 'string' || !q.objective.trim()) errors.push(`${q.question_id}: objective required`)
    for (const field of ['inputs', 'outputs', 'depends_on', 'initial_routes']) {
      if (!Array.isArray(q[field])) errors.push(`${q.question_id}: ${field} must be an array`)
    }
    if (q.required && !q.outputs?.length) errors.push(`${q.question_id}: required question needs outputs`)
  }
  const graph = new Map()
  for (const q of map.questions) {
    if (!q || typeof q.question_id !== 'string') continue
    const edges = []
    for (const edge of Array.isArray(q.depends_on) ? q.depends_on : []) {
      if (!edge || !ids.has(edge.question_id)) errors.push(`${q.question_id}: unknown dependency ${edge?.question_id}`)
      else edges.push(edge.question_id)
      if (!['analysis_contract', 'numerical_output'].includes(edge?.kind)) errors.push(`${q.question_id}: invalid dependency kind`)
      if (edge?.kind === 'numerical_output' &&
          (typeof edge.required_at !== 'string' || !edge.required_at.trim())) {
        errors.push(`${q.question_id}: numerical dependency needs required_at`)
      }
    }
    graph.set(q.question_id, edges)
  }
  const active = new Set(), done = new Set()
  function visit(id) {
    if (active.has(id)) { errors.push(`dependency cycle at ${id}`); return }
    if (done.has(id)) return
    active.add(id)
    for (const parent of graph.get(id) ?? []) visit(parent)
    active.delete(id); done.add(id)
  }
  for (const id of ids) visit(id)
  return errors
}

export function checkQuestionState(state) {
  if (!state || typeof state !== 'object') return ['question state required']
  const errors = []
  for (const field of ['case_id', 'question_id']) {
    if (typeof state[field] !== 'string' || !state[field].trim()) errors.push(`${field} required`)
  }
  if (state.schema_version !== '1.2') errors.push('schema_version must be 1.2')
  if (!Number.isSafeInteger(state.revision) || state.revision < 1) errors.push('revision must be a positive safe integer')
  for (const [field, values] of [['workflow_status', WORKFLOW], ['validity', VALIDITY], ['verification_status', VERIFICATION]]) {
    if (!values.includes(state[field])) errors.push(`invalid ${field}`)
  }
  if (state.workflow_status === 'QUESTION_COMPLETED') {
    if (!state.approval || typeof state.approval.actor_id !== 'string' || !state.approval.actor_id.trim() ||
        typeof state.approval.at !== 'string' || !Number.isFinite(Date.parse(state.approval.at))) errors.push('completion needs recorded approval')
    if (state.contract_check_status !== 'PASS') errors.push('completion needs contract_check_status PASS')
  }
  return errors
}
