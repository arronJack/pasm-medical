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
          <span class="hist-time">{{ h.time }}</span>
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
        <button @click="continueFromHistory">继续这段对话</button>
      </div>

      <div ref="msgBox" class="messages">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">{{ m.text }}</div>

          <div v-if="m.refused" class="refused">
            未找到可支撑该问题的资料，已如实说明（未编造）
          </div>

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
      <div v-if="pending" class="ask">
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
        <label class="up" title="上传检验单 / 病历图片">
          ＋<input type="file" accept="image/*,.pdf" hidden @change="onFile" />
        </label>
        <textarea v-model="draft" placeholder="描述症状，或直接提问（Ctrl+Enter 发送）"
                  @keydown.ctrl.enter="send" />
        <button class="primary" :disabled="busy || !draft.trim()" @click="send">
          {{ busy ? '处理中…' : '发送' }}
        </button>
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
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, type ConsultState, type Evidence, type LabItem, type Triage } from '../api'

interface Msg {
  role: 'user' | 'assistant'
  text: string
  evidence?: Evidence[]
  refused?: boolean
  resolved?: string
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

const history = ref<{ id: string; time: string; title: string; summary: string }[]>([])

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

onMounted(loadPatient)

async function scrollDown() {
  await nextTick()
  if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
}

/** 描述症状 → 自动开一次问诊（主诉识别不出时后端会返回可选列表） */
async function send() {
  const q = draft.value.trim()
  if (!q || busy.value) return
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
      const st = await api.answer(sessionKey, q)
      applyState(st)
    }
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '调用失败：' + (e instanceof Error ? e.message : e) })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

function applyState(st: ConsultState) {
  state.value = st
  if (st.halted && st.triage) {
    messages.value.push({
      role: 'assistant',
      text: '发现需要立即处理的警示信号，问诊已中止。' + st.triage.advice,
    })
  } else if (st.question) {
    messages.value.push({ role: 'assistant', text: st.question.text })
  }
}

async function submitAnswer(v?: string) {
  if (state.value?.halted) return
  const val = (typeof v === 'string' ? v : answerDraft.value).trim()
  if (!val) return
  answerDraft.value = ''
  messages.value.push({ role: 'user', text: val })
  await scrollDown()
  busy.value = true
  try {
    const st = await api.answer(sessionKey, val)
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
  const f = (ev.target as HTMLInputElement).files?.[0]
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

/** 采纳 / 修改 / 否决 —— 必须上报后端，这是最高质量的学习信号 */
async function resolve(m: Msg, kind: string, action: string) {
  m.resolved = action
  try {
    await api.feedback(patient.value.ref, kind, action)
  } catch {
    m.resolved = action + '（上报失败，请重试）'
  }
}

function openHistory(h: { id: string; title: string; summary: string }) {
  activeId.value = h.id
  activeTitle.value = h.title
  readonly.value = true
  messages.value = [{ role: 'assistant', text: '（历史记录）' + h.title + '：' + h.summary, resolved: '历史' }]
  state.value = null
}

function continueFromHistory() {
  readonly.value = false
  activeId.value = ''
  messages.value.push({ role: 'assistant', text: '已从该次问诊继续，请补充本次情况。' })
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
.messages { flex: 1; overflow-y: auto; padding: 16px; }
.msg { margin-bottom: 14px; }
.msg.user { text-align: right; }
.bubble {
  display: inline-block; padding: 8px 12px; border-radius: 10px;
  background: var(--surface); border: 1px solid var(--line);
  max-width: 44em; text-align: left; white-space: pre-wrap;
}
.msg.user .bubble { background: #eef4f6; border-color: #d5e3e8; }
.refused { font-size: 12px; color: var(--danger-fg); margin-top: 4px; }
.evidence { margin-top: 6px; font-size: 12px; color: var(--text-2); }
.evidence ul { margin: 4px 0 0 16px; padding: 0; }
.actions { margin-top: 6px; display: flex; gap: 8px; }
.actions button {
  font-size: 12px; padding: 3px 12px; border-radius: 6px;
  border: 1px solid var(--line-strong); background: #fff;
}
.resolved { margin-top: 4px; font-size: 12px; color: var(--ok-fg); }
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
.composer textarea {
  flex: 1; height: 62px; resize: none; padding: 8px 10px;
  border: 1px solid var(--line-strong); border-radius: 8px; outline: none;
}
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
