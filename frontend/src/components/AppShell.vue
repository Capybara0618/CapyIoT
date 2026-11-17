<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Layers3, MapPin, Bell, ChevronDown, LogOut, ShieldCheck, RefreshCw } from 'lucide-vue-next'
import { pages } from '../router'
import { logout, session } from '../session'
import { request, type Health } from '../api'

const route = useRoute()
const router = useRouter()
const health = ref<Health | null>(null)
const checking = ref(false)
const error = ref('')
const role = computed(() => (session.value?.user.role === 'ADMIN' ? '平台管理员' : '运维人员'))

async function checkHealth() {
  checking.value = true
  error.value = ''
  try {
    health.value = await request<Health>('/health')
  } catch (cause) {
    health.value = null
    error.value = (cause as Error).message
  } finally {
    checking.value = false
  }
}

async function signOut() {
  logout()
  await router.replace('/login')
}

onMounted(checkHealth)
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" aria-label="主导航">
      <RouterLink class="brand" to="/dashboard" aria-label="CapyIoT 首页">
        <Layers3 :size="29" :stroke-width="1.7" />
        <span>CapyIoT<small>智慧园区物联平台</small></span>
      </RouterLink>
      <nav>
        <RouterLink
          v-for="page in pages"
          :key="page.path"
          :to="page.path"
          :class="{ active: route.path === page.path }"
        >
          <component :is="page.icon" :size="19" :stroke-width="1.7" />
          <span>{{ page.title }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer"><ShieldCheck :size="17" /><span>本地演示环境</span></div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="campus">
          <MapPin :size="16" /><span>演示园区</span
          ><span class="campus-code">{{ session?.user.campusId }}</span>
        </div>
        <div class="topbar-actions">
          <div class="health-indicator" role="status" :title="error || health?.checkedAt">
            <span class="status-dot" :class="{ success: health, failed: error }"></span>
            <span>{{ checking ? '正在检查连接' : health ? '服务已连接' : '服务连接失败' }}</span>
            <button
              class="icon-button"
              aria-label="重新检查服务连接"
              :disabled="checking"
              @click="checkHealth"
            >
              <RefreshCw :size="14" :class="{ spinning: checking }" />
            </button>
          </div>
          <RouterLink
            class="icon-button header-alarms"
            to="/alarms"
            aria-label="查看告警"
            title="查看告警"
          >
            <Bell :size="18" />
          </RouterLink>
          <details class="user-menu">
            <summary>
              <span class="avatar">{{
                session?.user.username.slice(0, 1).toUpperCase()
              }}</span><span>{{ role }}</span
              ><ChevronDown :size="14" />
            </summary>
            <div class="user-popover">
              <span>{{ session?.user.username }}</span><button @click="signOut">
                <LogOut :size="16" />退出登录
              </button>
            </div>
          </details>
        </div>
      </header>
      <main id="main-content" class="page-content"><slot /></main>
      <footer class="workspace-footer">
        <span>CapyIoT · 智慧园区设备运维</span><span>本地演示</span>
      </footer>
    </div>
  </div>
</template>
