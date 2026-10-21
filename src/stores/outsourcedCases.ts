import { computed, ref } from 'vue'

import { outsourcedCaseApi, type OutsourcedCase } from '@/api/outsourcedCases'

const cases = ref<OutsourcedCase[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')

export const caseByProtoId = computed<Record<string, OutsourcedCase>>(() => {
  const map: Record<string, OutsourcedCase> = {}
  for (const item of cases.value) map[item.protoId] = item
  return map
})

export function outsourcedCases(): OutsourcedCase[] {
  return cases.value
}

export function outsourcedCase(protoId: string | null | undefined): OutsourcedCase | null {
  if (!protoId) return null
  return caseByProtoId.value[protoId] ?? null
}

export function hasPublishedCase(protoId: string | null | undefined): boolean {
  const item = outsourcedCase(protoId)
  return !!item && item.status === 'PUBLISHED'
}

/**
 * 最近一次案例库加载失败的原因（空串＝没失败）。
 *
 * 为什么要单独暴露：`loadOutsourcedCases` 把 401 / 404 / 超时一律收进 error 字段，
 * 而此前**没有任何地方读它** —— 于是「后端没起」「接口 404」「未登录」在前端
 * 长得一模一样：详情页四个面板全是占位，用户只看到「什么都没有」。
 * 2026-10-16 定位该现象时就是被这一点拖长了排查。
 */
export function outsourcedCaseError(): string {
  return error.value
}

export async function loadOutsourcedCases(admin = false): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    cases.value = await outsourcedCaseApi.list(admin)
    loaded.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : '案例库加载失败'
  } finally {
    loading.value = false
  }
}

export async function reloadOutsourcedCases(admin = false): Promise<void> {
  loaded.value = false
  await loadOutsourcedCases(admin)
}

export function outsourcedCaseState() {
  return { cases, loading, loaded, error, caseByProtoId }
}
