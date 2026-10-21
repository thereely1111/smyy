/**
 * 由「智能体中文名」推导 File Agent 的 UUID。
 *
 * 对齐后端 `java.util.UUID.nameUUIDFromBytes(name.getBytes(UTF_8))`：
 * 平台 FileSystemAgentLoader 用这一算法把 workspace/agents/ 下的目录名映射成 agentId，
 * 所以只要目录名不变，算出来的 ID 就与后端一致 —— 改名时无需手改代码。
 *
 * 注意：这不是常见的「带 namespace 的 UUID v3」（如 uuid npm 包的 v3），
 * 而是**裸 MD5** 后再修正版本位与变体位，二者结果不同，不要混用。
 *
 * 来源：kuaike `src/utils/nameUuid.ts`（跨项目验证过，与 Java 侧逐一比对过）。
 */

/** 循环左移 */
function rotl(x: number, n: number): number {
  return ((x << n) | (x >>> (32 - n))) >>> 0
}

/** MD5 压缩函数用的常量表 K[i] = floor(abs(sin(i+1)) * 2^32) */
const K = (() => {
  const t = new Uint32Array(64)
  for (let i = 0; i < 64; i++) t[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0
  return t
})()

/** 每步左移位数 */
const S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]

/** 计算 MD5，返回 16 字节摘要 */
function md5(input: Uint8Array): Uint8Array {
  const bitLenLo = (input.length * 8) >>> 0

  // 填充：原数据 + 0x80 + 补 0 + 8 字节小端长度 ≡ 56 (mod 64)
  const paddedLen = (((input.length + 8) >> 6) + 1) << 6
  const msg = new Uint8Array(paddedLen)
  msg.set(input)
  msg[input.length] = 0x80
  // 小端写入 64 位长度；目录名远短于 512MB，高 32 位恒为 0，已由初始 0 保证
  msg[paddedLen - 8] = bitLenLo & 0xff
  msg[paddedLen - 7] = (bitLenLo >>> 8) & 0xff
  msg[paddedLen - 6] = (bitLenLo >>> 16) & 0xff
  msg[paddedLen - 5] = (bitLenLo >>> 24) & 0xff

  let h0 = 0x67452301
  let h1 = 0xefcdab89
  let h2 = 0x98badcfe
  let h3 = 0x10325476

  const m = new Uint32Array(16)

  for (let off = 0; off < paddedLen; off += 64) {
    for (let j = 0; j < 16; j++) {
      const p = off + j * 4
      m[j] = (msg[p] | (msg[p + 1] << 8) | (msg[p + 2] << 16) | (msg[p + 3] << 24)) >>> 0
    }

    let a = h0
    let b = h1
    let c = h2
    let d = h3

    for (let i = 0; i < 64; i++) {
      let f: number
      let g: number
      if (i < 16) {
        f = (b & c) | (~b & d)
        g = i
      } else if (i < 32) {
        f = (d & b) | (~d & c)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        f = b ^ c ^ d
        g = (3 * i + 5) % 16
      } else {
        f = c ^ (b | ~d)
        g = (7 * i) % 16
      }
      f = f >>> 0

      const tmp = d
      d = c
      c = b
      b = (b + rotl((a + f + K[i] + m[g]) >>> 0, S[i])) >>> 0
      a = tmp
    }

    h0 = (h0 + a) >>> 0
    h1 = (h1 + b) >>> 0
    h2 = (h2 + c) >>> 0
    h3 = (h3 + d) >>> 0
  }

  const out = new Uint8Array(16)
  const hs = [h0, h1, h2, h3]
  for (let i = 0; i < 4; i++) {
    out[i * 4] = hs[i] & 0xff
    out[i * 4 + 1] = (hs[i] >>> 8) & 0xff
    out[i * 4 + 2] = (hs[i] >>> 16) & 0xff
    out[i * 4 + 3] = (hs[i] >>> 24) & 0xff
  }
  return out
}

const HEX = '0123456789abcdef'

function toHex(bytes: Uint8Array): string {
  let s = ''
  for (const b of bytes) s += HEX[b >> 4] + HEX[b & 0x0f]
  return s
}

/**
 * 由智能体目录名推导 agentId（等价于 Java `UUID.nameUUIDFromBytes`）。
 *
 * @param name 智能体目录名，如「数学建模解答助手」
 */
export function agentIdFromName(name: string): string {
  const encoded = new TextEncoder().encode(name)
  const digest = md5(encoded)

  // 版本位 → 3（MD5），变体位 → RFC 4122
  digest[6] = (digest[6] & 0x0f) | 0x30
  digest[8] = (digest[8] & 0x3f) | 0x80

  const h = toHex(digest)
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`
}
