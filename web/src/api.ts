/**
 * 前端 API 客户端。
 *
 * 只跟**业务层**说话（/api 由 vite 代理到 :8081）。
 * 绝不直连认知服务 :8090 —— 那边的令牌是管理令牌，
 * 一旦下发到浏览器，等于把"写记忆、改人格"的权限公开。
 */

export interface Evidence { title: string; brief?: string; tags?: string[] }

const TOKEN_KEY = 'pasm_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}
export function setToken(t: string): void {
  localStorage.setItem(TOKEN_KEY, t)
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const t = getToken()
  if (t) headers['Authorization'] = 'Bearer ' + t
  const r = await fetch('/api' + path, { headers, ...init })
  if (r.status === 401 || r.status === 403) {
    clearToken()
    throw new Error('登录已失效，请重新登录')
  }
  if (!r.ok) {
    const body = await r.text()
    throw new Error(String(r.status) + ' ' + body.slice(0, 200))
  }
  return r.json() as Promise<T>
}

export const api = {
  login: (username: string, password: string) =>
    call<{ token: string; role: string; displayName: string }>('/auth/login', {
      method: 'POST', body: JSON.stringify({ username, password }),
    }),

  /** 带依据的问答。认知服务会过相关性闸门；无依据时 refused=true（如实显示，不软化） */
  ask: (patientRef: string, question: string, k = 5) =>
    call<{ evidence: { hits: Evidence[] }; requiresPhysicianConfirmation: boolean; disclaimer: string }>(
      '/assist/ask', { method: 'POST', body: JSON.stringify({ patientRef, question, k }) }),

  history: (patientRef: string) =>
    call<{ encounters: Record<string, unknown>[] }>(
      '/encounters?patientRef=' + encodeURIComponent(patientRef)),

  /** 预问诊：开一次问诊，拿到第一个问题（或红旗直接建议就医） */
  startConsult: (patientRef: string, chiefComplaint: string) =>
    call<ConsultState>('/consult/start', {
      method: 'POST', body: JSON.stringify({ patientRef, chiefComplaint }) }),
  answer: (sessionKey: string, value: string) =>
    call<ConsultState>('/consult/answer', {
      method: 'POST', body: JSON.stringify({ sessionKey, value }) }),
  finishConsult: (sessionKey: string) =>
    call<{ ok: boolean; triage: Triage | null; coverage: Record<string, unknown> }>(
      '/consult/finish', { method: 'POST', body: JSON.stringify({ sessionKey }) }),

  /** 检验单：识别 → 返回**待确认**结果（未确认不得入病历） */
  parseLab: (patientRef: string, imageUrl: string) =>
    call<LabReport>('/lab/parse', {
      method: 'POST', body: JSON.stringify({ patientRef, imageUrl }) }),
  confirmLab: (reportKey: string, corrections: Record<string, number>) =>
    call<{ ok: boolean; written: number }>('/lab/confirm', {
      method: 'POST', body: JSON.stringify({ reportKey, corrections, allItems: true }) }),

  /** 采纳 / 修改 / 否决 —— 最高质量的学习信号，必须落到后端（不能只改 UI） */
  feedback: (patientRef: string, kind: string, action: string) =>
    call<{ ok: boolean }>('/assist/feedback', {
      method: 'POST', body: JSON.stringify({ patientRef, kind, action }) }),

  /** 患者档案（业务层视图）：过敏史 / 慢病来自结构化字段。 */
  patient: (ref: string) =>
    call<{ ref: string; name: string | null; sex: string | null; age: number | null; allergy: string | null; chronic: string | null }>(
      '/patient?ref=' + encodeURIComponent(ref)),

  /** 该患者历史就诊（业务库真实数据源，非前端硬编码）。 */
  patientEncounters: (ref: string) =>
    call<{ id: string; time: string; title: string; summary: string; urgency: string }[]>(
      '/patient/encounters?ref=' + encodeURIComponent(ref)),

  /** 后台：真实患者列表（含最新就诊 / 分诊 / 次数）。 */
  adminPatients: () =>
    call<{ ref: string; name: string; allergy: string; chronic: string; encounterCount: number; last: string; dept: string; urgency: string }[]>(
      '/admin/patients'),

  /** 后台：审计（append-only，最近 200 条）。 */
  adminAudit: () =>
    call<{ time: string; ref: string; actor: string; action: string; evidenceCount: number; modelVersion: string; refused: boolean }[]>(
      '/admin/audit'),

  /** 后台：运营统计（今日问诊量 / 红旗命中 / 拒答率 / 采纳率）。 */
  adminStats: () =>
    call<{ consultToday: number; redFlags: number; refusalRate: number; adoptionRate: number; askTotal: number; feedbackTotal: number }>(
      '/admin/stats'),
}

export interface ConsultQuestion { key: string; text: string; why: string; from_tree: string }
export interface Triage { urgency: string; advice: string; suggested_department?: string }
export interface ConsultState {
  halted: boolean
  question: ConsultQuestion | null
  red_flags: { label: string; advice: string; urgency: string }[]
  triage: Triage | null
  coverage: { answered: number; total: number; ratio: number }
}
export interface LabItem {
  name: string; raw_name: string; value: number | null; unit: string
  flag: string; critical: string[]; unrecognized: boolean; confirmed: boolean
}
export interface LabReport {
  report_key: string; items: LabItem[]; needs_confirmation: boolean
  critical: { count: number; forced: boolean; advice: string }
  disclaimer: string
}
