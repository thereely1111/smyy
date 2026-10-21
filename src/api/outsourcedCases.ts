import { http } from '@/api/http'

export type CaseSection = 'guide' | 'animation' | 'code' | 'result' | 'replay'

export interface OutsourcedCase {
  id: string
  protoId: string
  slug: string
  title: string
  tag: string
  category: string
  source: string
  status: 'DRAFT' | 'PUBLISHED' | 'BLANK'
  guideHtml: string | null
  animationHtml: string | null
  animationSegmentsJson: string | null
  codeText: string | null
  resultHtml: string | null
  replayHtml: string | null
  guideFileName: string | null
  guideFilesJson: string | null
  animationFileName: string | null
  codeFileName: string | null
  codeFilesJson: string | null
  resultFileName: string | null
  resultFilesJson: string | null
  replayFileName: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface CaseSectionInput {
  guide?: string | null
  animation?: string | null
  code?: string | null
  result?: string | null
  replay?: string | null
  animationSegments?: string | null
  guideFiles?: string | null
  codeFiles?: string | null
  resultFiles?: string | null
}

export const outsourcedCaseApi = {
  list: async (admin = false) => {
    const path = admin ? '/api/smyy/admin/cases' : '/api/smyy/cases'
    return (await http.get<{ items: OutsourcedCase[]; total: number }>(path)).items
  },
  get: (protoId: string) => http.get<OutsourcedCase>(`/api/smyy/cases/${encodeURIComponent(protoId)}`),
  upsert: (data: {
    protoId: string
    title: string
    tag: string
    category: string
    sections: CaseSectionInput
  }) => http.post<OutsourcedCase>('/api/smyy/admin/cases', data),
  replaceSection: (protoId: string, section: CaseSection, content: string | null) =>
    http.put<OutsourcedCase>(`/api/smyy/admin/cases/${encodeURIComponent(protoId)}/sections/${section}`, { content }),
  remove: (protoId: string) => http.delete<void>(`/api/smyy/admin/cases/${encodeURIComponent(protoId)}`),
}
