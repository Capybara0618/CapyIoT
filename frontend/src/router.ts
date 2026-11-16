import { createRouter, createWebHistory } from 'vue-router'
import {
  LayoutDashboard,
  HardDrive,
  Network,
  Radio,
  BellRing,
  ClipboardList,
  FlaskConical,
  Activity,
} from 'lucide-vue-next'
import { logout, session } from './session'
import PlaceholderPage from './pages/PlaceholderPage.vue'
import LoginPage from './pages/LoginPage.vue'
import DevicesPage from './pages/DevicesPage.vue'
import EventsPage from './pages/EventsPage.vue'

const featurePages = {
  '/devices': DevicesPage,
  '/events': EventsPage,
  '/dashboard': () => import('./pages/DashboardPage.vue'),
  '/topology': () => import('./pages/TopologyPage.vue'),
  '/alarms': () => import('./pages/AlarmsPage.vue'),
  '/workorders': () => import('./pages/WorkOrdersPage.vue'),
}

export const pages = [
  {
    path: '/dashboard',
    title: '运营驾驶舱',
    theme: 'dark',
    icon: LayoutDashboard,
    description: '园区设备、告警与处置概览',
  },
  {
    path: '/devices',
    title: '设备资产中心',
    theme: 'light',
    icon: HardDrive,
    description: '设备资产与运行状态管理',
  },
  {
    path: '/topology',
    title: '网络拓扑中心',
    theme: 'dark',
    icon: Network,
    description: '网络依赖与设备影响范围',
  },
  {
    path: '/events',
    title: '实时事件中心',
    theme: 'light',
    icon: Radio,
    description: '设备事件与处理链路查询',
  },
  {
    path: '/alarms',
    title: '告警治理中心',
    theme: 'light',
    icon: BellRing,
    description: '主子告警、关联证据与处置',
  },
  {
    path: '/workorders',
    title: '工单协同中心',
    theme: 'light',
    icon: ClipboardList,
    description: '从故障处置到恢复验证',
  },
  {
    path: '/scenarios',
    title: '场景模拟中心',
    theme: 'dark',
    icon: FlaskConical,
    description: '园区故障场景与恢复演示',
  },
  {
    path: '/observability',
    title: '系统可观测中心',
    theme: 'dark',
    icon: Activity,
    description: '服务运行与消息链路观测',
  },
] as const

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: LoginPage, meta: { title: '登录', theme: 'dark' } },
    ...pages.map((page) => ({
      path: page.path,
      component: featurePages[page.path as keyof typeof featurePages] ?? PlaceholderPage,
      meta: page,
    })),
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  if (session.value && !(Date.parse(session.value.expiresAt) > Date.now())) logout()
  if (!session.value && to.path !== '/login')
    return { path: '/login', query: { redirect: to.fullPath } }
  if (session.value && to.path === '/login') return '/dashboard'
  document.documentElement.dataset.theme = String(to.meta.theme ?? 'dark')
  document.title = `${String(to.meta.title)} · CapyIoT`
})

window.addEventListener('capyiot:unauthorized', () => {
  logout()
  if (router.currentRoute.value.path === '/login') return
  void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})
