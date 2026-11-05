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

      <!-- ★ 数据范围必须常驻可见：不写清范围，使用者会把"本科室的数字"当成"全院的数字" -->
      <p v-if="me" class="scope">
        {{ me.displayName || me.username }} ·
        <span class="scopebadge">{{ me.roleLabel }}</span>
        <span class="scopebadge">数据范围：{{ me.scopeLabel }}</span>
        <span v-if="me.department" class="scopebadge">科室：{{ me.department }}</span>
        <span v-if="!canWriteKb" class="scopebadge muted">资料库与对接设置：只读（需超级管理员）</span>
      </p>

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

      <!-- 坐诊台：转人工会话的排队、接诊与实时对话 -->
      <template v-else-if="tab === 'clinic'">
        <h2>坐诊台</h2>
        <p class="hint">
          患者转人工后进入<strong>本科室队列</strong>。接诊后可与患者实时对话；结束时写入一条就诊记录
          （填写的「判断/处置」属于医师结论，会成为自学习候选的来源）。
        </p>
        <p v-if="chatErr" class="hint warn">{{ chatErr }}</p>

        <div class="clinic">
          <aside class="cq">
            <div class="cq-h">会话（{{ queue.length }}）</div>
            <button
              v-for="s in queue" :key="s.id"
              class="cq-item" :class="{ on: activeChat?.id === s.id }"
              @click="openSession(s)"
            >
              <span class="cq-top">
                <b>{{ s.patientRef }}</b>
                <em :class="s.status === 'WAITING' ? 'warnb' : 'okb'">
                  {{ s.status === 'WAITING' ? '等待接诊' : '人工中' }}
                </em>
              </span>
              <span class="cq-sub">{{ s.chiefComplaint || '未填主诉' }} · {{ s.department }}</span>
              <span class="cq-sub muted">{{ s.lastMessage || '—' }}</span>
            </button>
            <p v-if="!queue.length" class="empty">
              暂无会话。患者可在问诊页点「转人工」，或红旗命中后自动转接。
            </p>
            <button class="ghost" @click="loadQueue">刷新队列</button>
          </aside>

          <section class="chat">
            <template v-if="activeChat">
              <div class="chat-h">
                <b>{{ activeChat.patientRef }}</b>
                <span class="scopebadge">{{ activeChat.department }}</span>
                <span class="scopebadge" :class="{ muted: activeChat.status === 'WAITING' }">
                  {{ activeChat.status === 'WAITING' ? '等待接诊' : '人工中' }}
                </span>
                <span v-if="activeChat.assignedName" class="scopebadge muted">
                  接诊：{{ activeChat.assignedName }}
                </span>
                <button v-if="activeChat.status === 'WAITING'" class="primary" @click="takeSession">接诊</button>
                <button v-else class="ghost" @click="closePanel = true">结束并写病历</button>
              </div>

              <div ref="chatBox" class="chat-body">
                <div v-for="m in chatMsgs" :key="m.id" class="msg" :class="m.sender.toLowerCase()">
                  <div class="bubble">{{ m.body }}</div>
                  <div class="mmeta">{{ m.senderName }} · {{ m.createdAt.slice(11, 16) }}</div>
                </div>
                <p v-if="!chatMsgs.length" class="empty">还没有消息</p>
              </div>

              <div class="chat-in">
                <input
                  v-model="chatDraft" placeholder="输入消息，回车发送"
                  @keyup.enter="sendChat"
                />
                <button
                  class="primary"
                  :disabled="!chatDraft.trim() || activeChat.status === 'WAITING'"
                  @click="sendChat"
                >发送</button>
              </div>
              <p v-if="activeChat.status === 'WAITING'" class="hint warn">
                请先点「接诊」再回复 —— 允许未接诊就回复，会让同一患者同时被多人答，责任无法归属。
              </p>

              <div v-if="closePanel" class="closebox">
                <div class="cq-h">结束就诊并写入病历</div>
                <label><span>判断（选填，医师结论）</span>
                  <input v-model="closeAssessment" placeholder="如：上呼吸道感染可能" />
                </label>
                <label><span>处置（选填）</span>
                  <input v-model="closePlan" placeholder="如：对症退热，必要时门诊复诊" />
                </label>
                <p class="hint">这两项会随本次会话写入就诊记录；留空则不编造结论（预问诊本身不是诊断）。</p>
                <div class="save-row">
                  <button class="primary" @click="doClose">确认结束</button>
                  <button class="ghost" @click="closePanel = false">取消</button>
                </div>
              </div>
            </template>
            <p v-else class="empty">从左侧选择一个会话</p>
          </section>
        </div>
      </template>

      <!-- 资料库 -->
      <template v-else-if="tab === 'kb'">
        <h2>资料库</h2>
        <p class="hint">
          资料是<strong>回答依据</strong>：问题词元必须落在<strong>标题或标签</strong>上，这条资料才会被引用；
          正文不参与该判定（避免"正文里偶然提到"造成的答非所问）。<strong>下架 = 不再作为任何回答的依据</strong>。
        </p>

        <div v-if="canWriteKb" class="kbf">
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
        <p v-else class="hint warn">
          只读：资料库的改动会影响<strong>全院</strong>的答案依据（一个科室改掉的是所有科室的依据），
          因此只有<strong>超级管理员</strong>可以修改。当前身份「{{ me?.roleLabel || '—' }}」可查看与核对。
        </p>

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
                <button :disabled="!canWriteKb" @click="editKb(d)">编辑</button>
                <button :disabled="!canWriteKb" @click="toggleKb(d)">{{ d.status === 'active' ? '下架' : '上架' }}</button>
                <button :disabled="!canWriteKb" @click="delKb(d)">删除</button>
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
          <button class="mini" :disabled="kbBusy || !canWriteKb" @click="syncKb()">重新同步到认知侧</button>
        </p>
        <p class="hint warn">
          ⚠ 规则表（中药配伍禁忌 / 妊娠禁忌 / 毒性剂量上限）投产前**必须由临床药师逐条复核**
          并与本机构前置审核规则对齐 —— 表中「复核人」显示"待复核"即表示尚未复核。
        </p>
      </template>

      <!-- 统计 -->
      <template v-else-if="tab === 'stats'">
        <h2>运营统计</h2>
        <p v-if="statsNote" class="hint warn">{{ statsNote }}</p>
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
        <div class="audit-more">
          <span class="hint">已加载 {{ audit.length }} 条</span>
          <button v-if="auditHasMore" class="ghost" :disabled="loading"
                  @click="loadMoreAudit">
            {{ loading ? '加载中…' : '加载更早的审计' }}
          </button>
          <span v-else class="hint">已经是全部了</span>
        </div>
          <p class="hint">
            每条 AI 输出都必须可追溯到「输入快照 + 召回条目 + 模型版本 + 操作者」。
            生产环境审计库应**独立且只授予 INSERT**，与业务库物理隔离。
          </p>
        </template>

      <!-- 审核台 -->
      <template v-else-if="tab === 'review'">
        <h2>自学习审核台</h2>
        <p class="hint">
          候选来自每日批处理（从近期就诊抽取可沉淀的经验），<strong>默认不直接生效</strong>。
          只有经本台「采纳」后，才会写进资料库并同步认知侧。这是自学习闭环的 human-in-the-loop 闸门
          —— 医生写错的病历若直接学进去，系统会把错误<strong>放大</strong>。
        </p>

        <div class="revbar">
          <div class="seg">
            <button :class="{ on: candidateFilter === 'pending' }" @click="setFilter('pending')">待处理</button>
            <button :class="{ on: candidateFilter === 'conflict' }" @click="setFilter('conflict')">冲突挂起</button>
            <button :class="{ on: candidateFilter === 'approved' }" @click="setFilter('approved')">已采纳</button>
            <button :class="{ on: candidateFilter === 'rejected' }" @click="setFilter('rejected')">已驳回</button>
          </div>
          <button class="ghost" :disabled="candBusy" @click="loadCandidates()">刷新</button>
        </div>

        <p v-if="candMsg" class="hint" :class="candBad ? 'warn' : ''">{{ candMsg }}</p>

        <table>
          <thead>
            <tr><th>标题 / 内容</th><th>科室</th><th>标签</th><th>评分</th><th>状态</th><th>依据</th><th>创建</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in candidates" :key="c.id">
              <td>
                <div class="ctitle">{{ c.title }}</div>
                <div class="cbody">{{ c.content }}</div>
                <div v-if="c.status === 'CONFLICT'" class="cwarn">
                  ⚠ 与现库 {{ c.conflictWith }} 同标题但结论相反，已挂起待人工裁定（采纳即覆盖）
                </div>
              </td>
              <td>{{ c.department || '全院' }}</td>
              <td class="snap" :title="c.tags.join(' / ')">{{ c.tags.join(' / ') || '—' }}</td>
              <td>{{ Math.round((c.confidence || 0) * 100) }}%</td>
              <td><span class="badge" :class="cstatus(c.status)">{{ cstatusText(c.status) }}</span></td>
              <td class="snap" :title="c.evidence">{{ c.evidence }}</td>
              <td class="mono">{{ c.createdAt }}</td>
              <td class="ops">
                <template v-if="c.status === 'PENDING' || c.status === 'CONFLICT'">
                  <button :disabled="!canWriteKb || candBusy" @click="approveCandidate(c.id)">采纳</button>
                  <button :disabled="!canWriteKb || candBusy" @click="rejectCandidate(c.id)">驳回</button>
                </template>
                <span v-else class="muted">{{ c.reviewedBy || '—' }}</span>
              </td>
            </tr>
            <tr v-if="!candidates.length">
              <td colspan="8" class="empty">
                暂无候选。可点下方「生成候选」从近期就诊抽取经验，或等待每日 02:00 批处理。
              </td>
            </tr>
          </tbody>
        </table>

        <div class="pool">
          <div class="pool-h">知识池版本（可回滚）</div>
          <template v-if="poolVersion">
            <div class="pool-line">
              当前版本 <b>v{{ poolVersion.version }}</b> · 本版本资料 {{ poolVersion.docsAtVersion }} 条
            </div>
            <div class="pool-actions">
              <button class="primary" :disabled="candBusy || !isSuperAdmin" @click="generateCandidates()">
                生成候选（扫近期就诊）
              </button>
              <button :disabled="candBusy || !poolVersion.rollbackAvailable || !isSuperAdmin" @click="rollbackPool()">
                回滚上一版本
              </button>
              <span v-if="!poolVersion.rollbackAvailable" class="hint">已是最初版本，无可回退</span>
              <span v-if="!isSuperAdmin" class="hint warn">生成与回滚仅超管可用</span>
            </div>
          </template>
        </div>
      </template>

      <!-- 科室管理（仅超管） -->
      <template v-else-if="tab === 'departments'">
        <h2>科室管理</h2>
        <p class="hint">
          科室是全院主数据：登录选科、坐诊队列、资料库授权、统计口径都按它归类。
          <strong>停用</strong>不会删除历史数据；<strong>删除</strong>只用于配错的脏数据。
        </p>

        <div class="form kbf">
          <label><span>科室名称</span><input v-model="deptName" placeholder="如：皮肤科" /></label>
          <label><span>排序（小的在前）</span><input v-model.number="deptSort" type="number" placeholder="100" /></label>
          <div class="save-row">
            <button class="primary" :disabled="deptBusy || !deptName.trim()" @click="createDept">新增科室</button>
            <span v-if="deptMsg" class="save-msg" :class="{ bad: deptBad }">{{ deptMsg }}</span>
          </div>
        </div>

        <table>
          <thead><tr><th>科室</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="d in deptRows" :key="d.id">
              <td>{{ d.name }}</td>
              <td>{{ d.sortOrder }}</td>
              <td>
                <span class="badge" :class="d.active ? 'ok' : 'warn'">{{ d.active ? '启用' : '停用' }}</span>
              </td>
              <td class="ops">
                <button class="ghost" @click="toggleDept(d)">{{ d.active ? '停用' : '启用' }}</button>
                <button class="ghost danger" @click="removeDept(d)">删除</button>
              </td>
            </tr>
            <tr v-if="!deptRows.length"><td colspan="4" class="empty">暂无科室</td></tr>
          </tbody>
        </table>
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
import { computed, ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { api, type AdminConfigView, type DesiredConfig, type IdentityView, type KbDoc, type KbCandidate, type KbPoolVersion, type ChatSession, type ChatMessage } from '../api'
import { ensureIdentity } from '../identity'

/**
 * ★ 标签按角色裁剪（不是全员同一套）：
 *   · 对接设置：仅超管（决定患者数据是否出网，医护后台不需要，也不该看到）；
 *   · 科室管理：仅超管（全院主数据，改名/停用影响历史归类与统计口径）；
 *   · 审核台：超管 + 科室管理员（医护看候选列表会 403）。
 * 隐藏只是体验，服务端 SecurityConfig 仍独立拒绝。
 */
const ALL_TABS = [
  { id: 'patients', name: '患者情况' },
  { id: 'clinic', name: '坐诊' },
  { id: 'kb', name: '资料库' },
  { id: 'review', name: '审核台' },
  { id: 'stats', name: '统计' },
  { id: 'audit', name: '审计' },
  { id: 'departments', name: '科室管理' },
  { id: 'config', name: '对接设置' },
]
const tab = ref('patients')
const tabs = computed(() => ALL_TABS.filter((t) => {
  if (t.id === 'config') return canWriteConfig.value
  if (t.id === 'departments') return canManageDepartments.value
  if (t.id === 'review') return canWriteKb.value || me.value?.role === 'DEPT_ADMIN'
  return true
}))

// ★ 全部从业务层真实接口拉取（dev 档由 DemoDataSeeder 灌入演示数据）。
interface PatientRow { ref: string; name: string; allergy: string; chronic: string; encounterCount: number; last: string; dept: string; urgency: string }
const patients = ref<PatientRow[]>([])
const stats = ref<{ label: string; value: string | number; note: string }[]>([])
/** 统计口径的数据范围说明（来自服务端 scopeNote）—— 科室角色看到的是全院口径，必须标明。 */
const statsNote = ref('')

/** 当前身份与可写范围。★ 界面据此禁用按钮只是体验；服务端会独立拒绝（不能只靠隐藏）。 */
const me = ref<IdentityView | null>(null)
const canWriteKb = computed(() => !!me.value?.canWriteKb)
const canWriteConfig = computed(() => !!me.value?.canWriteConfig)
const canManageDepartments = computed(() => !!me.value?.canManageDepartments)
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
/**
 * 审计分页游标（0 基）。
 * ★ 旧接口固定只返回最近 200 条，更早的审计在界面上**翻不到** ——
 *   对"只增不改"的合规库来说等于静默丢历史。现在按页向后翻。
 */
const auditPage = ref(0)
const auditHasMore = ref(false)
const AUDIT_PAGE_SIZE = 200
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

// ── 审核台：自学习候选池（PLAN §8）──
const candidates = ref<KbCandidate[]>([])
const candidateFilter = ref<'pending' | 'conflict' | 'approved' | 'rejected' | 'all'>('pending')
const poolVersion = ref<KbPoolVersion | null>(null)
const candBusy = ref(false)
const candMsg = ref('')
const candBad = ref(false)
const isSuperAdmin = computed(() => me.value?.role === 'SUPER_ADMIN')

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

// ── 审核台：自学习候选池
async function loadCandidates() {
  try {
    const status = candidateFilter.value === 'pending' ? undefined
      : candidateFilter.value === 'conflict' ? 'CONFLICT'
      : candidateFilter.value === 'approved' ? 'APPROVED'
      : candidateFilter.value === 'rejected' ? 'REJECTED' : undefined
    // "待处理" = 不含已驳回（待审 + 冲突 + 已采纳）
    candidates.value = await api.adminCandidates(status)
  } catch (e) {
    candMsg.value = e instanceof Error ? e.message : String(e)
    candBad.value = true
  }
}

function setFilter(f: 'pending' | 'conflict' | 'approved' | 'rejected') {
  candidateFilter.value = f
  loadCandidates()
}

async function loadPoolVersion() {
  try {
    poolVersion.value = await api.adminKbPoolVersion()
  } catch {
    // 不影响主流程
  }
}

async function generateCandidates() {
  candBusy.value = true
  candMsg.value = ''
  try {
    const r = await api.adminGenerateCandidates(30)
    candBad.value = false
    candMsg.value = `已生成候选 ${r.generated} 条（去重 ${r.deduplicated}，冲突挂起 ${r.conflict}，待审共 ${r.pendingTotal}）`
    await loadCandidates()
    await loadPoolVersion()
  } catch (e) {
    candBad.value = true
    candMsg.value = '生成失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    candBusy.value = false
  }
}

async function approveCandidate(id: number) {
  candBusy.value = true
  candMsg.value = ''
  try {
    const r = await api.adminApproveCandidate(id)
    candBad.value = false
    candMsg.value = (r.sync && r.sync.synced === false)
      ? '已采纳并入库；但同步检索副本失败：' + (r.sync.error || '认知服务不可达')
      : '已采纳：' + r.docKey + '（知识池版本 v' + r.poolVersion + '）'
    await loadCandidates()
    await loadPoolVersion()
    await loadKb()
  } catch (e) {
    candBad.value = true
    candMsg.value = '采纳失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    candBusy.value = false
  }
}

async function rejectCandidate(id: number) {
  candBusy.value = true
  candMsg.value = ''
  try {
    await api.adminRejectCandidate(id)
    candBad.value = false
    candMsg.value = '已驳回（未入库）'
    await loadCandidates()
  } catch (e) {
    candBad.value = true
    candMsg.value = '驳回失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    candBusy.value = false
  }
}

async function rollbackPool() {
  candBusy.value = true
  candMsg.value = ''
  try {
    const r = await api.adminKbPoolRollback()
    candBad.value = false
    candMsg.value = `已回滚：v${r.from} → v${r.to}，停用 ${r.deactivated} 条资料`
    await loadPoolVersion()
    await loadKb()
  } catch (e) {
    candBad.value = true
    candMsg.value = '回滚失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    candBusy.value = false
  }
}

function cstatus(s: string) {
  return s === 'APPROVED' ? 'ok' : s === 'REJECTED' ? 'warn'
    : s === 'CONFLICT' ? 'emergency' : 'pending'
}
function cstatusText(s: string) {
  return s === 'PENDING' ? '待审' : s === 'APPROVED' ? '已采纳'
    : s === 'REJECTED' ? '已驳回' : '冲突挂起'
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

/**
 * 审计行映射。
 * 直接映射而不是 as Record<string, unknown>：`adminAudit` 的类型是精确的，
 * 保留类型检查才能挡住"接口改了字段名、前端读 undefined"这类静默失效。
 */
function mapAudit(rows: Awaited<ReturnType<typeof api.adminAudit>>): AuditRow[] {
  return rows.map(a => ({
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
}

/** 向后翻一页（更早的审计）。旧接口固定 200 条，超过就再也看不到了。 */
async function loadMoreAudit() {
  if (loading.value || !auditHasMore.value) return
  loading.value = true
  err.value = ''
  try {
    const next = auditPage.value + 1
    const rows = await api.adminAudit(next, AUDIT_PAGE_SIZE)
    audit.value = audit.value.concat(mapAudit(rows))
    auditPage.value = next
    auditHasMore.value = rows.length === AUDIT_PAGE_SIZE
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  err.value = ''
  // 身份决定本页显示的数据范围，先取到（取不到不阻断加载，只是范围提示为空）
  me.value = await ensureIdentity()
  try {
    const [ps, st, au] = await Promise.all([
      api.adminPatients(), api.adminStats(), api.adminAudit(0, AUDIT_PAGE_SIZE),
    ])
    auditPage.value = 0
    auditHasMore.value = au.length === AUDIT_PAGE_SIZE
    patients.value = ps as PatientRow[]
    // ★ 口径写在卡片说明里：数字脱离口径无法核对，光给个 0.12 谁也不知道它算的是什么。
    // 用 unknown 而不是 number：这个响应里既有数字也有字符串（zone / windowFrom / definitions）
    const s = st as Record<string, unknown>
    const n = (k: string) => Number(s[k] ?? 0)
    // ★ 范围必须显示出来，理由同时间窗：不看范围就不能判断这个数字代表谁
    statsNote.value = String(s.scopeNote ?? '')
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
    audit.value = mapAudit(au)
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
  // ★ 按权限只拉该角色能读的：未授权接口会 403，拉回来只会制造"页面到处是错"的假象。
  const jobs: Promise<unknown>[] = [loadQueue(), loadDepartments()]
  if (canWriteConfig.value) jobs.push(loadConfig())
  if (canWriteKb.value || me.value?.role === 'DEPT_ADMIN') {
    jobs.push(loadKb(), loadCandidates(), loadPoolVersion())
  }
  await Promise.all(jobs)
}

// ── 坐诊台：队列 + 双向消息（增量轮询，2s）
//  ★ 用 afterId 增量拉取而不是每次全量：轮询既要"不漏消息"也要"不重复渲染"。
//    2s 是体感与请求量的折中；接口形状与 SSE/WS 一致，将来换传输层不用改数据模型。
const queue = ref<ChatSession[]>([])
const activeChat = ref<ChatSession | null>(null)
const chatMsgs = ref<ChatMessage[]>([])
const chatDraft = ref('')
const chatErr = ref('')
const chatBox = ref<HTMLElement | null>(null)
const closePanel = ref(false)
const closeAssessment = ref('')
const closePlan = ref('')
const chatBusy = ref(false)

async function loadQueue() {
  if (!me.value?.clinicalSide) return
  try {
    queue.value = await api.chatQueue()
    if (activeChat.value) {
      const fresh = queue.value.find((x) => x.id === activeChat.value!.id)
      if (fresh) activeChat.value = fresh
    }
  } catch (e) {
    chatErr.value = e instanceof Error ? e.message : String(e)
  }
}

async function openSession(s: ChatSession) {
  activeChat.value = s
  chatMsgs.value = []
  closePanel.value = false
  chatErr.value = ''
  await pullMessages(true)
}

async function pullMessages(reset = false) {
  const s = activeChat.value
  if (!s) return
  const after = reset ? 0 : (chatMsgs.value.length ? chatMsgs.value[chatMsgs.value.length - 1].id : 0)
  try {
    const rows = await api.chatMessages(s.id, after)
    if (rows.length) {
      chatMsgs.value = after === 0 ? rows : chatMsgs.value.concat(rows)
      await nextTick()
      if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
    }
  } catch (e) {
    chatErr.value = e instanceof Error ? e.message : String(e)
  }
}

async function takeSession() {
  if (!activeChat.value) return
  try {
    activeChat.value = await api.chatTake(activeChat.value.id)
    await Promise.all([pullMessages(true), loadQueue()])
  } catch (e) {
    chatErr.value = e instanceof Error ? e.message : String(e)
  }
}

async function sendChat() {
  const s = activeChat.value
  const body = chatDraft.value.trim()
  if (!s || !body || chatBusy.value) return
  chatBusy.value = true
  try {
    const m = await api.chatSend(s.id, body)
    chatMsgs.value.push(m)
    chatDraft.value = ''
    await nextTick()
    if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
  } catch (e) {
    chatErr.value = e instanceof Error ? e.message : String(e)
  } finally {
    chatBusy.value = false
  }
}

async function doClose() {
  if (!activeChat.value) return
  try {
    const r = await api.chatClose(activeChat.value.id, closeAssessment.value, closePlan.value)
    closePanel.value = false
    closeAssessment.value = ''
    closePlan.value = ''
    activeChat.value = r.session
    chatErr.value = ''
    // 结束后会多一条就诊，患者列表要跟着更新（否则"结束"看起来什么都没发生）
    patients.value = (await api.adminPatients()) as PatientRow[]
    await loadQueue()
  } catch (e) {
    chatErr.value = e instanceof Error ? e.message : String(e)
  }
}

// ── 科室管理（仅超管）
const deptRows = ref<{ id: number; name: string; active: boolean; sortOrder: number }[]>([])
const deptName = ref('')
const deptSort = ref(100)
const deptMsg = ref('')
const deptBad = ref(false)
const deptBusy = ref(false)

async function loadDepartments() {
  if (!canManageDepartments.value) return
  try {
    deptRows.value = await api.adminDepartments()
  } catch (e) {
    deptBad.value = true
    deptMsg.value = e instanceof Error ? e.message : String(e)
  }
}

async function createDept() {
  if (deptBusy.value || !deptName.value.trim()) return
  deptBusy.value = true
  try {
    await api.adminDeptCreate(deptName.value.trim(), deptSort.value || 100)
    deptBad.value = false
    deptMsg.value = '已新增：' + deptName.value.trim()
    deptName.value = ''
    await loadDepartments()
  } catch (e) {
    deptBad.value = true
    deptMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    deptBusy.value = false
  }
}

async function toggleDept(d: { id: number; name: string; active: boolean; sortOrder: number }) {
  try {
    await api.adminDeptUpdate(d.id, { active: !d.active })
    deptBad.value = false
    deptMsg.value = (d.active ? '已停用：' : '已启用：') + d.name
    await loadDepartments()
  } catch (e) {
    deptBad.value = true
    deptMsg.value = e instanceof Error ? e.message : String(e)
  }
}

async function removeDept(d: { id: number; name: string; active: boolean; sortOrder: number }) {
  if (!window.confirm('删除科室「' + d.name + '」？历史数据不会删，但该科室将从选择列表消失。\n如需保留历史归类，请改用「停用」。')) {
    return
  }
  try {
    await api.adminDeptDelete(d.id)
    deptBad.value = false
    deptMsg.value = '已删除：' + d.name
    await loadDepartments()
  } catch (e) {
    deptBad.value = true
    deptMsg.value = e instanceof Error ? e.message : String(e)
  }
}

let chatTimer: number | undefined
onMounted(() => {
  chatTimer = window.setInterval(() => {
    if (tab.value !== 'clinic') return
    loadQueue()
    if (activeChat.value) pullMessages()
  }, 2000)
})
onBeforeUnmount(() => {
  if (chatTimer) window.clearInterval(chatTimer)
})

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
.scope {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  margin: 0 0 16px; font-size: 12px; color: var(--text-2);
}
.scopebadge {
  font-style: normal; padding: 1px 8px; border-radius: 5px;
  background: var(--bg); border: 1px solid var(--line); color: var(--text-2);
}
.scopebadge.muted { color: var(--warn-fg); background: var(--warn-bg); border-color: var(--warn-bg); }
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

/* 审计分页：旧接口固定 200 条，更早的记录翻不到 —— 现在可以向后加载 */
.audit-more { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.audit-more button {
  padding: 6px 12px; border: 1px solid var(--line-strong); border-radius: 8px;
  background: #fff; font-size: 12px; cursor: pointer;
}
.audit-more button:disabled { opacity: 0.5; cursor: not-allowed; }

/* 审核台 */
.revbar { display: flex; align-items: center; gap: 14px; margin: 4px 0 10px; }
.seg { display: inline-flex; border: 1px solid var(--line-strong); border-radius: 8px; overflow: hidden; }
.seg button {
  padding: 6px 14px; font-size: 13px; border: 0; background: #fff; cursor: pointer; color: var(--text-2);
  border-right: 1px solid var(--line-strong);
}
.seg button:last-child { border-right: 0; }
.seg button.on { background: var(--primary); color: #fff; }
.ghost {
  padding: 6px 12px; font-size: 13px; border: 1px solid var(--line-strong);
  border-radius: 8px; background: #fff; cursor: pointer;
}
.ctitle { font-size: 13px; font-weight: 500; }
.cbody {
  margin-top: 3px; font-size: 12px; color: var(--text-2); max-width: 360px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cwarn { margin-top: 4px; font-size: 11px; color: var(--danger-fg); }
.badge.pending { background: #eef2ff; color: #3b5bdb; }
.muted { font-size: 12px; color: var(--text-3); }
.pool {
  margin-top: 18px; padding: 14px; border: 1px solid var(--line); border-radius: 10px;
  background: var(--surface); font-size: 13px;
}
.pool-h { font-size: 12px; color: var(--text-3); margin-bottom: 8px; }
.pool-line { margin-bottom: 10px; }
.pool-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.pool-actions button { padding: 7px 14px; font-size: 13px; border-radius: 7px; border: 1px solid var(--line-strong); background: #fff; cursor: pointer; }
.pool-actions button.primary { border: 0; background: var(--primary); color: #fff; }
.pool-actions button:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 坐诊台 ── */
.clinic {
  display: grid; grid-template-columns: 280px 1fr; gap: 14px;
  height: calc(100vh - 210px); min-height: 420px;
}
.cq {
  border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface);
  padding: 10px; overflow-y: auto; display: flex; flex-direction: column; gap: 6px;
}
.cq-h { font-size: 12px; color: var(--text-3); margin-bottom: 4px; }
.cq-item {
  text-align: left; border: 1px solid var(--line); background: #fff; border-radius: 8px;
  padding: 8px 10px; cursor: pointer; display: flex; flex-direction: column; gap: 3px;
}
.cq-item:hover { border-color: var(--primary); }
.cq-item.on { border-color: var(--primary); background: #eef4f6; }
.cq-top { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.cq-sub {
  font-size: 12px; color: var(--text-2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cq-sub.muted { color: var(--text-3); }
.warnb { color: #b45309; font-style: normal; font-size: 11px; }
.okb { color: #047857; font-style: normal; font-size: 11px; }

.chat {
  border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface);
  display: flex; flex-direction: column; overflow: hidden;
}
.chat-h {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 10px 12px; border-bottom: 1px solid var(--line);
}
.chat-body { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.msg { display: flex; flex-direction: column; max-width: 78%; }
.msg.patient { align-self: flex-start; }
.msg.doctor { align-self: flex-end; align-items: flex-end; }
.msg.system { align-self: center; align-items: center; max-width: 92%; }
.bubble {
  padding: 8px 11px; border-radius: 10px; background: #f2f4f6;
  font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word;
}
.msg.doctor .bubble { background: var(--primary); color: #fff; }
.msg.system .bubble {
  background: transparent; border: 1px dashed var(--line-strong);
  color: var(--text-3); font-size: 12px;
}
.mmeta { font-size: 11px; color: var(--text-3); margin-top: 3px; }
.chat-in { display: flex; gap: 8px; padding: 10px 12px; border-top: 1px solid var(--line); }
.chat-in input { flex: 1; padding: 8px 10px; border: 1px solid var(--line-strong); border-radius: 8px; }
.closebox { border-top: 1px solid var(--line); padding: 10px 12px; background: #fafbfc; }
</style>
