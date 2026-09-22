<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="logo">+</span>
        <span>智能预问诊</span>
        <span class="tag">辅助工具 · 非诊断</span>
      </div>
      <nav v-if="me">
        <RouterLink v-if="side === 'patient'" to="/consult">问诊</RouterLink>
        <RouterLink v-else to="/admin">后台</RouterLink>
      </nav>
      <div class="spacer" />
      <span v-if="me" class="who">
        {{ me.displayName || me.username }}
        <em class="chip-role">{{ me.roleLabel }}</em>
        <em class="chip-scope">{{ me.scopeLabel }}</em>
      </span>
      <button v-if="logged" class="link" @click="logout">退出</button>
    </header>
    <main><RouterView /></main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { clearToken, getToken, type IdentityView } from './api'
import { ensureIdentity, forgetIdentity, sideOf } from './identity'

const router = useRouter()
const tick = ref(0)
const logged = computed(() => {
  void tick.value
  return !!getToken()
})

/** 身份只有一个真相源：服务端 /api/auth/me（见 identity.ts 的说明）。 */
const me = ref<IdentityView | null>(null)
const side = computed(() => sideOf(me.value))

async function syncIdentity() {
  me.value = await ensureIdentity()
  tick.value++
}

onMounted(syncIdentity)
// 登录/换身份后路由会变，这里跟着刷新一次，避免顶栏显示上一个账号
router.afterEach(syncIdentity)

function logout() {
  clearToken()
  forgetIdentity()
  me.value = null
  tick.value++
  router.push('/login')
}
</script>

<style>
/* 设计令牌：整套界面只用这几个变量，保证"专业、克制"的统一观感 */
:root {
  --bg: #f6f7f9;
  --surface: #ffffff;
  --line: #e3e6ea;
  --line-strong: #cbd2da;
  --text: #1f2328;
  --text-2: #63696f;
  --text-3: #8b9196;
  --primary: #1d6f8b;
  --primary-dark: #175a70;
  --danger-bg: #fdecec;
  --danger-fg: #a32d2d;
  --warn-bg: #fdf6e7;
  --warn-fg: #8a5a10;
  --ok-bg: #ecf6ef;
  --ok-fg: #2c6e49;
  --radius: 10px;
}
* { box-sizing: border-box; }
html, body, #app { height: 100%; margin: 0; }
body {
  background: var(--bg);
  color: var(--text);
  font: 14px/1.6 -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
}
button { font: inherit; cursor: pointer; }
input, textarea, select { font: inherit; color: inherit; }
</style>

<style scoped>
.shell { display: flex; flex-direction: column; height: 100%; }
.topbar {
  display: flex; align-items: center; gap: 16px;
  padding: 0 20px; height: 52px; flex: 0 0 52px;
  background: var(--surface); border-bottom: 1px solid var(--line);
}
.brand { display: flex; align-items: center; gap: 8px; font-weight: 500; }
.logo {
  width: 22px; height: 22px; border-radius: 6px; background: var(--primary);
  color: #fff; display: grid; place-items: center; font-size: 15px;
}
.tag {
  font-size: 12px; font-weight: 400; color: var(--warn-fg);
  background: var(--warn-bg); padding: 1px 7px; border-radius: 5px;
}
nav { display: flex; gap: 4px; }
nav a {
  padding: 5px 12px; border-radius: 7px; color: var(--text-2);
  text-decoration: none; font-size: 13px;
}
nav a.router-link-active { background: #eef4f6; color: var(--primary-dark); }
.spacer { flex: 1; }
.who { color: var(--text-2); font-size: 13px; display: flex; align-items: center; gap: 6px; }
.chip-role {
  font-style: normal; font-size: 12px; padding: 1px 7px; border-radius: 5px;
  background: #eef4f6; color: var(--primary-dark);
}
/* ★ 数据范围必须一直看得见：否则"本科室的数字"会被当成"全院的数字" */
.chip-scope {
  font-style: normal; font-size: 12px; padding: 1px 7px; border-radius: 5px;
  background: var(--bg); color: var(--text-3); border: 1px solid var(--line);
}
.link { border: 0; background: none; color: var(--text-3); font-size: 13px; padding: 4px 6px; }
.link:hover { color: var(--danger-fg); }
main { flex: 1; min-height: 0; }
</style>
