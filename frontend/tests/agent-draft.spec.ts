import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const env = Object.fromEntries(
  readFileSync(resolve('../.env'), 'utf8')
    .split(/\r?\n/)
    .filter((l) => l && !l.startsWith('#'))
    .map((l) => {
      const i = l.indexOf('=')
      return [l.slice(0, i), l.slice(i + 1)]
    }),
)
async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill('supervisor')
  await page.getByLabel('密码', { exact: true }).fill(env.DEMO_ADMIN_PASSWORD!)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}
const initial = () => ({
  draftId: 'draft-fixture',
  runId: 'run-fixture',
  revision: 2,
  contentHash: 'a'.repeat(64),
  status: 'PENDING_CONFIRMATION',
  summary: 'A区摄像机维修',
  expiresAt: new Date(Date.now() + 600000).toISOString(),
  confirmedAt: null,
  payload: {
    sourceType: 'INCIDENT',
    incidentId: 'incident-fixture',
    title: 'A区摄像机维修',
    description: '检查摄像机连接与交换机供电',
    workCategory: '设备故障维修',
    priority: 'P2',
    priorityReason: null,
    expectedResult: '恢复录像并完成核查',
    deviceItems: [{ deviceId: 'ipc-fixture', deviceRole: 'MAIN', issueDescription: '持续离线' }],
  },
  devices: [
    { deviceId: 'ipc-fixture', name: 'A区入口摄像机', deviceType: 'IPC', assetCode: 'IPC-001' },
  ],
  relatedEvidenceReferences: [
    {
      evidenceId: 'evidence-fixture',
      summary: '当前授权范围的设备状态证据',
      retrievedAt: new Date().toISOString(),
      freshnessStatus: 'FRESH',
    },
  ],
  execution: null as null | {
    executionId: string
    status: string
    workOrderId: string
    workOrderNo: string
    errorCode: null
  },
})

test('draft review requires explicit action bound to the displayed revision and links the created order', async ({
  page,
}) => {
  await login(page)
  const view = initial(),
    decisions: unknown[] = []
  await page.route('**/api/v1/agent/drafts/draft-fixture**', async (route) => {
    if (route.request().method() === 'POST') {
      decisions.push(route.request().postDataJSON())
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      view.status = 'EXECUTED'
      view.execution = {
        executionId: 'execution-fixture',
        status: 'SUCCEEDED',
        workOrderId: '101',
        workOrderNo: 'WO-101',
        errorCode: null,
      }
    }
    await route.fulfill({
      status: route.request().method() === 'POST' ? 202 : 200,
      json: { code: 'OK', message: '成功', data: view, traceId: 'fixture' },
    })
  })
  await page.goto('/agent-drafts/draft-fixture')
  await expect(page.getByRole('heading', { name: 'A区摄像机维修' })).toBeVisible()
  await expect(page.getByText('A区入口摄像机', { exact: true })).toBeVisible()
  const confirm = page.getByRole('button', { name: '确认创建正式工单' })
  await expect(confirm).toBeDisabled()
  expect(decisions).toHaveLength(0)
  await page.getByRole('checkbox').check()
  await confirm.click()
  await expect(page.getByRole('link', { name: '查看正式工单 WO-101' })).toHaveAttribute(
    'href',
    '/workorders?id=101',
  )
  expect(decisions).toEqual([{ draftRevision: 2, contentHash: 'a'.repeat(64) }])
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})

test('rejection is a separate explicit decision and mobile review remains readable', async ({
  page,
}) => {
  await login(page)
  await page.setViewportSize({ width: 390, height: 1000 })
  const view = initial(),
    decisions: unknown[] = []
  await page.route('**/api/v1/agent/drafts/draft-fixture**', async (route) => {
    if (route.request().method() === 'POST') {
      expect(route.request().url()).toContain('/reject')
      decisions.push(route.request().postDataJSON())
      view.status = 'REJECTED'
    }
    await route.fulfill({ json: { code: 'OK', message: '成功', data: view, traceId: 'fixture' } })
  })
  await page.goto('/agent-drafts/draft-fixture')
  await expect(page.getByRole('heading', { name: 'A区摄像机维修' })).toBeVisible()
  await page.getByLabel('拒绝原因（可选）').fill('设备范围需要重新核对')
  await page.screenshot({
    path: resolve('../var/verification/stage4-draft-390.png'),
    fullPage: true,
  })
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy()
  await page.getByRole('button', { name: '拒绝此草稿' }).click()
  await expect(page.getByText('已拒绝', { exact: true })).toBeVisible()
  expect(decisions).toEqual([
    { draftRevision: 2, contentHash: 'a'.repeat(64), reason: '设备范围需要重新核对' },
  ])
})

test('a stale revision conflict removes confirmation controls until a fresh review', async ({
  page,
}) => {
  await login(page)
  await page.route('**/api/v1/agent/drafts/draft-fixture**', async (route) => {
    await route.fulfill(
      route.request().method() === 'POST'
        ? {
            status: 409,
            json: {
              code: 'DRAFT_REVISION_MISMATCH',
              message: '草稿版本已变化，请重新审阅',
              details: {},
              traceId: 'fixture',
            },
          }
        : { json: { code: 'OK', message: '成功', data: initial(), traceId: 'fixture' } },
    )
  })
  await page.goto('/agent-drafts/draft-fixture')
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '确认创建正式工单' }).click()
  await expect(page.getByRole('alert')).toContainText('草稿版本已变化')
  await expect(page.getByRole('button', { name: '确认创建正式工单' })).toHaveCount(0)
  await page.getByRole('button', { name: '刷新草稿' }).click()
  await expect(page.getByRole('button', { name: '确认创建正式工单' })).toBeDisabled()
})
