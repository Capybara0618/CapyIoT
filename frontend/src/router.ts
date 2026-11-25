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
  ShieldCheck,
} from 'lucide-vue-next'
import { clearSession, session } from './session'
import LoginPage from './pages/LoginPage.vue'

const featurePages = {
  '/devices': () => import('./pages/VideoAssetsPage.vue'),
  '/events': () => import('./pages/VideoEventsPage.vue'),
  '/dashboard': () => import('./pages/VideoDashboardPage.vue'),
  '/topology': () => import('./pages/VideoTopologyPage.vue'),
  '/alarms': () => import('./pages/VideoIncidentsPage.vue'),
  '/workorders': () => import('./pages/VideoWorkOrdersPage.vue'),
  '/scenarios': () => import('./pages/VideoConnectionPage.vue'),
  '/observability': () => import('./pages/ObservabilityPage.vue'),
  '/administration': () => import('./pages/AdministrationPage.vue'),
}

export const pages = [
  {
    path: '/administration',
    title: '账号与授权',
    icon: ShieldCheck,
    description: '平台账号、角色与数据范围管理',
  },
  {
    path: '/dashboard',
    title: '运营驾驶舱',
    icon: LayoutDashboard,
    description: '视频设备、故障研判与维修任务概览',
  },
  {
    path: '/devices',
    title: '设备资产中心',
    icon: HardDrive,
    description: '设备资产与运行状态管理',
  },
  {
    path: '/topology',
    title: '网络拓扑中心',
    icon: Network,
    description: '网络依赖与设备影响范围',
  },
  {
    path: '/events',
    title: '设备事件中心',
    icon: Radio,
    description: '设备事件与处理链路查询',
  },
  {
    path: '/alarms',
    title: '异常与故障中心',
    icon: BellRing,
    description: '设备异常、故障研判与证据核查',
  },
  {
    path: '/workorders',
    title: '工单协同中心',
    icon: ClipboardList,
    description: '从故障处置到恢复验证',
  },
  {
    path: '/scenarios',
    title: '设备接入说明',
    icon: FlaskConical,
    description: '虚拟设备接入和运维流程',
  },
  {
    path: '/observability',
    title: '系统可观测中心',
    icon: Activity,
    description: '服务运行与消息链路观测',
  },
] as const

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: LoginPage, meta: { title: '登录' } },
    {
      path: '/agent-drafts/:draftId?',
      component: () => import('./pages/AgentDraftPage.vue'),
      meta: { title: '工单草稿审阅', description: '核查设备范围和证据，确认后创建正式维修工单' },
    },
    ...pages.map((page) => ({
      path: page.path,
      component: featurePages[page.path],
      meta: page,
    })),
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  if (session.value && !(Date.parse(session.value.expiresAt) > Date.now())) clearSession()
  if (!session.value && to.path !== '/login')
    return { path: '/login', query: { redirect: to.fullPath } }
  if (session.value && to.path === '/login') return '/dashboard'
  document.documentElement.dataset.theme = 'light'
  document.title = `${String(to.meta.title)} · CapyIoT`
})

window.addEventListener('capyiot:unauthorized', () => {
  clearSession()
  if (router.currentRoute.value.path === '/login') return
  void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})
