<template>
  <div class="imaging">
    <p class="notice">
      ⚠ 本系统<b>只归档与转交影像，不提供任何自动分析或诊断结论</b>。请由具备资质的医师阅片。
    </p>

    <!-- 患者：上传本人影像 + 查看本人归档 -->
    <template v-if="side === 'patient'">
      <section class="card">
        <h2>上传我的影像</h2>
        <p class="hint">原始文件仅在本地打包后原样传回服务端存档，服务端不解析像素、不读片。</p>
        <div class="form">
          <label>检查类型
            <input v-model="form.modality" placeholder="如 CT / MR / X 线 / 超声" />
          </label>
          <label>部位
            <input v-model="form.bodyPart" placeholder="如 头部 / 胸部" />
          </label>
          <label>检查日期
            <input v-model="form.studyDate" placeholder="如 2026-11-06" />
          </label>
          <label>院内检查号
            <input v-model="form.accessionNo" placeholder="选填" />
          </label>
          <label>接收科室
            <input v-model="form.department" placeholder="选填（不填则由医护分诊）" />
          </label>
          <label class="file">选择文件
            <input ref="fileInput" type="file" @change="onFile" />
          </label>
        </div>
        <p v-if="upMsg" class="hint" :class="upBad ? 'warn' : ''">{{ upMsg }}</p>
        <button class="primary" :disabled="!file || busy" @click="upload">上传并归档</button>
      </section>

      <section class="card">
        <h2>我的影像归档</h2>
        <p v-if="err" class="hint warn">{{ err }}</p>
        <table v-if="mine.length">
          <thead><tr><th>类型</th><th>部位</th><th>日期</th><th>大小</th><th>状态</th><th></th></tr></thead>
          <tbody>
            <tr v-for="s in mine" :key="s.id">
              <td>{{ s.modality }}</td>
              <td>{{ s.bodyPart }}</td>
              <td>{{ s.studyDate }}</td>
              <td>{{ fmtSize(s.byteSize) }}</td>
              <td><span class="badge" :class="s.status === 'HANDED_OFF' ? 'ok' : 'warn'">
                {{ s.status === 'HANDED_OFF' ? '已转交 ' + s.handoffDepartment : '待转交' }}</span></td>
              <td><button class="ghost" @click="api.imagingContent(s.id, s.fileName)">下载原始文件</button></td>
            </tr>
          </tbody>
        </table>
        <p v-else class="hint">还没有归档记录。</p>
      </section>
    </template>

    <!-- 医护侧：待转交队列 + 按患者检索 -->
    <template v-else>
      <section class="card">
        <h2>待转交队列</h2>
        <p class="hint">把患者上传的影像转交给相应科室；本系统不读片，转交即「请医师阅片」。</p>
        <table v-if="queue.length">
          <thead><tr><th>ID</th><th>患者</th><th>类型</th><th>部位</th><th>科室</th><th>上传者</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="s in queue" :key="s.id">
              <td class="mono">{{ s.id }}</td>
              <td class="mono">{{ s.patientRef }}</td>
              <td>{{ s.modality }}</td>
              <td>{{ s.bodyPart }}</td>
              <td>{{ s.department || '未指定' }}</td>
              <td>{{ s.uploadedBy }}</td>
              <td>
                <button class="ghost" @click="api.imagingContent(s.id, s.fileName)">下载</button>
                <button class="primary small" @click="handoff(s)">转交</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="hint">队列为空。</p>
      </section>

      <section class="card">
        <h2>按患者检索</h2>
        <div class="row">
          <input v-model="searchRef" placeholder="患者档案标识，如 demo-patient-001" />
          <button class="ghost" @click="search">查询</button>
        </div>
        <p v-if="searchErr" class="hint warn">{{ searchErr }}</p>
        <table v-if="found.length">
          <thead><tr><th>ID</th><th>类型</th><th>部位</th><th>状态</th><th>转交科室</th><th></th></tr></thead>
          <tbody>
            <tr v-for="s in found" :key="s.id">
              <td class="mono">{{ s.id }}</td>
              <td>{{ s.modality }}</td>
              <td>{{ s.bodyPart }}</td>
              <td><span class="badge" :class="s.status === 'HANDED_OFF' ? 'ok' : 'warn'">
                {{ s.status === 'HANDED_OFF' ? '已转交' : '待转交' }}</span></td>
              <td>{{ s.handoffDepartment || '—' }}</td>
              <td><button class="ghost" @click="api.imagingContent(s.id, s.fileName)">下载</button>
                <button v-if="s.status !== 'HANDED_OFF'" class="primary small" @click="handoff(s)">转交</button></td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <!-- 转交弹层 -->
    <div v-if="handoffTarget" class="modal">
      <div class="modal-box">
        <div class="cq-h">转交影像 #{{ handoffTarget.id }}</div>
        <label>目标科室
          <input v-model="handoffDept" placeholder="必须是已启用的科室，如 发热门诊" />
        </label>
        <label>备注
          <input v-model="handoffNote" placeholder="选填" />
        </label>
        <p v-if="handoffMsg" class="hint" :class="handoffBad ? 'warn' : 'ok'">{{ handoffMsg }}</p>
        <div class="modal-actions">
          <button class="ghost" @click="handoffTarget = null">取消</button>
          <button class="primary" :disabled="!handoffDept.trim() || handoffBusy" @click="doHandoff">确认转交</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type ImagingStudy } from '../api'
import { currentIdentity, sideOf } from '../identity'

const me = ref(currentIdentity())
const side = computed(() => sideOf(me.value))

const mine = ref<ImagingStudy[]>([])
const queue = ref<ImagingStudy[]>([])
const found = ref<ImagingStudy[]>([])
const err = ref('')
const busy = ref(false)
const upMsg = ref('')
const upBad = ref(false)
const file = ref<File | null>(null)
const form = ref({ modality: '', bodyPart: '', studyDate: '', accessionNo: '', department: '' })

const searchRef = ref('')
const searchErr = ref('')
const handoffTarget = ref<ImagingStudy | null>(null)
const handoffDept = ref('')
const handoffNote = ref('')
const handoffBusy = ref(false)
const handoffMsg = ref('')
const handoffBad = ref(false)

function fmtSize(n: number): string {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

const fileInput = ref<HTMLInputElement | null>(null)

function onFile(e: Event): void {
  const t = e.target as HTMLInputElement
  file.value = t.files && t.files[0] ? t.files[0] : null
}

/** 把 ArrayBuffer 分块转 Base64（避免大文件展开成参数时栈溢出）。 */
function toBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let bin = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + CHUNK)))
  }
  return btoa(bin)
}

async function upload(): Promise<void> {
  if (!file.value) return
  busy.value = true
  upMsg.value = ''
  try {
    const buf = await file.value.arrayBuffer()
    const b64 = toBase64(buf)
    await api.imagingArchive({
      modality: form.value.modality.trim(),
      bodyPart: form.value.bodyPart.trim(),
      fileName: file.value.name,
      contentType: file.value.type || 'application/octet-stream',
      contentBase64: b64,
      studyDate: form.value.studyDate.trim() || undefined,
      accessionNo: form.value.accessionNo.trim() || undefined,
      department: form.value.department.trim() || undefined,
    })
    upMsg.value = '已归档。注意：本系统不分析影像，请由医师阅片。'
    upBad.value = false
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    await loadMine()
  } catch (e) {
    upMsg.value = '上传失败：' + (e as Error).message
    upBad.value = true
  } finally {
    busy.value = false
  }
}

async function loadMine(): Promise<void> {
  mine.value = []
  err.value = ''
  try {
    mine.value = await api.imagingList()
  } catch (e) {
    err.value = (e as Error).message
  }
}

async function loadQueue(): Promise<void> {
  queue.value = []
  try {
    queue.value = await api.imagingQueue()
  } catch (e) {
    err.value = (e as Error).message
  }
}

async function search(): Promise<void> {
  found.value = []
  searchErr.value = ''
  const r = searchRef.value.trim()
  if (!r) {
    searchErr.value = '请输入患者档案标识'
    return
  }
  try {
    found.value = await api.imagingList(r)
  } catch (e) {
    searchErr.value = (e as Error).message
  }
}

function handoff(s: ImagingStudy): void {
  handoffTarget.value = s
  handoffDept.value = s.department || ''
  handoffNote.value = ''
  handoffMsg.value = ''
}

async function doHandoff(): Promise<void> {
  if (!handoffTarget.value) return
  handoffBusy.value = true
  handoffMsg.value = ''
  try {
    await api.imagingHandoff(handoffTarget.value.id, handoffDept.value.trim(), handoffNote.value.trim() || undefined)
    handoffMsg.value = '已转交'
    handoffBad.value = false
    handoffTarget.value = null
    await loadQueue()
  } catch (e) {
    handoffMsg.value = '转交失败：' + (e as Error).message
    handoffBad.value = true
  } finally {
    handoffBusy.value = false
  }
}

onMounted(() => {
  if (side.value === 'patient') loadMine()
  else loadQueue()
})
</script>

<style scoped>
.imaging { max-width: 960px; margin: 0 auto; padding: 16px; }
.notice { background: var(--warn-bg); color: var(--warn-fg); border: 1px solid var(--line);
  border-radius: var(--radius); padding: 10px 12px; margin: 0 0 14px; font-size: 13px; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 14px; margin-bottom: 14px; }
.card h2 { margin: 0 0 8px; font-size: 16px; }
.hint { color: var(--text-2); font-size: 13px; margin: 6px 0; }
.hint.warn { color: var(--danger-fg); }
.hint.ok { color: var(--ok-fg); }
.form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.form label, .form .file { display: flex; flex-direction: column; font-size: 13px; color: var(--text-2); gap: 4px; }
.form input, .row input, .modal-box input { padding: 7px 9px; border: 1px solid var(--line-strong);
  border-radius: 8px; font-size: 14px; }
.row { display: flex; gap: 8px; margin-bottom: 8px; }
.row input { flex: 1; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { text-align: left; padding: 7px 8px; border-bottom: 1px solid var(--line); }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.badge { font-size: 12px; padding: 2px 7px; border-radius: 999px; }
.badge.warn { background: var(--warn-bg); color: var(--warn-fg); }
.badge.ok { background: var(--ok-bg); color: var(--ok-fg); }
button { cursor: pointer; border: none; border-radius: 8px; padding: 7px 12px; font-size: 13px; }
button.primary { background: var(--primary); color: #fff; }
button.primary:disabled { opacity: .5; }
button.ghost { background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); }
button.small { padding: 4px 9px; font-size: 12px; margin-left: 4px; }
.modal { position: fixed; inset: 0; background: rgba(0,0,0,.3); display: flex; align-items: center; justify-content: center; }
.modal-box { background: var(--surface); border-radius: var(--radius); padding: 18px; width: 360px;
  display: flex; flex-direction: column; gap: 10px; }
.modal-box label { display: flex; flex-direction: column; font-size: 13px; gap: 4px; color: var(--text-2); }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
