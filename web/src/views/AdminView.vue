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
        <p class="hint">
          资料是<strong>回答依据</strong>：问题词元必须落在<strong>标题或标签</strong>上，这条资料才会被引用；
          正文不参与该判定（避免"正文里偶然提到"造成的答非所问）。<strong>下架 = 不再作为任何回答的依据</strong>。
        </p>

        <div class="kbf">
          <input v-model.trim="kbForm.docKey" placeholder="资料键（留空自动生成；填已有键 = 编辑）" />
          <input v-model.trim="kbForm.title" placeholder="标题（必填）" />
          <input v-model.trim="kbForm.tags" placeholder="标签，逗号分隔 —— 相关性闸门的命中面" />
          <input v-model.trim="kbForm.version" placeholder="版本，如 2026.09" />
          <input v-model.trim="kbForm.reviewer" placeholder="复核人（留空 = 未复核）" />
          <textarea v-model="kbForm.content" rows="3" placeholder="正文"></textarea>
          <div class="kbf-actions">
            <button class="primary" :disabled="kbBusy || !kbForm.title" @click="saveKb()">
              {{ kbForm.docKey ? '保存修改' : '新增资料' }}
            </button>
            <button v-if="kbForm.docKey" @click="resetKbForm()">取消编辑</button>
            <span v-if="kbMsg" class="kbmsg" :class="kbBad ? 'bad' : 'ok'">{{ kbMsg }}</span>
          </div>
        </div>

        <table>
          <thead>
            <tr>
              <th>标题</th><th>标签</th><th>版本</th><th>复核人</th><th>状态</th><th>更新时间</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in kbDocs" :key="d.docKey">
              <td>{{ d.title }}</td>
              <td class="snap" :title="d.tags.join(' / ')">{{ d.tags.join(' / ') || '—' }}</td>
              <td class="mono">{{ d.version || '—' }}</td>
              <td>{{ d.reviewed ? d.reviewer : '待复核' }}</td>
              <td>
                <span class="badge" :class="d.status === 'active' ? 'ok' : 'warn'">
                  {{ d.status === 'active' ? '生效' : '已下架' }}
                </span>
              </td>
              <td class="mono">{{ d.updatedAt }}</td>
              <td class="ops">
                <button @click="editKb(d)">编辑</button>
                <button @click="toggleKb(d)">{{ d.status === 'active' ? '下架' : '上架' }}</button>
                <button @click="delKb(d)">删除</button>
              </td>
            </tr>
            <tr v-if="!kbDocs.length">
              <td colspan="7" class="empty">
                资料库是空的。空库意味着<strong>所有</strong>带依据问答都会拒答 —— 闸门找不到任何依据。
              </td>
            </tr>
          </tbody>
        </table>
        <p class="hint">
          生效 {{ kbActive }} 条 · 下架 {{ kbInactive }} 条
          <button class="mini" :disabled="kbBusy" @click="syncKb()">重新同步到认知侧</button>
        </p>
        <p class="hint warn">
          ⚠ 规则表（中药配伍禁忌 / 妊娠禁忌 / 毒性剂量上限）投产前**必须由临床药师逐条复核**
          并与本机构前置审核规则对齐 —— 表中「复核人」显示"待复核"即表示尚未复核。
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
          <thead>
            <tr>
              <th>时间</th><th>操作者</th><th>患者</th><th>动作</th><th>依据</th><th>内容 / 问题原文</th><th>模型版本</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in audit" :key="a.id">
              <td class="mono">{{ a.time }}</td>
              <td class="mono">{{ a.actor || '—' }}</td>
              <td class="mono">{{ a.ref || '—' }}</td>
              <td>
                {{ a.act }}
                <span v-if="a.refused" class="badge warn">拒答</span>
              </td>
              <td>{{ a.ev }}</td>
              <td class="snap" :title="a.input">{{ a.input || '—' }}</td>
              <td class="mono">{{ a.model }}</td>
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
          <p v-if="cfgErr" class="hint warn">读取当前设置失败：{{ cfgErr }}</p>

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
          <label v-if="cfg.llm !== 'null'"><span>服务地址（可选）</span>
            <input v-model="cfg.baseUrl"
                   :placeholder="cfg.llm === 'ollama' ? '默认 http://127.0.0.1:11434' : '默认 https://api.openai.com/v1'" />
          </label>
          <p v-if="cfg.llm === 'openai'" class="hint warn">
            ⚠ 使用云端 API 时，患者数据出网前**必须去标识化**（脱敏）。这不是建议，是合规要求。
          </p>

          <div class="save-row">
            <button class="primary" :disabled="saving" @click="saveConfig">
              {{ saving ? '保存中…' : '保存' }}
            </button>
            <span v-if="saveMsg" class="save-msg" :class="{ bad: saveBad }">{{ saveMsg }}</span>
          </div>

          <!-- 期望 vs 实际：防止"配置了但其实没生效" -->
          <div class="applied" :class="{ drift: cfgView?.drift === true }">
            <div class="applied-h">认知服务实际生效</div>
            <template v-if="cfgView?.applied">
              <div class="applied-b">
                provider = <b>{{ cfgView.applied.provider || '（空）' }}</b>
                · model = <b>{{ cfgView.applied.model || '（空）' }}</b>
                <span v-if="cfgView.applied.provider_known === false" class="applied-warn">
                  —— 这个 provider 系统不认识，会静默降级成"无模型"（模板话术）
                </span>
              </div>
              <div v-if="cfgView.drift === true" class="applied-warn">
                ⚠ 与上面保存的期望值**不一致**：真正在跑的是上面这一行，不是表单里的选项。
                改环境变量（<code>PASM_MEDICAL_LLM</code> / <code>PASM_MEDICAL_LLM_MODEL</code>）
                并重启认知服务后才会真正生效。
              </div>
              <div v-else class="applied-ok">✓ 与保存的期望值一致</div>
            </template>
            <div v-else class="applied-b unknown">
              取不到 —— 认知服务不可达，因此**无法判断**当前配置是否真的生效（不做乐观假设）。
            </div>
          </div>

          <p class="hint">
            ★ 本表只存**非机密**的对接开关（期望值），供后台展示与审计用；
            密钥（云 API key、LIS 口令）一律走环境变量，不落这张会被页面读出的表。
          </p>
          <p v-if="cfgView?.updatedAt" class="hint">
            最后保存：{{ cfgView.updatedAt }}
            <span v-if="cfgView.updatedBy">· {{ cfgView.updatedBy }}</span>
          </p>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, type AdminConfigView, type DesiredConfig, type KbDoc } from '../api'

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
interface AuditRow {
  id: number
  time: string
  actor: string
  ref: string
  act: string
  ev: number
  /** 输入快照：问答时就是患者问题的原文（超 300 字已截断）。 */
  input: string
  inputTruncated: boolean
  refused: boolean
  model: string
}
const audit = ref<AuditRow[]>([])
const loading = ref(false)
const err = ref('')

// ── 资料库：真实数据（业务库是权威）
//  ★ 以前这里是 5 行**硬编码的"待复核清单"**，看着像有资料、其实库里一条也没有 ——
//  而资料库是空的时候，所有带依据问答都会拒答（闸门找不到依据）。改真数据后，
//  "能不能答"就取决于这里有没有维护资料，这是可观察的。
const kbDocs = ref<KbDoc[]>([])
const kbActive = ref(0)
const kbInactive = ref(0)
const kbBusy = ref(false)
const kbMsg = ref('')
const kbBad = ref(false)
const emptyKbForm = () => ({ docKey: '', title: '', tags: '', content: '', version: '', reviewer: '' })
const kbForm = ref(emptyKbForm())

async function loadKb() {
  try {
    const r = await api.adminKb()
    kbDocs.value = r.docs
    kbActive.value = r.active
    kbInactive.value = r.inactive
  } catch (e) {
    kbMsg.value = e instanceof Error ? e.message : String(e)
    kbBad.value = true
  }
}

function clearKbForm() { kbForm.value = emptyKbForm() }

function editKb(d: KbDoc) {
  kbForm.value = {
    docKey: d.docKey, title: d.title, tags: d.tags.join(','),
    content: d.content, version: d.version, reviewer: d.reviewer,
  }
  kbMsg.value = ''
}

function resetKbForm() { clearKbForm(); kbMsg.value = '' }

/** 同步结果如实回报：`synced=false` = 库里改了但检索侧没生效（漂移），绝不能显示成成功。 */
function noteSync(sync: { synced?: boolean; error?: string } | undefined, okMsg: string) {
  if (sync && sync.synced === false) {
    kbBad.value = true
    kbMsg.value = okMsg + '；但同步检索副本失败：' + (sync.error || '认知服务不可达')
  } else {
    kbBad.value = false
    kbMsg.value = okMsg
  }
}

async function saveKb() {
  kbBusy.value = true
  const editing = !!kbForm.value.docKey
  try {
    const r = await api.adminKbSave({ ...kbForm.value })
    clearKbForm()
    noteSync(r.sync, editing ? '已保存并同步' : '已新增并同步')
    await loadKb()
  } catch (e) {
    kbBad.value = true
    kbMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    kbBusy.value = false
  }
}

async function toggleKb(d: KbDoc) {
  kbBusy.value = true
  const toActive = d.status !== 'active'
  try {
    const r = await api.adminKbStatus(d.docKey, toActive)
    noteSync(r.sync, toActive ? '已上架并同步' : '已下架并同步（下架的资料不再作为回答依据）')
    await loadKb()
  } catch (e) {
    kbBad.value = true
    kbMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    kbBusy.value = false
  }
}

async function delKb(d: KbDoc) {
  kbBusy.value = true
  try {
    const r = await api.adminKbDelete(d.docKey)
    noteSync(r.sync, '已删除并同步')
    await loadKb()
  } catch (e) {
    kbBad.value = true
    kbMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    kbBusy.value = false
  }
}

async function syncKb() {
  kbBusy.value = true
  try {
    const s = await api.adminKbSync()
    noteSync(s, '已重新同步 ' + (s.expected ?? 0) + ' 条生效资料')
    await loadKb()
  } catch (e) {
    kbBad.value = true
    kbMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    kbBusy.value = false
  }
}

// ── 对接设置：真实读写业务层（不再是前端占位）
const cfg = ref<DesiredConfig>({ ocr: 'none', lis: 'off', llm: 'null', model: '', baseUrl: '' })
const cfgView = ref<AdminConfigView | null>(null)
const cfgErr = ref('')
const saving = ref(false)
const saveMsg = ref('')
const saveBad = ref(false)

function pct(v: number) { return Math.round((v || 0) * 100) + '%' }
function urgencyText(u: string) {
  return u === 'emergency' ? '紧急' : u === 'urgent' ? '尽快' : '常规'
}

/** 读当前设置。★ 单独 try/catch：设置读不到不该把患者/统计/审计一起带崩。 */
async function loadConfig() {
  try {
    const v = await api.adminConfig()
    cfgView.value = v
    cfg.value = { ...v.desired }
    cfgErr.value = ''
  } catch (e) {
    cfgErr.value = e instanceof Error ? e.message : String(e)
  }
}

/** 保存。服务端会做白名单校验（前端下拉框不是安全边界），非法值返回 400 并如实显示。 */
async function saveConfig() {
  if (saving.value) return
  saving.value = true
  saveMsg.value = ''
  saveBad.value = false
  try {
    const v = await api.saveAdminConfig({ ...cfg.value })
    cfgView.value = v
    cfg.value = { ...v.desired }
    saveMsg.value = '已保存' + (v.updatedAt ? '（' + v.updatedAt + '）' : '')
  } catch (e) {
    saveBad.value = true
    saveMsg.value = '保存失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

async function load() {
  loading.value = true
  err.value = ''
  try {
    const [ps, st, au] = await Promise.all([
      api.adminPatients(), api.adminStats(), api.adminAudit(),
    ])
    patients.value = ps as PatientRow[]
    // ★ 口径写在卡片说明里：数字脱离口径无法核对，光给个 0.12 谁也不知道它算的是什么。
    // 用 unknown 而不是 number：这个响应里既有数字也有字符串（zone / windowFrom / definitions）
    const s = st as Record<string, unknown>
    const n = (k: string) => Number(s[k] ?? 0)
    stats.value = [
      {
        label: '今日问诊量',
        value: n('consultationsToday'),
        note: `今日发起（按 consult-start 计，一次问诊算一次）· 已走完流程 ${n('consultationsFinishedToday')} · 累计 ${n('consultationsTotal')}`,
      },
      {
        label: '今日红旗命中',
        value: n('redFlagsToday'),
        note: `今日触发红旗中断的次数 · 累计 ${n('redFlagsTotal')}（安全指标，优先关注）`,
      },
      {
        label: '今日拒答率',
        value: pct(n('refusalRateToday')),
        note: `今日 ${n('refusalsToday')} / ${n('questionsToday')} 个问题无依据被拒 · 累计 ${pct(n('refusalRateTotal'))}；高说明资料库覆盖不足`,
      },
      {
        label: '建议采纳率',
        value: pct(n('adoptionRateTotal')),
        note: `累计 采纳 /（采纳 + 否决），样本 ${n('feedbackTotal')} 次；用累计是因为单日反馈样本太小`,
      },
    ]
    // 直接映射而不是 as Record<string, unknown>：adminAudit 的类型是精确的，
    // 保留类型检查才能挡住"接口改了字段名、前端读 undefined"这类静默失效。
    audit.value = au.map(a => ({
      id: a.id,
      time: a.time,
      actor: a.actor,
      ref: a.ref,
      act: a.action,
      ev: a.evidenceCount,
      input: a.inputSnapshot,
      inputTruncated: a.inputTruncated,
      refused: a.refused,
      model: a.modelVersion,
    }))
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
  await Promise.all([loadConfig(), loadKb()])
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
/* 内容列：问题原文可能很长，限宽截断（完整值在 title 里，悬停可见） */
.snap {
  max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--text-2); cursor: help;
}
.badge { font-size: 12px; padding: 2px 8px; border-radius: 5px; }
.badge.emergency { background: var(--danger-bg); color: var(--danger-fg); }
.badge.routine, .badge.ok { background: var(--ok-bg); color: var(--ok-fg); }
.badge.warn { background: var(--warn-bg); color: var(--warn-fg); }
/* 资料库表单：一行一个字段，窄屏自动折行 */
.kbf { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 4px; }
.kbf input { flex: 1 1 190px; min-width: 150px; padding: 7px 9px; font-size: 13px;
  border: 1px solid var(--line-strong); border-radius: 7px; background: #fff; outline: none; }
.kbf textarea { flex: 1 1 100%; padding: 7px 9px; font-size: 13px; font-family: inherit;
  border: 1px solid var(--line-strong); border-radius: 7px; background: #fff; outline: none; resize: vertical; }
.kbf input:focus, .kbf textarea:focus { border-color: var(--primary); }
.kbf-actions { flex: 1 1 100%; display: flex; align-items: center; gap: 10px; }
.kbf button { padding: 7px 14px; font-size: 13px; border-radius: 7px; border: 1px solid var(--line-strong); background: #fff; cursor: pointer; }
.kbf button.primary { border: 0; background: var(--primary); color: #fff; }
.kbf button.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.kbmsg { font-size: 12px; }
.kbmsg.ok { color: var(--ok-fg); }
.kbmsg.bad { color: var(--danger-fg); }
.ops button { padding: 3px 9px; margin-right: 4px; font-size: 12px; border-radius: 6px;
  border: 1px solid var(--line-strong); background: #fff; cursor: pointer; }
.ops button:hover { border-color: var(--primary); color: var(--primary); }
.mini { margin-left: 8px; padding: 3px 10px; font-size: 12px; border-radius: 6px;
  border: 1px solid var(--line-strong); background: #fff; cursor: pointer; }
.empty { padding: 18px; text-align: center; color: var(--text-3); font-size: 13px; }
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
.primary:disabled { opacity: 0.5; cursor: not-allowed; }

/* 对接设置：保存反馈 + 期望/实际对账 */
.save-row { display: flex; align-items: center; gap: 12px; }
.save-msg { font-size: 12px; color: var(--ok-fg); }
.save-msg.bad { color: var(--danger-fg); }
.applied {
  margin-top: 18px; padding: 12px; border: 1px solid var(--line);
  border-radius: 8px; background: var(--surface); font-size: 12px;
}
.applied.drift { border-color: #f0c6c6; background: var(--danger-bg); }
.applied-h { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.applied-b { line-height: 1.8; }
.applied-b.unknown { color: var(--text-3); }
.applied-ok { color: var(--ok-fg); margin-top: 4px; }
.applied-warn { color: var(--danger-fg); margin-top: 6px; line-height: 1.8; }
.applied-warn code {
  font-family: ui-monospace, Consolas, monospace; font-size: 11px;
  background: #fff; padding: 1px 4px; border-radius: 4px;
}
</style>
