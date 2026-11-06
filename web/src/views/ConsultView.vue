<template>
  <div class="workbench">
    <!-- ══ 左栏：患者信息 + 历史问询（每次问询 = 一次对话） ══ -->
    <aside class="left">
      <div class="patient">
        <div class="avatar">{{ initial }}</div>
        <div class="pinfo">
          <div class="pname">{{ patient.name }}</div>
          <div class="pmeta">{{ patient.sex }} · {{ patient.age }}岁</div>
          <div class="pmeta">编号 {{ patient.ref }}</div>
        </div>
      </div>
      <div class="pflags">
        <span v-if="patient.allergy" class="flag danger">过敏：{{ patient.allergy }}</span>
        <span v-if="patient.chronic" class="flag warn">慢病：{{ patient.chronic }}</span>
      </div>

      <div class="section-title">历史问询</div>
      <div class="history">
        <button
          v-for="h in history" :key="h.id"
          class="hist" :class="{ active: activeId === h.id }"
          @click="openHistory(h)"
        >
          <span class="hist-time">{{ h.time }}<em v-if="h.urgency === 'emergency'" class="hist-emg">紧急</em></span>
          <span class="hist-title">{{ h.title }}</span>
          <span class="hist-sub">{{ h.summary }}</span>
        </button>
        <p v-if="!history.length" class="empty">暂无历史记录</p>
      </div>
    </aside>

    <!-- ══ 中栏：聊天 + 上传 + 追问 ══ -->
    <section class="center">
      <div v-if="readonly" class="ro-bar">
        正在查看历史记录 · {{ activeTitle }} · <b>只读</b>
        <span class="ro-note">（只读时不能发送，避免误发成新问诊）</span>
        <button @click="exitHistory">退出只读，开始新问询</button>
      </div>

      <!-- 转人工：AI 不是万能的，必须有一条"找真人"的出口 -->
      <div v-if="!readonly" class="handoff-bar" :class="{ live: !!humanSession }">
        <template v-if="!humanSession">
          <span class="hb-hint">需要真人医生？</span>
          <select v-model="chosenDept">
            <option value="" disabled>选择科室</option>
            <option v-for="d in deptOptions" :key="d" :value="d">{{ d }}</option>
          </select>
          <button class="ghost" :disabled="!chosenDept || handoffBusy" @click="handoff()">
            {{ handoffBusy ? '转接中…' : '转人工' }}
          </button>
        </template>
        <template v-else>
          <span class="hb-live">
            {{ humanSession.status === 'WAITING'
              ? '已转接「' + humanSession.department + '」，等待医生接诊…'
              : '医生 ' + (humanSession.assignedName || '') + ' 正在为您服务' }}
          </span>
          <span class="hb-dept">{{ humanSession.department }}</span>
        </template>
        <span v-if="handoffErr" class="hb-err">{{ handoffErr }}</span>
      </div>

      <div ref="msgBox" class="messages">
        <div v-for="(m, i) in messages" :key="m.chatId ?? i" class="msg" :class="m.role">
          <div class="bubble">{{ m.text }}</div>
          <div v-if="m.role === 'doctor' || m.role === 'system'" class="mname">
            {{ m.senderName || (m.role === 'doctor' ? '医生' : '系统') }}
          </div>

          <!-- 历史就诊的结构化详情（字段各自独立，而不是拼成一句话） -->
          <div v-if="m.encounter" class="enc">
            <div class="enc-h">
              <span class="badge" :class="m.encounter.urgency">{{ urgencyOf(m.encounter.urgency) }}</span>
              <span class="enc-t">{{ m.encounter.time }}</span>
              <span v-if="m.encounter.department" class="enc-d">{{ m.encounter.department }}</span>
              <span class="enc-id">#{{ m.encounter.id }}</span>
            </div>
            <dl class="enc-fields">
              <template v-if="m.encounter.chiefComplaint"><dt>主诉</dt><dd>{{ m.encounter.chiefComplaint }}</dd></template>
              <template v-if="m.encounter.assessment"><dt>判断</dt><dd>{{ m.encounter.assessment }}</dd></template>
              <template v-if="m.encounter.plan"><dt>处置</dt><dd>{{ m.encounter.plan }}</dd></template>
            </dl>
            <p v-if="!m.encounter.chiefComplaint && !m.encounter.assessment && !m.encounter.plan"
               class="enc-empty">
              这次就诊没有留下结构化的主诉/判断/处置字段（可能早于结构化落库）。
            </p>
            <details v-if="m.encounter.summary" class="enc-sum">
              <summary>原始摘要（可审计）</summary>
              <div class="enc-sum-body">{{ m.encounter.summary }}</div>
            </details>
          </div>

          <div v-if="m.refused" class="refused">
            未找到可支撑该问题的资料，已如实说明（未编造）
            <span v-if="m.refusalReason" class="refused-why">（判据：{{ m.refusalReason }}）</span>
          </div>

          <div v-if="m.disclaimer" class="disc">{{ m.disclaimer }}</div>

          <details v-if="m.evidence?.length" class="evidence">
            <summary>依据 {{ m.evidence.length }} 条</summary>
            <ul>
              <li v-for="(s, j) in m.evidence" :key="j">
                <b>{{ s.title }}</b><span v-if="s.brief"> — {{ s.brief }}</span>
                <em v-if="s.tags?.length"> [{{ s.tags.join(' / ') }}]</em>
              </li>
            </ul>
          </details>

          <div v-if="m.role === 'assistant' && !m.resolved" class="actions">
            <button @click="resolve(m, 'praise', 'adopt')">采纳</button>
            <button @click="resolve(m, 'poke', 'modify')">修改</button>
            <button @click="resolve(m, 'scold', 'reject')">否决</button>
          </div>
          <div v-else-if="m.resolved" class="resolved">已处置：{{ m.resolved }}</div>
        </div>
      </div>

      <!-- 追问区：问题是**规则决定的**，why 可展开看依据 -->
      <div v-if="pending && !humanSession" class="ask">
        <div class="ask-q">
          {{ pending.text }}
          <details class="why"><summary>为什么问这个</summary>{{ pending.why }}</details>
        </div>
        <div class="ask-row">
          <input v-model="answerDraft" placeholder="回答…（不清楚可写「不清楚」）"
                 @keyup.enter="submitAnswer()" />
          <button class="ghost" @click="submitAnswer('不清楚')">不清楚</button>
        </div>
        <div class="cov">
          问诊完整度 {{ state?.coverage.answered }}/{{ state?.coverage.total }}
          （{{ Math.round((state?.coverage.ratio || 0) * 100) }}%）
        </div>
      </div>

      <div class="composer">
        <label class="up" :class="{ off: readonly }" title="上传检验单 / 病历图片">
          ＋<input type="file" accept="image/*,.pdf" hidden :disabled="readonly" @change="onFile" />
        </label>
        <textarea v-model="draft" :disabled="readonly"
                  :placeholder="readonly ? '只读模式：先点上方「退出只读」再输入'
                                         : humanSession ? '给医生发消息…（Ctrl+Enter 发送）'
                                         : '描述症状，或直接提问（Ctrl+Enter 发送）'"
                  @keydown.ctrl.enter="send" />
        <button class="primary" :disabled="readonly || busy || !draft.trim()" @click="send">
          {{ busy ? '处理中…' : '发送' }}
        </button>
        <button class="ghost" :disabled="readonly || busy || !draft.trim()"
                title="只依据院内已审核资料作答，不替代医师诊断"
                @click="askAI">查资料</button>
        <button v-if="canFinish" class="ghost" :disabled="busy" title="把本次问询归档为一条就诊记录"
                @click="finishConsult">结束问诊</button>
      </div>
    </section>

    <!-- ══ 右栏：病情分析 ══ -->
    <aside class="right">
      <div class="section-title">病情分析</div>

      <!-- 危险信号：最高优先级，视觉上必须最突出 -->
      <div v-if="redFlags.length" class="danger-box">
        <div class="danger-h">⚠ 发现危险信号，请立即就医</div>
        <div v-for="(f, i) in redFlags" :key="i" class="danger-i">{{ f.label }}</div>
        <div class="danger-a">{{ redFlags[0].advice }}</div>
      </div>

      <div v-if="triage" class="card">
        <div class="card-h">建议就诊</div>
        <div class="triage">
          <span class="badge" :class="triage.urgency">{{ urgencyText }}</span>
          <span>{{ triage.suggested_department || '全科门诊' }}</span>
        </div>
        <p class="note">{{ triage.advice }}</p>
        <div v-if="!triageResolved" class="actions">
          <button @click="resolveTriage('adopt')">采纳</button>
          <button @click="resolveTriage('modify')">修改</button>
          <button @click="resolveTriage('reject')">否决</button>
        </div>
        <div v-else class="resolved">已处置：{{ triageResolved }}</div>
      </div>

      <div v-if="labItems.length" class="card">
        <div class="card-h">
          检验指标解读
          <span v-if="labNeedsConfirm" class="pending">待确认</span>
        </div>
        <table class="lab">
          <tr v-for="it in labItems" :key="it.name" :class="rowClass(it)">
            <td class="lab-n">{{ it.unrecognized ? it.raw_name : it.name }}</td>
            <td class="lab-v">{{ it.value }} <em>{{ it.unit }}</em></td>
            <td class="lab-f">{{ flagText(it) }}</td>
          </tr>
        </table>
        <p v-if="labNeedsConfirm" class="warn-note">
          识别结果需你核对后才写入病历 —— 数值识别错误会造成实际风险。
        </p>
        <button v-if="labNeedsConfirm" class="primary small" @click="confirmLab">
          核对无误，写入病历
        </button>
      </div>

      <div v-if="missing.length" class="card">
        <div class="card-h">还需补充</div>
        <ul class="missing">
          <li v-for="m in missing" :key="m">{{ m }}</li>
        </ul>
      </div>

      <p class="disclaimer">
        本页内容由辅助系统基于已记录资料生成，<b>不构成诊断意见</b>，
        请以医师面诊判断为准。
      </p>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, type ChatSession, type ConsultState, type EncounterDetail, type Evidence, type LabItem, type Triage } from '../api'
import { subscribeChat } from '../chatStream'

interface Msg {
  /** doctor / system 来自人工坐诊会话（与 AI 消息同列渲染，保持时间顺序）。 */
  role: 'user' | 'assistant' | 'doctor' | 'system'
  text: string
  /** 人工会话消息 id —— 轮询去重用，避免同一条消息重复出现。 */
  chatId?: number
  senderName?: string
  evidence?: Evidence[]
  refused?: boolean
  /** 拒答原因（后端给的判据，如「无可追溯来源」）。如实显示，不软化。 */
  refusalReason?: string
  /** 资料问答的免责声明（由后端下发，前端不硬编码措辞）。 */
  disclaimer?: string
  resolved?: string
  /** 问题消息携带的 key —— 反馈时拼成 ask:<key> 写进医学动作后验（P0.5）。 */
  qkey?: string
  /** 历史就诊的结构化详情（点开历史记录时拉取）。 */
  encounter?: EncounterDetail
}

interface PatientProfile { ref: string; name: string | null; sex: string | null; age: number | null; allergy: string | null; chronic: string | null }
const patient = ref<PatientProfile>({
  ref: 'demo-patient-001', name: null, sex: null, age: null, allergy: null, chronic: null,
})
const initial = computed(() => (patient.value.name || patient.value.ref).slice(0, 1))

const messages = ref<Msg[]>([
  { role: 'assistant', text: '你好，我是预问诊助手。请先告诉我这次主要哪里不舒服？' },
])
const draft = ref('')
const answerDraft = ref('')
const busy = ref(false)
const readonly = ref(false)
const activeId = ref('')
const activeTitle = ref('')
const msgBox = ref<HTMLElement | null>(null)

const sessionKey = 'current'
const state = ref<ConsultState | null>(null)
const labItems = ref<LabItem[]>([])
const labKey = ref('')
const labNeedsConfirm = ref(false)

const pending = computed(() => (state.value?.halted ? null : state.value?.question ?? null))
/** 危险信号 = 问诊命中 + 检验危急值。检验那条来自本地（后端尚未并回 state），
 *  所以用一个独立 ref 收集，避免往 computed 上 push（那是绕不过类型检查的错误用法）。 */
const labFlags = ref<{ label: string; advice: string; urgency: string }[]>([])
const redFlags = computed(() => [...(state.value?.red_flags ?? []), ...labFlags.value])
const triage = computed<Triage | null>(() => state.value?.triage ?? null)
const missing = computed(() =>
  state.value ? (state.value.coverage.total - state.value.coverage.answered > 0
    ? ['完整度未满，请继续回答上方问题'] : []) : [])
const urgencyText = computed(() => {
  const u = triage.value?.urgency
  return u === 'emergency' ? '紧急' : u === 'urgent' ? '尽快' : '常规'
})

/** 有进行中的问诊且非只读、未转人工时，才显示「结束问诊」入口（转人工后由医生结束并写病历）。 */
const canFinish = computed(() => !!state.value && !readonly.value && !humanSession.value)

// ── 人工坐诊（转人工）───────────────────────────────────────────────
//  ★ AI 不是万能的：必须有"找真人"的出口，且转接后消息要与 AI 消息同列显示，
//    不能开一个新窗口把上下文丢掉（医生看不到患者已经说过什么，等于重新问一遍）。
const humanSession = ref<ChatSession | null>(null)
const deptOptions = ref<string[]>([])
const chosenDept = ref('')
const handoffBusy = ref(false)
const handoffErr = ref('')
/** 已渲染过的人工消息 id（去重）。 */
const chatSeen = new Set<number>()
let chatTimer: number | undefined
let chatUnsub: (() => void) | null = null

/** 把 AI 预问诊的关键问答带给医生（截断，避免把整段会话塞进一条消息）。 */
function buildTranscript(): string {
  return messages.value
    .filter((m) => !m.chatId && (m.role === 'user' || m.role === 'assistant'))
    .slice(-10)
    .map((m) => (m.role === 'user' ? '患者：' : '助手：') + m.text.replace(/\s+/g, ' ').slice(0, 160))
    .join('；')
}

/** 订阅该会话的 SSE：先补发历史，再实时接收医生消息与状态变化。 */
function startChatStream(afterId = 0) {
  stopChatStream()
  const s = humanSession.value
  if (!s) return
  chatUnsub = subscribeChat(s.id, afterId, {
    onMessage: (m) => { pushChatMsg(m); void scrollDown() },
    onSession: (next) => {
      humanSession.value = next.status === 'CLOSED' ? null : next
      if (next.status === 'CLOSED') stopChatStream()
    },
    onError: () => { handoffErr.value = '实时连接中断，正在重连…' },
    onOpen: () => { handoffErr.value = '' },
  })
}

function stopChatStream() {
  if (chatUnsub) {
    chatUnsub()
    chatUnsub = null
  }
}

/** 恢复现场：有进行中会话就订阅；没有就清空。 */
async function syncSession() {
  try {
    const s = await api.chatMy()
    if (!s) {
      humanSession.value = null
      stopChatStream()
      return
    }
    humanSession.value = s
    if (!chatUnsub) startChatStream(0)
  } catch {
    /* 轮询失败不打断问诊：SSE 仍可能在推 */
  }
}

async function loadDeptOptions() {
  try {
    deptOptions.value = await api.departmentsPublic()
    if (!chosenDept.value && deptOptions.value.length) {
      chosenDept.value = deptOptions.value[0]
    }
  } catch {
    deptOptions.value = []
  }
}

function pushChatMsg(m: { id: number; sender: string; senderName: string; body: string }) {
  if (chatSeen.has(m.id)) return
  chatSeen.add(m.id)
  const role: Msg['role'] = m.sender === 'DOCTOR' ? 'doctor'
    : m.sender === 'SYSTEM' ? 'system' : 'user'
  messages.value.push({ role, text: m.body, chatId: m.id, senderName: m.senderName })
}

/** 转人工。`auto=true` 表示红旗中断时自动转接（科室取分诊建议的第一段）。 */
async function handoff(auto = false) {
  if (handoffBusy.value || humanSession.value) return
  const dept = auto
    ? normalizeDept(state.value?.triage?.suggested_department) || chosenDept.value
    : chosenDept.value
  if (!dept) {
    if (!auto) handoffErr.value = '请选择科室'
    return
  }
  handoffBusy.value = true
  handoffErr.value = ''
  try {
    const chief = state.value?.chiefComplaint || lastUserText()
    const s = await api.chatHandoff({
      patientRef: patient.value.ref,
      department: dept,
      chiefComplaint: chief,
      urgency: state.value?.triage?.urgency || 'routine',
      transcript: buildTranscript(),
    })
    humanSession.value = s
    chosenDept.value = s.department
    startChatStream(0)
  } catch (e) {
    handoffErr.value = e instanceof Error ? e.message : String(e)
  } finally {
    handoffBusy.value = false
  }
}

function normalizeDept(raw?: string): string {
  if (!raw) return ''
  const first = raw.split(/\s*\/\s*|、|,/)[0].trim()
  if (deptOptions.value.includes(first)) return first
  return deptOptions.value.includes(raw.trim()) ? raw.trim() : ''
}

function lastUserText(): string {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'user' && !m.chatId) return m.text.slice(0, 200)
  }
  return ''
}

/** 恢复现场 + 轮询。2s 增量拉取：既有"即时"体感，也不会重复渲染。 */
async function pollChat(reset = false) {
  try {
    const s = await api.chatMy()
    if (!s) {
      humanSession.value = null
      return
    }
    humanSession.value = s
    const last = reset ? 0 : Math.max(0, ...Array.from(chatSeen))
    const rows = await api.chatMessages(s.id, last)
    if (rows.length) {
      rows.forEach(pushChatMsg)
      await scrollDown()
    }
  } catch {
    /* 轮询失败不打断问诊：下一轮会重试 */
  }
}

const history = ref<{ id: string; time: string; title: string; summary: string; urgency?: string }[]>([])

/** 左栏患者档案 + 历史就诊：全部来自业务层真实接口（dev 档由 DemoDataSeeder 灌演示数据）。 */
async function loadPatient() {
  try {
    const p = await api.patient(patient.value.ref)
    if (p) patient.value = { ...patient.value, ...p }
  } catch {
    /* 接口不可用时保留左侧默认骨架，不阻断主流程 */
  }
  try {
    history.value = await api.patientEncounters(patient.value.ref)
  } catch {
    history.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadPatient(), loadDeptOptions()])
  await syncSession()
  // 消息走 SSE 实时推送；10s 兜底轮询只补「SSE 不可用 / 断线重连期间」的缺口。
  chatTimer = window.setInterval(() => {
    syncSession()
    if (humanSession.value) pollChat()
  }, 10000)
})

onBeforeUnmount(() => {
  if (chatTimer) window.clearInterval(chatTimer)
  stopChatStream()
})

async function scrollDown() {
  await nextTick()
  if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
}

/** 描述症状 → 自动开一次问诊（主诉识别不出时后端会返回可选列表） */
async function send() {
  // ★ 只读态必须在这里也挡一道：禁用的按钮挡不住 Enter 键提交
  if (readonly.value) return
  const q = draft.value.trim()
  if (!q || busy.value) return

  // ★ 已转人工：消息进人工会话（医生端轮询可见），不再走 AI 问诊树 ——
  //   两条链路同时回答会让患者收到互相矛盾的两套说法，也会污染问诊采集。
  if (humanSession.value && humanSession.value.status !== 'CLOSED') {
    draft.value = ''
    busy.value = true
    try {
      const m = await api.chatSend(humanSession.value.id, q)
      pushChatMsg(m)
      await scrollDown()
    } catch (e) {
      handoffErr.value = e instanceof Error ? e.message : String(e)
      draft.value = q
    } finally {
      busy.value = false
    }
    return
  }

  draft.value = ''
  messages.value.push({ role: 'user', text: q })
  await scrollDown()
  busy.value = true
  try {
    if (!state.value) {
      const st = await api.startConsult(patient.value.ref, q)
      applyState(st)
      if (!st.question && !st.halted) {
        messages.value.push({ role: 'assistant', text: '没能识别出主诉，请从列表中选择或描述得更具体。' })
      }
    } else {
      const st = await api.answer(patient.value.ref, sessionKey, q)
      applyState(st)
    }
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '调用失败：' + (e instanceof Error ? e.message : e) })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

/**
 * 查院内资料（**带依据**的问答）。
 *
 * ★ 与「发送」是两条独立链路，别合并：
 *   · 发送 → 问诊树（采集病史，问题由规则决定）；
 *   · 查资料 → /api/assist/ask（按**已审核资料**回答「就医指征 / 用药安全」这类问题）。
 *   合并会互相污染：问诊树会把自由提问当成"回答当前问题"，问答又会跳过病史采集。
 *
 * ★ 拒答必须原样显示：后端 refused=true 时 text 就是拒答话术，不许软化、
 *   也不许用「可能」「建议咨询」把它包装成一句答案。
 */
async function askAI(): Promise<void> {
  if (readonly.value) return
  const q = draft.value.trim()
  if (!q || busy.value) return
  draft.value = ''
  messages.value.push({ role: 'user', text: q })
  await scrollDown()
  busy.value = true
  try {
    const r = await api.ask(patient.value.ref, q)
    const body = (r.message || '').trim()
    messages.value.push({
      role: 'assistant',
      // 兜底：后端没给正文时**不能渲染空气泡**（患者会以为系统坏了）。
      // 但也不许自己编一句话补上 —— 只如实说明没拿到正文。
      text: body || '（未获取到正文，请换个问法，或直接咨询医师）',
      evidence: r.sources || r.evidence?.hits || [],
      refused: r.refused,
      refusalReason: r.refused ? (r.refusalReason || '') : '',
      disclaimer: r.refused ? '' : (r.disclaimer || ''),
    })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '查资料失败：' + (e instanceof Error ? e.message : e) })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

function applyState(st: ConsultState) {
  state.value = st
  // 分诊建议科室可作为转人工的默认值（复合值取第一段，且必须在启用科室里）
  if (st.triage?.suggested_department && !humanSession.value) {
    const d = normalizeDept(st.triage.suggested_department)
    if (d) chosenDept.value = d
  }
  if (st.halted && st.triage) {
    messages.value.push({
      role: 'assistant',
      text: '发现需要立即处理的警示信号，问诊已中止。' + st.triage.advice,
    })
    // 红旗中断 = 必须有人接住：自动转人工（科室取分诊建议），别让患者停在"建议就医"四个字上。
    if (!humanSession.value) handoff(true)
  } else if (st.question) {
    messages.value.push({ role: 'assistant', text: st.question.text, qkey: st.question.key })
  }
}

async function submitAnswer(v?: string) {
  if (readonly.value || state.value?.halted) return
  const val = (typeof v === 'string' ? v : answerDraft.value).trim()
  if (!val) return
  answerDraft.value = ''
  messages.value.push({ role: 'user', text: val })
  await scrollDown()
  busy.value = true
  try {
    const st = await api.answer(patient.value.ref, sessionKey, val)
    applyState(st)
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '调用失败：' + (e instanceof Error ? e.message : e) })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

/** 上传检验单：先识别，结果**待确认**才写入 */
async function onFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const f = input.files?.[0]
  // 只读态不接受上传（历史记录不该被追加检验单）
  if (readonly.value) {
    input.value = ''
    return
  }
  if (!f) return
  messages.value.push({ role: 'user', text: '［上传］' + f.name })
  await scrollDown()
  busy.value = true
  try {
    const rep = await api.parseLab(patient.value.ref, f.name)
    labItems.value = rep.items
    labKey.value = rep.report_key
    labNeedsConfirm.value = rep.needs_confirmation
    messages.value.push({
      role: 'assistant',
      text: '已识别 ' + rep.items.length + ' 项，请在右侧核对。' +
        (rep.critical.forced ? '⚠ 发现危急值：' + rep.critical.advice : ''),
    })
    if (rep.critical.forced) {
      labFlags.value.push({ label: '检验危急值', advice: rep.critical.advice, urgency: 'emergency' })
    }
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '识别失败：' + (e instanceof Error ? e.message : e) })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

async function confirmLab() {
  try {
    const r = await api.confirmLab(labKey.value, {})
    labNeedsConfirm.value = false
    messages.value.push({ role: 'assistant', text: '已写入病历（' + r.written + ' 项）。' })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '确认失败：' + (e instanceof Error ? e.message : e) })
  }
}

/**
 * 结束问诊：调 /consult/finish 把本次问询**落一条 Encounter 到业务库**
 * （左栏历史与后台时间轴的真实数据源，而非前端硬编码）。
 *
 * ★ 落库后把 state 置空：Python 侧 finish 已 pop 掉会话，
 *   下一次描述症状会经 send() → startConsult 开启**全新**问诊，不会接上旧会话。
 *   刚归档的问询通过 loadPatient() 立即出现在左栏历史里。
 */
async function finishConsult() {
  if (!state.value || readonly.value || busy.value) return
  busy.value = true
  try {
    const r = await api.finishConsult(patient.value.ref, sessionKey)
    state.value = null
    await loadPatient()
    messages.value.push({
      role: 'assistant',
      text: '本次问询已结束并归档。' + (r.triage ? '分诊建议：' + r.triage.suggested_department : ''),
    })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '归档失败：' + (e instanceof Error ? e.message : e) })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

/** 处境 context（P0.5）：complaint=主诉;age_band=儿童/成年/老年/未知;redflag=有/无 */
function ageBand(): string {
  const a = patient.value.age
  if (a == null) return '未知'
  if (a < 18) return '儿童'
  if (a >= 65) return '老年'
  return '成年'
}
function feedbackContext(): string {
  const cc = state.value?.chiefComplaint || ''
  const redflag = redFlags.value.length > 0 ? '有' : '无'
  return 'complaint=' + cc + ';age_band=' + ageBand() + ';redflag=' + redflag
}

/** 采纳 / 修改 / 否决 —— P0.5 改接到医学动作后验（ask:<问题key>）。 */
async function resolve(m: Msg, kind: string, decision: string) {
  m.resolved = decision
  // 问题消息 → ask:<key>；其它 AI 消息没具体建议 → 不更新后验，仅留审计
  const medicalAction = m.qkey ? 'ask:' + m.qkey : ''
  try {
    await api.feedback(patient.value.ref, decision, medicalAction, feedbackContext())
  } catch {
    m.resolved = decision + '（上报失败，请重试）'
  }
}

/** 分诊建议卡片的采纳 / 修改 / 否决 —— P0.5 核心：medicalAction = triage:<科室>。 */
const triageResolved = ref<string>('')
async function resolveTriage(decision: string) {
  triageResolved.value = decision
  const dept = triage.value?.suggested_department || '全科门诊'
  try {
    await api.feedback(patient.value.ref, decision, 'triage:' + dept, feedbackContext())
  } catch {
    triageResolved.value = decision + '（上报失败，请重试）'
  }
}

/**
 * 点开一次历史问询。
 *
 * ★ 之前这里只把 `title + summary` 拼成一句话显示 —— 数据是真的（列表接口给的就是这些），
 *   但医生看不到**字段各自的值**，等于"点开了却读不到病历"。现在改为拉
 *   `GET /api/patient/encounter/{id}`，把主诉 / 判断 / 处置 / 科室 / 分诊 / 时间分开呈现。
 *   ★ ref 一起传：服务端会校验这次就诊是否属于该患者（不校验就是 IDOR）。
 */
async function openHistory(h: { id: string; time: string; title: string; summary: string }) {
  activeId.value = h.id
  activeTitle.value = h.title
  readonly.value = true
  state.value = null
  // 历史记录是只读快照：把"当前这次问诊"的右栏内容清掉，避免看起来像是它的分析结果
  labItems.value = []
  labFlags.value = []
  labNeedsConfirm.value = false
  draft.value = ''
  answerDraft.value = ''
  messages.value = [{ role: 'assistant', text: '正在读取这次就诊的结构化记录…', resolved: '历史' }]
  try {
    const d = await api.patientEncounter(patient.value.ref, h.id)
    messages.value = [{ role: 'assistant', text: '历史记录 · ' + (d.chiefComplaint || h.title),
                        encounter: d, resolved: '历史' }]
  } catch (e) {
    // 详情取不到时**如实说明**，并回落到列表里已有的摘要（不假装详情加载成功了）
    messages.value = [{
      role: 'assistant',
      text: '（历史记录）' + h.title + '：' + h.summary
        + '\n\n（结构化详情读取失败：' + (e instanceof Error ? e.message : e) + '）',
      resolved: '历史',
    }]
  }
}

/**
 * 退出只读。
 *
 * ★ 不叫"继续这段对话"：Python 侧的问诊会话是**进程内**的（`_consults` 字典），
 *   历史记录对应的会话早已结束/丢失，无法真的恢复。写"继续"会让人以为
 *   接上了旧会话 —— 那正是界面说谎的典型。所以只如实说"可以开始新问询"。
 */
function exitHistory() {
  readonly.value = false
  activeId.value = ''
  activeTitle.value = ''
  messages.value.push({
    role: 'assistant',
    text: '已退出历史记录（只读）。历史问诊不会被恢复成进行中的会话 —— 发送新的描述会开启一次新的问询。',
  })
}

function urgencyOf(u: string) {
  return u === 'emergency' ? '紧急' : u === 'urgent' ? '尽快' : '常规'
}

function flagText(it: LabItem) {
  if (it.critical.length) return '危急'
  if (it.flag.startsWith('high')) return '偏高'
  if (it.flag.startsWith('low')) return '偏低'
  if (it.flag === 'unknown') return '未识别'
  return '正常'
}
function rowClass(it: LabItem) {
  if (it.critical.length) return 'crit'
  if (it.flag.startsWith('high') || it.flag.startsWith('low')) return 'abn'
  if (it.flag === 'unknown') return 'unk'
  return ''
}
</script>

<style scoped>
.workbench { display: grid; grid-template-columns: 260px 1fr 320px; height: 100%; }

/* 左栏 */
.left { background: var(--surface); border-right: 1px solid var(--line); overflow-y: auto; padding: 16px; }
.patient { display: flex; gap: 10px; align-items: center; }
.avatar {
  width: 42px; height: 42px; border-radius: 50%; background: var(--primary);
  color: #fff; display: grid; place-items: center; font-size: 17px; flex: 0 0 42px;
}
.pname { font-weight: 500; }
.pmeta { font-size: 12px; color: var(--text-2); }
.pflags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.flag { font-size: 12px; padding: 2px 7px; border-radius: 5px; }
.flag.danger { background: var(--danger-bg); color: var(--danger-fg); }
.flag.warn { background: var(--warn-bg); color: var(--warn-fg); }
.section-title { font-size: 12px; color: var(--text-3); margin: 18px 0 8px; }
.hist {
  display: block; width: 100%; text-align: left; padding: 8px 10px; margin-bottom: 6px;
  border: 1px solid var(--line); border-radius: 8px; background: #fff;
}
.hist:hover { border-color: var(--line-strong); }
.hist.active { border-color: var(--primary); background: #f3f8fa; }
.hist-time { font-size: 11px; color: var(--text-3); }
.hist-emg {
  font-style: normal; font-size: 10px; margin-left: 6px; padding: 0 5px;
  border-radius: 4px; background: var(--danger-bg); color: var(--danger-fg);
}
.hist-title { display: block; font-size: 13px; }
.hist-sub { display: block; font-size: 12px; color: var(--text-2); }
.empty { color: var(--text-3); font-size: 13px; }

/* 中栏 */
.center { display: flex; flex-direction: column; min-width: 0; }
.ro-bar {
  display: flex; align-items: center; gap: 12px; padding: 7px 16px;
  background: #f2f4f7; color: var(--text-2); font-size: 12px;
  border-bottom: 1px solid var(--line);
}
.ro-bar button { margin-left: auto; font-size: 12px; border: 1px solid var(--line-strong);
  background: #fff; border-radius: 6px; padding: 3px 10px; }
.ro-note { color: var(--text-3); }
.messages { flex: 1; overflow-y: auto; padding: 16px; }
.msg { margin-bottom: 14px; }
.msg.user { text-align: right; }
/* 人工会话：医生消息靠左但换色，系统提示居中弱化 —— 与 AI 消息可一眼区分 */
.msg.doctor .bubble { background: #eaf5ef; border-color: #cfe7da; }
.msg.system { text-align: center; }
.msg.system .bubble {
  background: transparent; border-style: dashed; color: var(--text-3); font-size: 12px;
}
.mname { margin-top: 3px; font-size: 11px; color: var(--text-3); }

/* 转人工条 */
.handoff-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 16px; border-bottom: 1px solid var(--line); background: #fafbfc;
  font-size: 12px; color: var(--text-2);
}
.handoff-bar.live { background: #eef7f2; }
.handoff-bar select {
  padding: 5px 8px; border: 1px solid var(--line-strong); border-radius: 6px; background: #fff;
}
.handoff-bar button { padding: 5px 12px; font-size: 12px; }
.hb-hint { color: var(--text-3); }
.hb-live { color: var(--ok-fg, #047857); font-weight: 500; }
.hb-dept { color: var(--text-3); }
.hb-err { color: var(--danger-fg); }
.bubble {
  display: inline-block; padding: 8px 12px; border-radius: 10px;
  background: var(--surface); border: 1px solid var(--line);
  max-width: 44em; text-align: left; white-space: pre-wrap;
}
.msg.user .bubble { background: #eef4f6; border-color: #d5e3e8; }
.refused { font-size: 12px; color: var(--danger-fg); margin-top: 4px; }
.refused-why { color: var(--text-3, var(--text-2)); }
.disc { font-size: 11px; color: var(--text-2); margin-top: 6px; line-height: 1.5; }
.evidence { margin-top: 6px; font-size: 12px; color: var(--text-2); }
.evidence ul { margin: 4px 0 0 16px; padding: 0; }
.actions { margin-top: 6px; display: flex; gap: 8px; }
.actions button {
  font-size: 12px; padding: 3px 12px; border-radius: 6px;
  border: 1px solid var(--line-strong); background: #fff;
}
.resolved { margin-top: 4px; font-size: 12px; color: var(--ok-fg); }

/* 历史就诊结构化详情 */
.enc {
  margin-top: 8px; padding: 12px 14px; border: 1px solid var(--line);
  border-radius: var(--radius); background: #fff; max-width: 44em;
}
.enc-h { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.enc-t { font-size: 12px; color: var(--text-2); }
.enc-d { font-size: 12px; color: var(--text-2); }
.enc-id { margin-left: auto; font-size: 11px; color: var(--text-3); font-family: ui-monospace, Consolas, monospace; }
.enc-fields { margin: 0; font-size: 13px; display: grid; grid-template-columns: 3.2em 1fr; column-gap: 0.4em; }
.enc-fields dt { color: var(--text-3); font-size: 12px; padding-top: 2px; }
.enc-fields dd { margin: 0 0 6px 0; line-height: 1.7; }
.enc-empty { margin: 0; font-size: 12px; color: var(--text-3); }
.enc-sum { margin-top: 8px; font-size: 12px; color: var(--text-3); }
.enc-sum-body { margin-top: 4px; color: var(--text-2); line-height: 1.8; white-space: pre-wrap; }
.ask { padding: 12px 16px; border-top: 1px solid var(--line); background: #fbfcfd; }
.ask-q { font-size: 13px; margin-bottom: 8px; }
.why { display: inline-block; margin-left: 8px; font-size: 12px; color: var(--text-3); }
.ask-row { display: flex; gap: 8px; }
.ask-row input { flex: 1; padding: 8px 10px; border: 1px solid var(--line-strong); border-radius: 8px; }
.ghost { border: 1px solid var(--line-strong); background: #fff; border-radius: 8px; padding: 0 12px; font-size: 13px; }
.cov { margin-top: 6px; font-size: 12px; color: var(--text-3); }
.composer {
  display: flex; gap: 8px; align-items: flex-end; padding: 12px 16px;
  border-top: 1px solid var(--line); background: var(--surface);
}
.up {
  width: 36px; height: 36px; border: 1px dashed var(--line-strong); border-radius: 8px;
  display: grid; place-items: center; color: var(--text-2); cursor: pointer; flex: 0 0 36px;
}
.up.off { opacity: 0.4; cursor: not-allowed; }
.composer textarea {
  flex: 1; height: 62px; resize: none; padding: 8px 10px;
  border: 1px solid var(--line-strong); border-radius: 8px; outline: none;
}
.composer textarea:disabled { background: #f4f6f8; color: var(--text-3); cursor: not-allowed; }
.primary { padding: 0 20px; height: 36px; border: 0; border-radius: 8px; background: var(--primary); color: #fff; }
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.primary.small { height: 30px; font-size: 13px; margin-top: 8px; }

/* 右栏 */
.right { background: var(--surface); border-left: 1px solid var(--line); overflow-y: auto; padding: 16px; }
.danger-box {
  background: var(--danger-bg); border: 1px solid #f0c6c6; border-radius: var(--radius);
  padding: 12px; margin-bottom: 14px;
}
.danger-h { color: var(--danger-fg); font-weight: 500; margin-bottom: 6px; }
.danger-i { font-size: 13px; color: var(--danger-fg); }
.danger-a { font-size: 12px; margin-top: 6px; color: var(--text); }
.card { border: 1px solid var(--line); border-radius: var(--radius); padding: 12px; margin-bottom: 12px; }
.card-h { font-size: 13px; font-weight: 500; margin-bottom: 8px; display: flex; justify-content: space-between; }
.pending { font-size: 12px; font-weight: 400; color: var(--warn-fg); background: var(--warn-bg); padding: 1px 7px; border-radius: 5px; }
.triage { display: flex; align-items: center; gap: 8px; }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 5px; }
.badge.emergency { background: var(--danger-bg); color: var(--danger-fg); }
.badge.urgent { background: var(--warn-bg); color: var(--warn-fg); }
.badge.routine { background: var(--ok-bg); color: var(--ok-fg); }
.note { font-size: 12px; color: var(--text-2); margin: 6px 0 0; }
.lab { width: 100%; border-collapse: collapse; font-size: 12px; }
.lab td { padding: 4px 2px; border-bottom: 1px solid var(--line); }
.lab tr.crit { background: var(--danger-bg); }
.lab tr.abn .lab-f { color: var(--warn-fg); }
.lab tr.unk .lab-f { color: var(--text-3); }
.lab-n { color: var(--text-2); }
.lab-v { text-align: right; }
.lab-v em { color: var(--text-3); font-style: normal; font-size: 11px; }
.lab-f { text-align: right; width: 52px; }
.warn-note { font-size: 12px; color: var(--warn-fg); margin: 8px 0 0; }
.missing { margin: 0; padding-left: 16px; font-size: 12px; color: var(--text-2); }
.disclaimer {
  font-size: 11px; color: var(--text-3); line-height: 1.7;
  margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--line);
}
</style>
