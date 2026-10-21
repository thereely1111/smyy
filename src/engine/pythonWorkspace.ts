import type { ArtifactNode } from '@/types/sandbox'
import type { PythonFile } from './pythonRunner'
/** Preserve backend paths exactly, and reject paths that could escape the virtual workspace. */
export function workspacePath(path: string): string {
  if (!path || path.startsWith('/') || path.includes('\\') || path.includes('\0') || path.split('/').some(part => part === '..' || part === '.' || !part)) throw new Error(`不合法的产物路径 ${path}`)
  return path
}
export function flattenArtifacts(nodes: ArtifactNode[]): ArtifactNode[] {
  return nodes.flatMap(node => node.children ? flattenArtifacts(node.children) : node.type === 'file' ? [node] : [])
}
export async function pythonSnapshot(nodes: ArtifactNode[], path: string, code: string, read: (node: ArtifactNode) => Promise<string | Uint8Array>): Promise<PythonFile[]> {
  workspacePath(path)
  const files = flattenArtifacts(nodes).filter(node => node.path !== path && !/\.(pdf|docx?|pptx?|mp4|zip|html)$/i.test(node.path))
  if (files.length > 256) throw new Error('会话文件超过 256 个，请精简文件后运行')
  let size = new TextEncoder().encode(code).byteLength
  const result: PythonFile[] = [{ path, content: code }]
  for (const node of files) {
    workspacePath(node.path)
    if ((node.size ?? 0) > 20 * 1024 * 1024) throw new Error(`文件 ${node.path} 超过 20 MB，暂不支持在浏览器运行`)
    const content = await read(node)
    size += typeof content === 'string' ? new TextEncoder().encode(content).byteLength : content.byteLength
    if (size > 32 * 1024 * 1024) throw new Error('本次运行的数据超过 32 MB，请精简数据或使用服务器环境')
    result.push({ path: node.path, content })
  }
  return result
}
