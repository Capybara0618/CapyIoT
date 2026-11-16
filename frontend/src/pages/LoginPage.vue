<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Layers3, ArrowRight, LoaderCircle, Eye, EyeOff } from 'lucide-vue-next'
import { login } from '../session'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const visible = ref(false)
const pending = ref(false)
const error = ref('')

async function submit() {
  pending.value = true
  error.value = ''
  try {
    await login(username.value.trim(), password.value)
    const destination = String(route.query.redirect ?? '/dashboard')
    await router.replace(
      destination.startsWith('/') && !destination.startsWith('//') ? destination : '/dashboard',
    )
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-brand"><Layers3 :size="30" /><span>CapyIoT</span></div>
    <div class="login-layout">
      <section class="login-intro">
        <span class="intro-line"></span>
        <p>智慧园区 · 设备运维</p>
        <h1>感知设备状态<br />让处置有据可循</h1>
        <p class="intro-description">
          从设备接入、告警关联到工单处置，<br />连接园区运维的每一步。
        </p>
      </section>
      <section class="login-card" aria-labelledby="login-title">
        <h2 id="login-title">登录 CapyIoT</h2>
        <p>使用本地演示账号进入园区工作台</p>
        <form @submit.prevent="submit">
          <label for="username">账号</label
          ><input
            id="username"
            v-model="username"
            name="username"
            autocomplete="username"
            maxlength="64"
            placeholder="admin 或 operator"
            required
            :disabled="pending"
          />
          <label for="password">密码</label>
          <div class="password-field">
            <input
              id="password"
              v-model="password"
              name="password"
              :type="visible ? 'text' : 'password'"
              autocomplete="current-password"
              maxlength="256"
              placeholder="请输入本地演示密码"
              required
              :disabled="pending"
            /><button
              type="button"
              class="icon-button"
              :aria-label="visible ? '隐藏密码' : '显示密码'"
              @click="visible = !visible"
            >
              <component :is="visible ? EyeOff : Eye" :size="18" />
            </button>
          </div>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary-button login-submit" type="submit" :disabled="pending">
            <LoaderCircle v-if="pending" :size="18" class="spinning" /><span>{{
              pending ? '正在登录' : '进入工作台'
            }}</span
            ><ArrowRight v-if="!pending" :size="18" />
          </button>
        </form>
        <div class="login-help">
          演示密码请查看本地 .env 中的<br /><code>DEMO_ADMIN_PASSWORD</code> 或
          <code>DEMO_OPERATOR_PASSWORD</code>
        </div>
      </section>
    </div>
    <footer class="login-footer">CapyIoT · 云原生智慧园区物联平台</footer>
  </main>
</template>
