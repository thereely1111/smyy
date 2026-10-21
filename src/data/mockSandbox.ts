/**
 * mock 数据源
 *
 * 作用：后端未启动、或**登录未就绪**（当前所有接口都要求 JWT，见 §11.2 未决项 3）时，
 * 沙箱工作台仍能完整演示 —— 假会话、假执行输出、假产物树。
 *
 * ⚠️ 纪律：mock 数据在 UI 上必须**显式打标**。
 *    不能让演示数据看起来像真实运行结果。
 */

import type { ArtifactNode, AttachmentItem } from '@/types/sandbox'
import type { AguiEvent } from '@/types/agui'

/** 演示文件同时提供 Python 3.11 和既有 JavaScript 示例，内容仅用于界面验证。 */
export function mockArtifactTree(): ArtifactNode[] {
  return [
    {
      name: 'data',
      path: 'artifacts/data',
      type: 'dir',
      children: [
        { name: '附件1_光伏出力.csv', path: 'artifacts/data/附件1_光伏出力.csv', type: 'file', size: 184320 },
        { name: '附件2_负荷曲线.csv', path: 'artifacts/data/附件2_负荷曲线.csv', type: 'file', size: 176540 },
      ],
    },
    {
      name: 'src',
      path: 'artifacts/src',
      type: 'dir',
      children: [
        { name: 'solve_q1.py', path: 'artifacts/src/solve_q1.py', type: 'file', size: 600 },
        { name: 'clean.js', path: 'artifacts/src/clean.js', type: 'file', size: 2140 },
        { name: 'solve_q1.js', path: 'artifacts/src/solve_q1.js', type: 'file', size: 5820 },
      ],
    },
    { name: 'result_q1.json', path: 'artifacts/result_q1.json', type: 'file', size: 1024 },
    { name: 'report.md', path: 'artifacts/report.md', type: 'file', size: 4120 },
  ]
}

/** 造文件内容 */
export function mockArtifactContent(path: string): { content: string; binary: boolean } {
  const files: Record<string, string> = {
    'artifacts/src/solve_q1.py': [
      '# Python 3.11 演示代码 不是赛题答案',
      'import csv',
      'import json',
      'import sys',
      'from pathlib import Path',
      '',
      'data = Path(__file__).parent.parent / "data" / "附件1_光伏出力.csv"',
      'with data.open(encoding="utf-8") as stream:',
      '    rows = list(csv.DictReader(stream))',
      'cost = sum((float(row["load"]) - float(row["pv"])) * float(row["price"]) for row in rows)',
      'print(f"Python {sys.version.split()[0]}")',
      'print(f"数据行数 {len(rows)}")',
      'print(f"切片购电费 {cost:.4f}")',
      'Path("result_preview.json").write_text(json.dumps({"cost": round(cost, 4)}), encoding="utf-8")',
    ].join('\n'),
    // 数据文件：可读、可改，被上面两个脚本 readFile 读走
    'artifacts/data/附件1_光伏出力.csv': [
      'time,price,load,pv',
      '0:00,0.4248,3439.8466,0',
      '3:00,0.4102,3211.4522,0',
      '6:00,0.3986,3298.7713,182.3341',
      '9:00,0.5638,4102.3361,2141.5528',
      '12:00,0.7192,4487.2194,4318.2276',
      '15:00,0.6451,4351.8847,3025.7761',
      '18:00,0.6843,4203.1158,642.1185',
      '21:00,0.5127,3812.4439,0',
    ].join('\n'),
    'artifacts/data/附件2_负荷曲线.csv': [
      'time,load',
      '0:00,3439.8466',
      '6:00,3298.7713',
      '12:00,4487.2194',
      '18:00,4203.1158',
    ].join('\n'),

    // 可运行脚本：真的能跑，且真的去读上面的 csv
    //
    // 路径写 `../data/xxx.csv` —— 脚本在 artifacts/src/，
    // 数据在 artifacts/data/，同属 artifacts/ 下的两个兄弟目录。
    // 沙箱引擎按「相对入口文件」解析（与真实文件系统一致），
    // 所以这里用相对路径最自然，也最经得起挪文件。
    'artifacts/src/clean.js': [
      '// 数据体检：读附件 csv，报每列的量级',
      "const csv = sandbox.readFile('../data/附件1_光伏出力.csv')",
      "const rows = csv.trim().split('\\n')",
      "const head = rows[0].split(',')",
      "console.log('列名:', head.join(' | '))",
      "console.log('行数:', rows.length - 1, '（当前切片）')",
      '',
      'for (let c = 1; c < head.length; c++) {',
      '  let sum = 0',
      '  for (let i = 1; i < rows.length; i++) sum += Number(rows[i].split(",")[c])',
      "  console.log('  ' + head[c] + ' 均值为 ' + (sum / (rows.length - 1)).toFixed(4))",
      '}',
    ].join('\n'),

    'artifacts/src/solve_q1.js': [
      '// 问题一：按分时电价算全天购电费',
      "// 试着改改：把 price 乘 1.1，看合计涨多少（改完点运行）",
      "const csv = sandbox.readFile('../data/附件1_光伏出力.csv')",
      "const rows = csv.trim().split('\\n').slice(1)",
      '',
      'let cost = 0',
      'let netSum = 0',
      'for (const r of rows) {',
      "  const [, price, load, pv] = r.split(',')",
      '  const net = Number(load) - Number(pv)',
      '  cost += net * Number(price)',
      '  netSum += net',
      '}',
      '',
      "console.log('净负荷合计(kW):', netSum.toFixed(4))",
      "console.log('全天购电费(元):', cost.toFixed(4))",
      "console.log('（这是切片演示，不是赛题答案）')",
    ].join('\n'),

    'artifacts/result_q1.json': JSON.stringify(
      { problem: 'q1', segments: 144, note: '这是 mock 数据，不是真实运行结果' },
      null,
      2,
    ),
    'artifacts/report.md': [
      '# 问题一 结果说明',
      '',
      '> 本文件由 mock 数据生成，仅用于演示界面。',
      '',
      '单日 144 段，采用分时电价贪心调度。',
    ].join('\n'),
  }

  if (files[path] !== undefined) {
    return { content: files[path], binary: false }
  }
  return { content: `（mock：文件 ${path} 的内容未预置）`, binary: false }
}

/** 造附件列表（注意 fileName 是中文原名，ref 是 ASCII 名 —— 与后端行为一致） */
export function mockAttachments(): AttachmentItem[] {
  return [
    { fileId: 'f1', ref: 'fujian1.csv', fileName: '附件1_光伏出力.csv', size: 184320, uploadedAt: '2026-09-28T10:12:00' },
    { fileId: 'f2', ref: 'fujian2.csv', fileName: '附件2_负荷曲线.csv', size: 176540, uploadedAt: '2026-09-28T10:12:30' },
    { fileId: 'f3', ref: 'file.pdf', fileName: '赛题说明.pdf', size: 512000, uploadedAt: '2026-09-28T10:13:00' },
  ]
}

/**
 * 造一段 AG-UI 事件流（模拟一次真实的 shell 执行）
 *
 * 事件名刻意用后端既有风格：TOOL_CALL_START / TOOL_CALL_ARGS / TOOL_RESULT / RUN_FINISHED。
 */
export function mockAguiEvents(): AguiEvent[] {
  return [
    { type: 'RUN_STARTED' },
    { type: 'TEXT_MESSAGE_START' },
    { type: 'TEXT_MESSAGE_CONTENT', delta: '我来跑一下数据清洗脚本。' },
    { type: 'TEXT_MESSAGE_END' },
    {
      type: 'TOOL_CALL_START',
      toolCallId: 'tc-1',
      toolCallName: 'execute',
    },
    {
      type: 'TOOL_CALL_ARGS',
      toolCallId: 'tc-1',
      delta: JSON.stringify({ command: 'python src/clean.py', timeout: 600 }),
    },
    {
      type: 'TOOL_RESULT',
      toolCallId: 'tc-1',
      content: {
        stdout: '            count      mean       std\n光伏出力    144.0    412.35    188.02\n负荷        144.0    736.41    120.55',
        stderr: '',
        exitCode: 0,
      },
    },
    { type: 'TEXT_MESSAGE_START' },
    { type: 'TEXT_MESSAGE_CONTENT', delta: '数据读进来了，144 段齐全，光伏白天出力正常。' },
    { type: 'TEXT_MESSAGE_END' },
    { type: 'RUN_FINISHED', outcome: { type: 'success' } },
  ]
}

/** 一次失败执行的样例（用于验证 UI 的错误分支） */
export function mockFailedExecEvents(): AguiEvent[] {
  return [
    { type: 'RUN_STARTED' },
    {
      type: 'TOOL_CALL_START',
      toolCallId: 'tc-x',
      toolCallName: 'execute',
    },
    { type: 'TOOL_CALL_ARGS', toolCallId: 'tc-x', delta: JSON.stringify({ command: 'python missing.py' }) },
    {
      type: 'TOOL_RESULT',
      toolCallId: 'tc-x',
      isError: true,
      content: {
        stdout: '',
        stderr: 'python: can\'t open file \'missing.py\': [Errno 2] No such file or directory',
        exitCode: 2,
      },
    },
    { type: 'RUN_FINISHED', outcome: { type: 'success' } },
  ]
}
