<!--
  诊疗工作台 —— 左栏时间轴 + 右侧对话区。

  交互要点（对应 PLAN.md §9）：
   · 左栏是**就诊时间轴**，点某次就诊 → 右侧加载那次会话（**只读态**，顶部标注日期）；
   · 要有明确的「继续这段对话」→ 新建会话但保留 context 链接，避免把历史误当当前；
   · 每条 AI 输出旁必须有「采纳 / 修改 / 否决」—— 这是**最高质量的学习信号**，
     病历只记录结果，而"医生否决了这条建议"直接标出系统的错误；
   · 顶部固定「本次会话 AI 输出未确认」状态条，**未确认前不允许一键写入病历**。
-->
<template>
  <div class="workbench">
    <!-- 顶部：未确认状态条。放在固定位置而不是弹窗，是因为它必须一直可见 -->
    <header class="statusbar" :class="{ pending: hasUnconfirmed }">
      <span class="dot" />
      <span v-if="hasUnconfirmed">本次会话有 {{ unconfirmedCount }} 条 AI 输出未确认</span>
      <span v-else>本次会话 AI 输出均已处置</span>
      <span class="spacer" />
      <span class="cog" :class="{ off: !cognitionOk }">
        认知服务：{{ cognitionOk ? '在线' : '不可用（记忆功能降级）' }}
      </span>
    </header>

    <div class="body">
      <!-- ── 左栏：就诊时间轴 ───────────────────────────── -->
      <aside class="timeline">
        <h2>就诊记录</h2>
        <p v-if="!timeline.length" class="empty">暂无就诊记录</p>
        <button
          v-for="e in timeline"
          :key="e.title + e.brief"
          class="enc"
          :class="{ active: activeEncounter === e }"
          @click="openEncounter(e)"
        >
          <span class="enc-title">{{ e.title }}</span>
          <span class="enc-brief">{{ e.brief }}</span>
        </button>
      </aside>

      <!-- ── 右侧：对话区 ──────────────────────────────── -->
      <main class="chat">
        <div v-if="readonlyEncounter" class="readonly-banner">
          历史记录 · {{ readonlyEncounter.title }} · 只读
          <button @click="continueFromHistory">继续这段对话</button>
        </div>

        <div class="messages">
          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <div class="bubble">{{ m.text }}</div>

            <!-- 拒答要如实显示，不要用"可能/或许"软化 -->
            <div v-if="m.refused" class="refused">查不到可靠依据，已如实拒答（未编造）</div>

            <!-- 依据卡片：医生要能看到"系统凭哪几条说这话" -->
            <details v-if="m.evidence?.length" class="evidence">
              <summary>依据 {{ m.evidence.length }} 条</summary>
              <ul>
                <li v-for="(s, j) in m.evidence" :key="j">
                  <b>{{ s.title }}</b>
                  <span v-if="s.brief"> — {{ s.brief }}</span>
                  <em v-if="s.tags?.length"> [{{ s.tags.join(' / ') }}]</em>
                </li>
              </ul>
            </details>

            <!-- 采纳 / 修改 / 否决 —— 三个按钮就是学习信号 -->
            <div v-if="m.role === 'assistant' && !m.resolved" class="actions">
              <button @click="resolve(m, 'praise', 'adopt')">采纳</button>
              <button @click="resolve(m, 'poke', 'modify')">修改</button>
              <button @click="resolve(m, 'scold', 'reject')">否决</button>
            </div>
            <div v-else-if="m.resolved" class="resolved">已处置：{{ m.resolved }}</div>
          </div>
        </div>

        <div class="composer">
          <textarea
            v-model="draft"
            placeholder="描述症状 / 提问（Ctrl+Enter 发送）"
            @keydown.ctrl.enter="send"
          />
          <button :disabled="busy || !draft.trim()" @click="send">
            {{ busy ? '思考中…' : '发送' }}
          </button>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

/**
 * 说明：这里直接调后端 /api/assist/*，**不直连 Python 认知服务**。
 * 认知服务的令牌是管理令牌，一旦下发到浏览器，等于把"写记忆、改人格"的权限公开。
 */

const patientRef = ref('demo-patient-001')
const draft = ref('')
const busy = ref(false)
const cognitionOk = ref(true)
const readonlyEncounter = ref<any>(null)
const activeEncounter = ref<any>(null)

interface Evidence { title: string; brief?: string; tags?: string[] }
interface Msg {
  role: 'user' | 'assistant'
  text: string
  evidence?: Evidence[]
  refused?: boolean
  resolved?: string
}
const messages = ref<Msg[]>([])
const timeline = ref<any[]>([])

const hasUnconfirmed = computed(
  () => messages.value.some(m => m.role === 'assistant' && !m.resolved))
const unconfirmedCount = computed(
  () => messages.value.filter(m => m.role === 'assistant' && !m.resolved).length)

async function api(path: string, init?: RequestInit) {
  const r = await fetch(`/api/assist${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`)
  return r.json()
}

onMounted(async () => {
  try {
    const h = await api('/cognition-health')
    cognitionOk.value = !!h.available
  } catch {
    cognitionOk.value = false
  }
})

/** 打开历史就诊：**只读态**，且必须显式点「继续这段对话」才能接着聊。 */
function openEncounter(e: any) {
  readonlyEncounter.value = e
  activeEncounter.value = e
  messages.value = [{
    role: 'assistant',
    text: `（历史记录）${e.title}：${e.brief}`,
    resolved: '历史记录',
  }]
}

function continueFromHistory() {
  // 新建会话但保留引用 —— 避免把历史误当当前
  messages.value.push({
    role: 'assistant',
    text: '已从该次就诊继续。请补充本次情况。',
  })
  readonlyEncounter.value = null
}

async function send() {
  const q = draft.value.trim()
  if (!q || busy.value) return
  draft.value = ''
  messages.value.push({ role: 'user', text: q })
  busy.value = true
  try {
    const d = await api('/ask', {
      method: 'POST',
      body: JSON.stringify({ patientRef: patientRef.value, question: q, k: 5 }),
    })
    const ev: Evidence[] = d?.evidence?.hits ?? []
    messages.value.push({
      role: 'assistant',
      // 后端只回"能不能答 + 凭哪几条"；**自然语言的生成在业务侧接大模型之后**
      text: ev.length
        ? `根据已记录的资料，找到 ${ev.length} 条相关记录，供医师核对。`
        : '没有查到与该问题相关的记录。',
      evidence: ev,
      refused: ev.length === 0,
    })
  } catch (e: any) {
    messages.value.push({ role: 'assistant', text: `调用失败：${e.message}` })
  } finally {
    busy.value = false
  }
}

/**
 * 采纳 / 修改 / 否决 —— **必须落到后端**，不能只改 UI 状态。
 * 这三个动作是反馈塑形的入口，也是"系统有没有变好"的唯一可靠信号来源。
 */
async function resolve(m: Msg, kind: string, action: string) {
  m.resolved = action
  try {
    await api('/feedback', {
      method: 'POST',
      body: JSON.stringify({ patientRef: patientRef.value, kind, action }),
    })
  } catch {
    m.resolved = `${action}（上报失败，请重试）`
  }
}
</script>

<style scoped>
.workbench { display: flex; flex-direction: column; height: 100vh; font-size: 14px; }
.statusbar { display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  background: #eef6ee; border-bottom: 1px solid #d6e6d6; }
.statusbar.pending { background: #fdf3e3; border-bottom-color: #f0dcc0; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #7aa87a; }
.statusbar.pending .dot { background: #d39a3a; }
.spacer { flex: 1; }
.cog.off { color: #a33; }
.body { display: flex; flex: 1; min-height: 0; }
.timeline { width: 280px; border-right: 1px solid #e6e6e6; overflow-y: auto; padding: 10px; }
.timeline h2 { font-size: 13px; color: #666; margin: 0 0 8px; }
.empty { color: #999; font-size: 13px; }
.enc { display: block; width: 100%; text-align: left; padding: 8px 10px; margin-bottom: 6px;
  border: 1px solid #e6e6e6; border-radius: 8px; background: #fff; cursor: pointer; }
.enc.active { border-color: #9ab; background: #f6f9fc; }
.enc-title { display: block; font-weight: 500; }
.enc-brief { display: block; color: #777; font-size: 12px; margin-top: 2px; }
.chat { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.readonly-banner { padding: 6px 12px; background: #f2f4f7; color: #556; font-size: 12px;
  display: flex; gap: 12px; align-items: center; }
.messages { flex: 1; overflow-y: auto; padding: 12px; }
.msg { margin-bottom: 14px; }
.msg.user .bubble { background: #eaf1fb; }
.bubble { display: inline-block; padding: 8px 12px; border-radius: 10px; background: #f4f4f5;
  max-width: 42em; line-height: 1.6; white-space: pre-wrap; }
.refused { color: #a33; font-size: 12px; margin-top: 4px; }
.evidence { margin-top: 6px; font-size: 12px; color: #555; }
.evidence ul { margin: 4px 0 0 16px; padding: 0; }
.actions { margin-top: 6px; display: flex; gap: 8px; }
.actions button { font-size: 12px; padding: 4px 12px; border-radius: 6px;
  border: 1px solid #ccc; background: #fff; cursor: pointer; }
.resolved { margin-top: 4px; font-size: 12px; color: #7a7; }
.composer { display: flex; gap: 8px; padding: 10px 12px; border-top: 1px solid #e6e6e6; }
.composer textarea { flex: 1; height: 64px; resize: none; padding: 8px;
  border: 1px solid #ddd; border-radius: 8px; font: inherit; }
.composer button { padding: 0 20px; border-radius: 8px; border: 1px solid #ccc;
  background: #fff; cursor: pointer; }
.composer button:disabled { opacity: .5; cursor: not-allowed; }
</style>
