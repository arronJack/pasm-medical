<template>
  <div class="wrap">
    <div class="card">
      <h1>智能预问诊</h1>
      <p class="sub">面向患者 · 辅助工具，不提供诊断结论</p>

      <label>
        <span>账号</span>
        <input v-model.trim="username" placeholder="手机号 / 工号" @keyup.enter="submit" />
      </label>
      <label>
        <span>密码</span>
        <input v-model="password" type="password" placeholder="密码" @keyup.enter="submit" />
      </label>

      <div class="roles">
        <label><input type="radio" value="patient" v-model="role" /> 患者</label>
        <label><input type="radio" value="staff" v-model="role" /> 医护人员</label>
      </div>

      <button class="primary" :disabled="busy || !username || !password" @click="submit">
        {{ busy ? '登录中…' : '登 录' }}
      </button>

      <p v-if="error" class="err">{{ error }}</p>
      <p class="hint">
        本系统为**辅助工具**：输出的是「线索 + 依据 + 建议就诊科室」，
        不下诊断、不开处方。危急情况请直接拨打 120。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, setToken } from '../api'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const role = ref('patient')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const r = await api.login(username.value, password.value)
    setToken(r.token)
    localStorage.setItem('pasm_display', r.displayName || username.value)
    const next = (route.query.next as string) || (role.value === 'staff' ? '/admin' : '/consult')
    await router.push(next)
  } catch (e) {
    // 失败要如实显示，不要用"账号或密码错误"掩盖服务端异常
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.wrap { display: grid; place-items: center; height: 100%; padding: 24px; }
.card {
  width: 100%; max-width: 380px; background: var(--surface);
  border: 1px solid var(--line); border-radius: var(--radius);
  padding: 32px 32px 26px;
}
h1 { margin: 0; font-size: 20px; font-weight: 500; }
.sub { margin: 4px 0 24px; color: var(--text-2); font-size: 13px; }
label { display: block; margin-bottom: 14px; }
label > span { display: block; font-size: 12px; color: var(--text-2); margin-bottom: 5px; }
input[type='text'], input[type='password'], input:not([type]) {
  width: 100%; padding: 9px 11px; border: 1px solid var(--line-strong);
  border-radius: 8px; background: #fff; outline: none;
}
input:focus { border-color: var(--primary); }
.roles { display: flex; gap: 20px; margin: 2px 0 20px; font-size: 13px; color: var(--text-2); }
.roles label { margin: 0; display: flex; align-items: center; gap: 6px; }
.primary {
  width: 100%; padding: 10px; border: 0; border-radius: 8px;
  background: var(--primary); color: #fff; font-weight: 500;
}
.primary:hover:not(:disabled) { background: var(--primary-dark); }
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.err { margin: 12px 0 0; color: var(--danger-fg); font-size: 13px; }
.hint {
  margin: 20px 0 0; padding-top: 14px; border-top: 1px solid var(--line);
  color: var(--text-3); font-size: 12px; line-height: 1.7;
}
</style>
