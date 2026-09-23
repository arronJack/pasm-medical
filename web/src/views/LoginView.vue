<template>
  <div class="wrap">
    <div class="card">
      <h1>智能预问诊</h1>

      <!-- ★ 角色分区：仅为「演示账号入口」做 UI 分流，不决定身份。
           真实角色由后端账号决定（见 identity.ts landingFor），落地页由服务端返回的角色决定。
           不能做成「用户先选身份再登录」——那等于允许自称医护、进错工作区。 -->
      <div class="seg">
        <button
          v-for="g in GROUPS" :key="g.key" type="button"
          class="seg-btn" :class="{ on: roleGroup === g.key }"
          @click="roleGroup = g.key"
        >{{ g.label }}</button>
      </div>
      <p class="seg-hint">{{ groupHint }}</p>

      <label>
        <span>账号</span>
        <input v-model.trim="username" placeholder="手机号 / 工号" @keyup.enter="submit" />
      </label>
      <label>
        <span>密码</span>
        <input v-model="password" type="password" placeholder="密码" @keyup.enter="submit" />
      </label>

      <p v-if="demoItems.length" class="demo">
        <span>该入口演示账号：</span>
        <button
          v-for="d in demoItems" :key="d.user" type="button" class="chip" @click="fill(d.user)"
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
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, setToken } from '../api'
import { ensureIdentity, landingFor } from '../identity'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

/**
 * ★ 角色分区：仅用于把演示账号入口按「患者端 / 医护端 / 管理端」分流展示，
 * 不改变登录逻辑——最终角色由后端账号决定，落地页由服务端返回的角色决定。
 * 这是「多角色登录分开」的前端体验层：四角色不再挤在一个混杂框里，各自有清晰入口，
 * 但**绝不**允许前端声称身份（避免自称医护进错工作区）。
 */
const roleGroup = ref<'patient' | 'clinical' | 'admin'>('patient')

const GROUPS = [
  { key: 'patient', label: '患者端', hint: '面向患者 · 辅助预问诊，不提供诊断结论' },
  { key: 'clinical', label: '医护端', hint: '医护 / 科室管理 · 查看本科室数据' },
  { key: 'admin', label: '管理端', hint: '系统管理 · 全院范围，唯一可改配置与资料库' },
] as const

const groupHint = computed(
  () => GROUPS.find((g) => g.key === roleGroup.value)?.hint ?? ''
)

/**
 * ★ 演示账号提示**只在开发模式出现**（点了自动填入，省去手打），且按当前分区过滤。
 *
 * <p>写法上刻意用「脚本层三元 + 常量数组」，**不是**模板里的 `v-if`：模板 v-if 只挡住**渲染**，
 * 字面量照样进 render 函数 —— 实测生产产物里 grep 得到 `123456`（3 处）与「演示账号」。
 * 换成下面的写法后，`vite build` 把 `import.meta.env.DEV` 折叠为 `false`，
 * 三元退化成 `[]`、整段字面量被摇掉，产物里 grep 不到演示口令
 * （实测：生产 0 处 / `NODE_ENV=development` 构建 3 处，见 docs/GUIDE.md §4.4.1）。
 *
 * <p>为什么不问后端要「演示账号开没开」：那等于把「本部署开着写死的账号」告诉未认证访问者。
 * `config/TokenService.java` 刻意让登录失败**不区分**「账号不存在 / 密码错 / 演示账号是否启用」，
 * 别从别的地方把这个口子捅开。
 */
const DEMO: { user: string; label: string; group: 'patient' | 'clinical' | 'admin' }[] =
  import.meta.env.DEV
    ? [
        { user: 'patient', label: '患者 patient / 123456', group: 'patient' },
        { user: 'doctor', label: '医护 doctor / 123456（发热门诊）', group: 'clinical' },
        { user: 'deptadmin', label: '科室管理员 deptadmin / 123456（发热门诊）', group: 'clinical' },
        { user: 'staff', label: '超级管理员 staff / 123456（全院）', group: 'admin' },
      ]
    : []

const demoItems = computed(() => DEMO.filter((d) => d.group === roleGroup.value))

function fill(user: string) {
  username.value = user
  password.value = import.meta.env.DEV ? '123456' : ''
  error.value = ''
}

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const r = await api.login(username.value, password.value)
    setToken(r.token)
    // 先取回身份（带 patientRef / 科室），再由**服务端返回的角色**决定落到哪个工作区
    const id = await ensureIdentity(true)
    const next = (route.query.next as string) || landingFor(id)
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

/* ★ 角色分区控件：纯 UI 分流，不传角色给后端 */
.seg { display: flex; gap: 6px; margin: 4px 0 10px; }
.seg-btn {
  flex: 1; padding: 8px 0; border: 1px solid var(--line-strong);
  border-radius: 8px; background: transparent; color: var(--text-2);
  font-size: 13px; cursor: pointer; transition: all .15s;
}
.seg-btn:hover { border-color: var(--primary); color: var(--primary); }
.seg-btn.on {
  background: var(--primary); color: #fff; border-color: var(--primary);
}
.seg-hint { margin: 0 0 18px; font-size: 12px; color: var(--text-3); line-height: 1.5; }

label { display: block; margin-bottom: 14px; }
label > span { display: block; font-size: 12px; color: var(--text-2); margin-bottom: 5px; }
input[type='text'], input[type='password'], input:not([type]) {
  width: 100%; padding: 9px 11px; border: 1px solid var(--line-strong);
  border-radius: 8px; background: #fff; outline: none;
}
input:focus { border-color: var(--primary); }
.demo {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  margin: -10px 0 18px; font-size: 12px; color: var(--text-3);
}
.demo > span { width: 100%; }
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
