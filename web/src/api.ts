/**
 * 前端 API 客户端。
 *
 * 只跟**业务层**说话（/api 由 vite 代理到 :8081）。
 * 绝不直连认知服务 :8090 —— 那边的令牌是管理令牌，
 * 一旦下发到浏览器，等于把"写记忆、改人格"的权限公开。
 */

export interface Evidence {
  title: string
  brief?: string
  tags?: string[]
  /** `doc` = 来自机构资料库；`memory` = 来自该患者的档案/记忆。医生要分得清依据来自哪一侧。 */
  kind?: 'doc' | 'memory'
  docKey?: string
  salience?: number
}

/** 资料库条目（业务库视图）。`tags` 是相关性闸门的命中面，不是装饰。 */
export interface KbDoc {
  docKey: string
  title: string
  content: string
  tags: string[]
  department: string
  status: 'active' | 'inactive'
  version: string
  reviewer: string
  reviewed: boolean
  updatedAt: string
  updatedBy: string
}

/** 资料库 → 认知侧检索副本的同步结果。`synced=false` = 库里改了但检索侧没生效（漂移）。 */
export interface KbSync {
  expected: number
  synced: boolean
  error?: string
  cognition?: unknown
}

/**
 * 当前身份（`GET /api/auth/me`）。
 *
 * ★ 界面**必须**用它决定"能进哪个工作区、显示什么数据范围"，而不是自己猜或读登录时
 * 随手存的副本 —— 令牌还在、身份可能已经不同。界面据此分支只是体验；
 * 真正的边界在服务端（患者换 `ref` 读别人记录会被 403）。
 */
export interface IdentityView {
  username: string
  /** 四角色之一：PATIENT / DOCTOR / DEPT_ADMIN / SUPER_ADMIN */
  role: string
  roleLabel: string
  displayName: string
  department: string
  staffId: string
  /** 患者角色绑定的档案标识（医护为空） */
  patientRef: string
  /** 可读文案，如「本科室：发热门诊」/「全院」/「本人（demo-patient-001）」 */
  scopeLabel: string
  hospitalWide: boolean
  clinicalSide: boolean
  /** 仅供界面禁用按钮；服务端会独立拒绝，不能只靠隐藏 */
  canWriteKb: boolean
  canWriteConfig: boolean
}

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
  if (r.status === 401) {
    clearToken()
    throw new Error('登录已失效，请重新登录')
  }
  if (r.status === 403) {
    // ★ 403 与 401 必须分开处理。原先两者一起清令牌，于是"越权被拒"会被当成"会话过期"：
    //   用户被踢回登录页、重新登录、再次撞上同一个 403 —— 问题是权限，症状却像登录坏了。
    const body = await r.text()
    throw new Error('没有权限' + (body ? '：' + body.slice(0, 120) : ''))
  }
  if (!r.ok) {
    const body = await r.text()
    throw new Error(String(r.status) + ' ' + body.slice(0, 200))
  }
  return r.json() as Promise<T>
}

export const api = {
  login: (username: string, password: string) =>
    call<IdentityView & { token: string }>('/auth/login', {
      method: 'POST', body: JSON.stringify({ username, password }),
    }),

  /** 当前身份。路由守卫与顶栏都用它，别把登录响应缓存下来当身份用。 */
  me: () => call<IdentityView>('/auth/me'),

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
  /**
   * 回答当前问题。
   *
   * ★ `patientRef` 必传：**服务端把会话按患者隔离**（真正的会话键 = ref + sessionKey）。
   * 不带它服务端会拒绝 —— 否则全租户共用一条名为 "current" 的会话，
   * 两个人会串进同一场问诊里（而且采集到的事实会记到别人身上）。
   */
  answer: (patientRef: string, sessionKey: string, value: string) =>
    call<ConsultState>('/consult/answer', {
      method: 'POST', body: JSON.stringify({ patientRef, sessionKey, value }) }),
  /** 结束问诊：产出摘要并**落一次就诊**到业务库（后台时间轴与统计的真实来源）。 */
  finishConsult: (patientRef: string, sessionKey: string) =>
    call<{ ok: boolean; triage: Triage | null; coverage: Record<string, unknown> }>(
      '/consult/finish', { method: 'POST', body: JSON.stringify({ patientRef, sessionKey }) }),

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

  /** 单次就诊的**结构化详情**（点开历史记录时用）。ref 必填且服务端会校验归属。 */
  patientEncounter: (ref: string, id: string) =>
    call<EncounterDetail>(
      '/patient/encounter/' + encodeURIComponent(id) + '?ref=' + encodeURIComponent(ref)),

  /** 后台：真实患者列表（含最新就诊 / 分诊 / 次数）。 */
  adminPatients: () =>
    call<{ ref: string; name: string; allergy: string; chronic: string; encounterCount: number; last: string; dept: string; urgency: string }[]>(
      '/admin/patients'),

  /**
   * 后台：审计（append-only，最近 200 条）。
   *
   * `inputSnapshot` 是这次操作的输入快照 —— 问答（`ask`）时里面就是**患者问题的原文**，
   * 读类事件里是 `ref=… / encounterId=…`。它超过 300 字会被截断，`inputTruncated` 标明
   * 「这是截断后的」，别把省略号当成用户输入的原文。
   */
  adminAudit: () =>
    call<{
      id: number
      time: string
      ref: string
      actor: string
      action: string
      evidenceCount: number
      modelVersion: string
      refused: boolean
      inputSnapshot: string
      inputTruncated: boolean
    }[]>('/admin/audit'),

  /**
   * 后台：资料库（**业务库是权威**，认知侧只放一份"当前生效资料"的检索副本）。
   *
   * ★ `tags` 是**相关性闸门的命中面**（问题词元必须落在标题或标签上才算依据），
   * 所以它是必填的语义字段，不是装饰。`sync.synced=false` 表示"库里改了、检索侧没生效"，
   * 界面必须如实显示这种漂移 —— 否则会以为改了资料就有用。
   */
  adminKb: () =>
    call<{ docs: KbDoc[]; active: number; inactive: number; note: string }>('/admin/kb'),

  adminKbSave: (body: {
    docKey?: string; title: string; content: string; tags: string
    department?: string; version?: string; reviewer?: string
  }) => call<{ doc: KbDoc; sync: KbSync }>('/admin/kb', {
    method: 'POST', body: JSON.stringify(body),
  }),

  adminKbStatus: (docKey: string, active: boolean) =>
    call<{ doc: KbDoc; sync: KbSync }>(
      '/admin/kb/' + encodeURIComponent(docKey) + '/status',
      { method: 'POST', body: JSON.stringify({ active }) }),

  adminKbDelete: (docKey: string) =>
    call<{ deleted: boolean; sync: KbSync }>(
      '/admin/kb/' + encodeURIComponent(docKey), { method: 'DELETE' }),

  adminKbSync: () => call<KbSync>('/admin/kb/sync', { method: 'POST' }),

  /**
   * 后台：运营统计。
   *
   * ★ 字段名带今日/累计后缀，**故意不再用含糊的 `consultToday` / `refusalRate`** ——
   * 旧字段名让同一个面板里"今日"和"全时段"两种口径看起来一样，读代码的人必然误判。
   * 口径定义随响应返回（`definitions`），界面直接展示，避免前端各写一版说明。
   */
  adminStats: () =>
    call<{
      zone: string
      windowFrom: string
      windowTo: string
      consultationsToday: number
      consultationsFinishedToday: number
      redFlagsToday: number
      questionsToday: number
      refusalsToday: number
      refusalRateToday: number
      consultationsTotal: number
      redFlagsTotal: number
      questionsTotal: number
      refusalRateTotal: number
      feedbackTotal: number
      adoptionRateTotal: number
      definitions: Record<string, string>
    }>('/admin/stats'),

  /**
   * 后台：对接设置（读）。
   *
   * ★ `desired` 是**机构期望值**（业务层持久化）；`applied` 是**认知服务进程实际生效**的配置。
   * 二者不一致时 `drift=true` —— 此时界面上的配置并没有真正生效，必须显式提示，
   * 不能让医生以为"选了 ollama"就真的在用本地模型。`drift=null` 表示认知服务不可达（未知，不猜）。
   */
  adminConfig: () => call<AdminConfigView>('/admin/config'),

  /** 后台：保存对接设置（幂等 upsert + 服务端白名单校验 + 留审计）。 */
  saveAdminConfig: (cfg: DesiredConfig) =>
    call<AdminConfigView>('/admin/config', {
      method: 'POST', body: JSON.stringify(cfg),
    }),
}

export interface EncounterDetail {
  id: string
  ref: string
  time: string
  chiefComplaint: string
  assessment: string
  plan: string
  department: string
  urgency: string
  summary: string
  requiresPhysicianConfirmation?: boolean
}

export interface DesiredConfig {
  ocr: string
  lis: string
  llm: string
  model: string
  baseUrl: string
}

/** 认知服务进程**实际生效**的 LLM 配置（api_key 由服务端打码，绝不回显明文）。 */
export interface AppliedConfig {
  provider?: string
  model?: string
  base_url?: string
  api_key?: string
  source?: string
  provider_known?: boolean
}

export interface AdminConfigView {
  desired: DesiredConfig
  updatedAt: string
  updatedBy: string
  applied: AppliedConfig | null
  /** true=期望与实际不一致；false=一致；null=认知服务不可达，无从判断 */
  drift: boolean | null
  note?: string
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
