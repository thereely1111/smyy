#!/usr/bin/env node
/**
 * 换算智能体目录名 → agentId（UUID v3），与后端保持逐位一致。
 *
 * 后端源码：backend/src/main/java/com/tipdm/agentplatform/config/FileSystemAgentLoader.java:107
 *   UUID id = UUID.nameUUIDFromBytes(dirName.getBytes(StandardCharsets.UTF_8));
 *
 * 用法：
 *   node scripts/check-agent-id.mjs                     # 校验源码里的常量对不对
 *   node scripts/check-agent-id.mjs 数模演易             # 算一个目录名
 *
 * 为什么要单独有脚本：虽然前端已在 src/utils/nameUuid.ts 里实现了同一套推导（浏览器无同步
 * crypto，故手写 MD5），这里用 node:crypto 再实现一遍可交叉验证 —— 二者结果必须逐位一致。
 */

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/** 与 Java UUID.nameUUIDFromBytes 等价：MD5 后置 version=3、variant=IETF */
export function nameUUIDFromBytes(input) {
  const b = Buffer.from(createHash('md5').update(Buffer.from(input, 'utf8')).digest())
  b[6] = (b[6] & 0x0f) | 0x30 // version 3
  b[8] = (b[8] & 0x3f) | 0x80 // variant IETF
  const h = b.toString('hex')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/** 已知正确值（用 Java 端算法手工核对过）—— 用作自检 */
const KNOWN = [
  ['数模演易', 'bc68f09f-4705-3bf6-ab31-31c93410f59d'],
  ['数据分析助手', 'd01395cf-f5ba-33cb-aac7-8b789d362c8b'],
  ['旅行规划师', '30376c2e-c402-3feb-98f6-745460924435'],
]

const arg = process.argv[2]

if (arg) {
  console.log(nameUUIDFromBytes(arg))
  process.exit(0)
}

// 无参数：自检 + 校验源码常量
let failed = 0
for (const [name, expect] of KNOWN) {
  const got = nameUUIDFromBytes(name)
  if (got !== expect) {
    console.error(`✗ 算法自检失败 ${name}: got=${got} exp=${expect}`)
    failed++
  }
}
if (failed === 0) console.log(`✓ 算法自检通过（${KNOWN.length} 例）`)

// 无参数时：自检 + 打印默认智能体名推导出的 agentId。
// 注意：前端已改为**运行时推导**（src/config/index.ts + src/utils/nameUuid.ts），
// 不再在源码里写死 UUID，所以这里只核对「算法自检」与「默认目录名的换算结果」。
const here = dirname(fileURLToPath(import.meta.url))
const cfgPath = resolve(here, '../../src/config/index.ts')
const cfg = readFileSync(cfgPath, 'utf8')

const n = cfg.match(/VITE_SMYY_AGENT_NAME[^\n]*\|\|\s*'([^']+)'/)
const agentName = n ? n[1] : '数学建模解答助手'

const computed = nameUUIDFromBytes(agentName)
console.log(`✓ 默认智能体「${agentName}」→ ${computed}（运行期由 src/utils/nameUuid.ts 推导）`)
process.exit(failed === 0 ? 0 : 1)
