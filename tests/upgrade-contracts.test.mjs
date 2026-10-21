import test from 'node:test'
import assert from 'node:assert/strict'
import { checkSolutionMap, checkQuestionState } from '../scripts/upgrade/contract-checks.mjs'
const question = (id, dependencies = []) => ({question_id: id, required: true,
  objective: `Objective ${id}`, inputs: [], outputs: ['result'], depends_on: dependencies, initial_routes: []})
const state = () => ({schema_version: '1.2', case_id: 'fixture-only', question_id: 'q07', revision: 1,
  workflow_status: 'QUESTION_COMPLETED', validity: 'CURRENT', verification_status: 'NOT_RUN',
  contract_check_status: 'PASS', approval: {actor_id: 'fixture-user', at: '2026-10-15T00:00:00Z'}})

test('Phase 0: nonconsecutive question IDs and a single route need no fabricated candidates', () => {
  const a = question('q07'); a.initial_routes = [{model_id: 'm01', method_id: 'M-OPT-001'}]
  assert.deepEqual(checkSolutionMap({questions: [a, question('appendix-A')]}), [])
})
test('Phase 0: duplicate IDs, dangling dependencies and cycles fail closed', () => {
  assert.match(checkSolutionMap({questions: [question('a'), question('a')]}).join(), /duplicate/)
  assert.match(checkSolutionMap({questions: [question('a', [{question_id: 'missing', kind: 'analysis_contract'}])]}).join(), /unknown dependency/)
  assert.match(checkSolutionMap({questions: [question('a', [{question_id: 'b', kind: 'analysis_contract'}]),
    question('b', [{question_id: 'a', kind: 'analysis_contract'}])]}).join(), /cycle/)
})
test('Phase 0: numerical dependencies require the point at which real output is needed', () => {
  const edge = {question_id: 'a', kind: 'numerical_output'}
  const map = {questions: [question('a'), question('b', [edge])]}
  assert.match(checkSolutionMap(map).join(), /required_at/)
  edge.required_at = 'FULL_ANALYSIS_RUNNING'
  assert.deepEqual(checkSolutionMap(map), [])
})
test('Phase 0: malformed data and missing required outputs are rejected', () => {
  assert.ok(checkSolutionMap(null).length)
  assert.ok(checkSolutionMap({questions: [null]}).length)
  const q = question('a'); q.outputs = []; q.required = 'yes'
  assert.ok(checkSolutionMap({questions: [q]}).length)
})
test('Phase 0: user-confirmed analysis remains NOT_RUN for numerical verification', () => {
  const s = state()
  assert.deepEqual(checkQuestionState(s), [])
  assert.equal(s.verification_status, 'NOT_RUN')
  s.validity = 'INVALIDATED_BY_DEPENDENCY'
  assert.deepEqual(checkQuestionState(s), []) // historical completion survives validity change
})
test('Phase 0: missing approval or failed model contract cannot declare completion', () => {
  const s = state(); delete s.approval
  assert.match(checkQuestionState(s).join(), /approval/)
  s.approval = {actor_id: 'fixture-user', at: '2026-10-15T00:00:00Z'}
  s.contract_check_status = 'FAIL'
  assert.match(checkQuestionState(s).join(), /contract_check_status/)
})
test('Phase 0: skip and cancellation do not imply verified success', () => {
  for (const status of ['SKIPPED', 'CANCELLED']) {
    const s = state(); s.workflow_status = status; s.verification_status = 'INCONCLUSIVE'
    assert.deepEqual(checkQuestionState(s), [])
    assert.equal(s.verification_status, 'INCONCLUSIVE')
  }
})
test('Phase 0: legacy state requires explicit migration and cannot masquerade as revision 1.2', () => {
  assert.ok(checkQuestionState({status: 'ANALYSIS_DONE', revision: 1}).length)
  assert.ok(checkQuestionState({...state(), revision: 0}).length)
  assert.ok(checkQuestionState({...state(), validity: 'COMPLETED'}).length)
})
