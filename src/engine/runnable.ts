/**
 * 可执行性判定 —— 沙箱引擎能跑哪些文件。
 *
 * 沙箱引擎用 `new Function` 执行，所以只认 **JavaScript/TypeScript**。
 * 产物目录里还常有 csv / json / md / png，那些是**数据或文档，不是代码** ——
 * 打开它们可以看、可以改，但不该给「运行」按钮。
 *
 * 为什么要单独一个文件：文件编辑页、代码台、工作台三处都要做同一个判断，
 * 判定口径集中在这是为了避免三处各写一套、口径漂移。
 */

/** 沙箱引擎能执行的扩展名（小写，不含点） */
export const RUNNABLE_EXTENSIONS = ['js', 'mjs', 'cjs', 'ts'] as const

/** 取小写扩展名（无扩展名返回空串） */
export function extOf(path: string): string {
  const clean = path.split(/[?#]/)[0]
  const base = clean.split('/').pop() ?? ''
  const i = base.lastIndexOf('.')
  return i > 0 ? base.slice(i + 1).toLowerCase() : ''
}

/** 这个文件能否用沙箱引擎跑 */
export function isRunnable(path: string): boolean {
  return (RUNNABLE_EXTENSIONS as readonly string[]).includes(extOf(path))
}

/**
 * 给不可执行的文件一句人话解释 —— 直接显示在按钮位上，
 * 别让用户点了没反应。
 */
export function whyNotRunnable(path: string): string {
  const ext = extOf(path)
  if (!ext) return '这个文件没有扩展名，无法判断是不是代码'
  if (['csv', 'tsv', 'json', 'txt', 'md', 'markdown'].includes(ext)) {
    return `.${ext} 是数据/文本文件 —— 它可以被代码读取，但本身不是可执行代码`
  }
  if (['png', 'jpg', 'jpeg', 'gif', 'svg', 'pdf'].includes(ext)) {
    return `.${ext} 是二进制内容，不能作为代码执行`
  }
  return `.${ext} 不是沙箱引擎支持的类型（支持 ${RUNNABLE_EXTENSIONS.map((e) => '.' + e).join(' / ')}）`
}
