<template>
  <div class="admin">
    <aside class="nav">
      <button v-for="t in tabs" :key="t.id" :class="{ active: tab === t.id }" @click="tab = t.id">
        {{ t.name }}
      </button>
    </aside>

    <section class="panel">
      <p v-if="loading" class="hint">正在从业务层拉取真实数据…</p>
      <p v-if="err" class="hint warn">加载失败：{{ err }}</p>

      <!-- 患者情况 -->
      <template v-if="tab === 'patients'">
        <h2>患者情况</h2>
        <table>
          <thead><tr><th>编号</th><th>姓名</th><th>最近问诊</th><th>分诊建议</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="p in patients" :key="p.ref">
              <td class="mono">{{ p.ref }}</td>
              <td>{{ p.name }}</td>
              <td>{{ p.last }}</td>
              <td>{{ p.dept }}</td>
              <td><span class="badge" :class="p.urgency">{{ urgencyText(p.urgency) }}</span></td>
            </tr>
          </tbody>
        </table>
        <p class="hint">点开某位患者可查看《预问诊摘要》全文与对话轨迹（可审计）。</p>
      </template>

      <!-- 资料库 -->
      <template v-else-if="tab === 'kb'">
        <h2>资料库</h2>
        <table>
          <thead><tr><th>来源</th><th>类型</th><th>版本</th><th>复核人</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="k in kb" :key="k.name">
              <td>{{ k.name }}</td><td>{{ k.kind }}</td><td class="mono">{{ k.ver }}</td>
              <td>{{ k.reviewer }}</td>
              <td><span class="badge" :class="k.ok ? 'ok' : 'warn'">{{ k.ok ? '已复核' : '待复核' }}</span></td>
            </tr>
          </tbody>
        </table>
        <p class="hint warn">
          ⚠ 规则表（中药配伍禁忌 / 妊娠禁忌 / 毒性剂量上限）在投产前**必须由临床药师逐条复核**
          并与本机构前置审核规则对齐 —— 上表中的「复核人」为空即表示尚未复核。
        </p>
      </template>

      <!-- 统计 -->
      <template v-else-if="tab === 'stats'">
        <h2>运营统计</h2>
        <div class="kpis">
          <div v-for="s in stats" :key="s.label" class="kpi">
            <div class="kpi-v">{{ s.value }}</div>
            <div class="kpi-l">{{ s.label }}</div>
            <div class="kpi-n">{{ s.note }}</div>
          </div>
        </div>
        <p class="hint">
          ★ 医疗场景**漏诊率优先于准确率**：宁可系统说"不确定"，也不要一个漏掉红旗的高准确率。
          因此这里把「红旗命中数」和「拒答率」放在与准确率同等显眼的位置。
        </p>
      </template>

      <!-- 审计 -->
      <template v-else-if="tab === 'audit'">
        <h2>审计（只增不改）</h2>
        <table>
          <thead><tr><th>时间</th><th>患者</th><th>动作</th><th>依据条数</th><th>模型版本</th></tr></thead>
          <tbody>
            <tr v-for="a in audit" :key="a.time + a.act">
              <td class="mono">{{ a.time }}</td><td class="mono">{{ a.ref }}</td>
              <td>{{ a.act }}</td><td>{{ a.ev }}</td><td class="mono">{{ a.model }}</td>
            </tr>
          </tbody>
        </table>
        <p class="hint">
          每条 AI 输出都必须可追溯到「输入快照 + 召回条目 + 模型版本 + 操作者」。
          生产环境审计库应**独立且只授予 INSERT**，与业务库物理隔离。
        </p>
      </template>

      <!-- 对接设置 -->
      <template v-else>
        <h2>对接设置</h2>
        <div class="form">
          <label><span>OCR 引擎</span>
            <select v-model="cfg.ocr">
              <option value="none">未配置（检验单功能不可用）</option>
              <option value="vendor">化验单专用 OCR（推荐）</option>
              <option value="generic">通用 OCR（不推荐，表格上明显更差）</option>
            </select>
          </label>
          <label><span>LIS / HIS 直连</span>
            <select v-model="cfg.lis">
              <option value="off">未对接（OCR 为主路径）</option>
              <option value="hl7">HL7 v2 接口</option>
              <option value="fhir">FHIR API</option>
            </select>
          </label>
          <label><span>大模型</span>
            <select v-model="cfg.llm">
              <option value="null">不使用（降级为模板，功能完整）</option>
              <option value="ollama">本地 Ollama（数据不出院）</option>
              <option value="openai">OpenAI 兼容 API（需去标识化）</option>
            </select>
          </label>
          <label><span>模型名</span><input v-model="cfg.model" placeholder="如 qwen2.5:7b" /></label>
          <p v-if="cfg.llm === 'openai'" class="hint warn">
            ⚠ 使用云端 API 时，患者数据出网前**必须去标识化**（脱敏）。这不是建议，是合规要求。
          </p>
          <button class="primary">保存（占位：待业务层接口就绪）</button>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../api'

const tabs = [
  { id: 'patients', name: '患者情况' },
  { id: 'kb', name: '资料库' },
  { id: 'stats', name: '统计' },
  { id: 'audit', name: '审计' },
  { id: 'config', name: '对接设置' },
]
const tab = ref('patients')

// ★ 全部从业务层真实接口拉取（dev 档由 DemoDataSeeder 灌入演示数据）。
interface PatientRow { ref: string; name: string; allergy: string; chronic: string; encounterCount: number; last: string; dept: string; urgency: string }
const patients = ref<PatientRow[]>([])
const stats = ref<{ label: string; value: string | number; note: string }[]>([])
const audit = ref<{ time: string; ref: string; act: string; ev: number; model: string }[]>([])
const loading = ref(false)
const err = ref('')

// 资料库：固定的"规则表复核"清单（上线前必须由临床药师逐条复核），非动态数据，保留为静态参考。
const kb = [
  { name: '临床指南汇编', kind: '西医', ver: '2026.09', reviewer: '（待临床顾问）', ok: false },
  { name: '中药配伍禁忌表（十八反/十九畏）', kind: '中医·规则', ver: '2026.09-starter', reviewer: '', ok: false },
  { name: '毒性药材剂量上限', kind: '中医·规则', ver: '2026.09-starter', reviewer: '', ok: false },
  { name: '检验项目字典', kind: '术语', ver: '2026.09', reviewer: '', ok: false },
  { name: '红旗症状规则表', kind: '规则', ver: '2026.09-starter', reviewer: '', ok: false },
]

const cfg = ref({ ocr: 'none', lis: 'off', llm: 'null', model: '' })

function pct(v: number) { return Math.round((v || 0) * 100) + '%' }
function urgencyText(u: string) {
  return u === 'emergency' ? '紧急' : u === 'urgent' ? '尽快' : '常规'
}

async function load() {
  loading.value = true
  err.value = ''
  try {
    const [ps, st, au] = await Promise.all([
      api.adminPatients(), api.adminStats(), api.adminAudit(),
    ])
    patients.value = ps as PatientRow[]
    const s = st as Record<string, number>
    stats.value = [
      { label: '今日问诊量', value: s.consultToday ?? 0, note: '含预问诊与问诊结束' },
      { label: '红旗命中', value: s.redFlags ?? 0, note: '安全指标，优先关注' },
      { label: '拒答率', value: pct(s.refusalRate), note: '高说明资料库覆盖不足' },
      { label: '建议采纳率', value: pct(s.adoptionRate), note: '来自医生「采纳/否决」反馈' },
    ]
    audit.value = (au as Record<string, unknown>[]).map(a => ({
      time: String(a.time), ref: String(a.ref), act: String(a.action),
      ev: Number(a.evidenceCount ?? 0), model: String(a.modelVersion ?? ''),
    }))
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.admin { display: grid; grid-template-columns: 168px 1fr; height: 100%; }
.nav { background: var(--surface); border-right: 1px solid var(--line); padding: 12px 10px; }
.nav button {
  display: block; width: 100%; text-align: left; padding: 8px 12px; margin-bottom: 4px;
  border: 0; background: none; border-radius: 8px; color: var(--text-2); font-size: 13px;
}
.nav button:hover { background: #f4f6f8; }
.nav button.active { background: #eef4f6; color: var(--primary-dark); font-weight: 500; }
.panel { overflow-y: auto; padding: 22px 26px; }
h2 { margin: 0 0 16px; font-size: 17px; font-weight: 500; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { text-align: left; font-weight: 400; color: var(--text-3); font-size: 12px; padding: 6px 8px; border-bottom: 1px solid var(--line); }
td { padding: 8px; border-bottom: 1px solid var(--line); }
.mono { font-family: ui-monospace, Consolas, monospace; font-size: 12px; color: var(--text-2); }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 5px; }
.badge.emergency { background: var(--danger-bg); color: var(--danger-fg); }
.badge.routine, .badge.ok { background: var(--ok-bg); color: var(--ok-fg); }
.badge.warn { background: var(--warn-bg); color: var(--warn-fg); }
.hint { margin-top: 16px; font-size: 12px; color: var(--text-3); line-height: 1.8; }
.hint.warn { color: var(--warn-fg); background: var(--warn-bg); padding: 10px 12px; border-radius: 8px; }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.kpi { border: 1px solid var(--line); border-radius: var(--radius); padding: 14px; background: var(--surface); }
.kpi-v { font-size: 24px; font-weight: 500; }
.kpi-l { font-size: 13px; margin-top: 2px; }
.kpi-n { font-size: 11px; color: var(--text-3); margin-top: 4px; }
.form { max-width: 460px; }
.form label { display: block; margin-bottom: 16px; }
.form span { display: block; font-size: 12px; color: var(--text-2); margin-bottom: 5px; }
.form select, .form input {
  width: 100%; padding: 8px 10px; border: 1px solid var(--line-strong); border-radius: 8px; background: #fff;
}
.primary { padding: 9px 18px; border: 0; border-radius: 8px; background: var(--primary); color: #fff; }
</style>
