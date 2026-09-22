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

      <p v-if="DEMO_HINT.items.length" class="demo">
        <span>{{ DEMO_HINT.title }}</span>
        <button
          v-for="d in DEMO_HINT.items" :key="d.role" type="button" class="chip" @click="fill(d.role)"
        >{{ d.label }}</button>
      </p>

      <button class="primary" :disabled="busy || !username || !password" @click="submit">
        {{ busy ? '登录中…' : '登 录' }}
      </button>

      <p v-if="error" class="err">{{ error }}</p>
      <p class="hint">
        本系统为<strong>辅助工具</strong>：输出的是「线索 + 依据 + 建议就诊科室」，
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

/**
 * ★ 演示账号提示**只在开发模式出现**（点了自动填入，省去手打）。
 *
 * <p>写法上刻意用「脚本层三元 + 常量数组」，**不是**模板里的 `v-if`：模板 v-if 只挡住**渲染**，
 * 字面量照样进 render 函数 —— 实测生产产物里 grep 得到 `123456`（3 处）与「演示账号」。
 * 换成下面的写法后，`vite build` 把 `import.meta.env.DEV` 折叠为 `false`，
 * 三元退化成 `{ title: '', items: [] }`、整段字面量被摇掉，产物里 grep 不到演示口令
 * （实测：生产 0 处 / `NODE_ENV=development` 构建 3 处，见 docs/GUIDE.md §4.4.1）。
 *
 * <p>为什么不问后端要「演示账号开没开」：那等于把「本部署开着写死的账号」告诉未认证访问者。
 * `config/TokenService.java` 刻意让登录失败**不区分**「账号不存在 / 密码错 / 演示账号是否启用」，
 * 别从别的地方把这个口子捅开。
 */
const DEMO_HINT: { title: string; items: { role: 'staff' | 'patient'; label: string }[] } =
  import.meta.env.DEV
    ? {
        title: '演示账号',
        items: [
          { role: 'staff', label: '医护人员 staff / 123456' },
          { role: 'patient', label: '患者 patient / 123456' },
        ],
      }
    : { title: '', items: [] }

/** role 决定登录后落到 /admin（医护后台）还是 /consult（患者工作台）。 */
function fill(who: 'staff' | 'patient') {
  username.value = who
  password.value = import.meta.env.DEV ? '123456' : ''
  role.value = who
  error.value = ''
}

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
.demo {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  margin: -10px 0 18px; font-size: 12px; color: var(--text-3);
}
.chip {
  padding: 2px 9px; border: 1px dashed var(--line-strong); border-radius: 999px;
  background: transparent; color: var(--text-2); font-size: 12px; cursor: pointer;
}
.chip:hover { border-color: var(--primary); color: var(--primary); }
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
