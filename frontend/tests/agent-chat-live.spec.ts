import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
test.skip(
  process.env.CAPYIOT_AGENT_LIVE_TESTS !== 'true',
  'Requires the isolated Agent verification profile',
)
const env = Object.fromEntries(
  readFileSync(resolve(process.env.CAPYIOT_CREDENTIAL_FILE ?? '../.env'), 'utf8')
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
async function api(page: Page, path: string) {
  return page.evaluate(async (path) => {
    const response = await fetch('/api/v1' + path, {
      headers: { Authorization: 'Bearer ' + sessionStorage.getItem('capyiot.token') },
    })
    return { status: response.status, data: (await response.json()).data }
  }, path)
}
test('real UI query, follow-up draft, explicit confirmation and refresh recover a single formal work order', async ({
  page,
}) => {
  await login(page)
  const before = (await api(page, '/workorders')).data.total
  await page.getByRole('link', { name: 'AI 运维助手', exact: true }).click()
  await page
    .getByLabel('运维问题', { exact: true })
    .fill('A 区为什么多台摄像头离线？请查询证据并区分事实和推断。')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(
    page.getByText('已查询当前可见摄像机的连接状态与状态历史', { exact: true }),
  ).toBeVisible({ timeout: 45000 })
  expect((await api(page, '/workorders')).data.total).toBe(before)
  await page.reload()
  await expect(
    page.getByText('已查询当前可见摄像机的连接状态与状态历史', { exact: true }),
  ).toBeVisible()
  await page
    .getByLabel('运维问题', { exact: true })
    .fill('draft-fixture MANUAL 请为这些视频设备准备一份独立维修工单草稿，等我在页面确认。')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const card = page.getByRole('region', { name: '工单草稿审阅' })
  await expect(card.getByRole('heading', { name: 'A区视频设备维修草稿' })).toBeVisible({
    timeout: 45000,
  })
  expect((await api(page, '/workorders')).data.total).toBe(before)
  await expect(card.getByRole('button', { name: '确认创建正式工单', exact: true })).toBeDisabled()
  await card.getByRole('checkbox').check()
  await card.getByRole('button', { name: '确认创建正式工单', exact: true }).click()
  await expect(card.getByRole('link', { name: /查看正式工单 WO-/ })).toBeVisible({ timeout: 20000 })
  expect((await api(page, '/workorders')).data.total).toBe(before + 1)
  await page.reload()
  await expect(page.getByRole('link', { name: /查看正式工单 WO-/ })).toBeVisible()
  expect((await api(page, '/workorders')).data.total).toBe(before + 1)
  await page.screenshot({
    path: resolve(
      process.env.CAPYIOT_SCREENSHOT_DIR ?? '../var/verification',
      'stage5-chat-live-1440.png',
    ),
    fullPage: true,
  })
})
test('leaving an active POST stream cancels the run and reopening restores its final status', async ({
  page,
}) => {
  await login(page)
  await page.goto('/assistant')
  await page.getByLabel('运维问题', { exact: true }).fill('slow-fixture 请查询设备状态')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.locator('.agent-progress')).toBeVisible({ timeout: 15000 })
  const url = page.url(),
    id = new URL(url).pathname.split('/').at(-1)
  let runId: string | undefined
  await expect
    .poll(
      async () => {
        const result = await api(page, '/agent/conversations/' + id + '?latest=true')
        runId = result.data?.messages[0]?.runId
        return runId
      },
      { timeout: 10000 },
    )
    .toBeTruthy()
  await page.getByRole('link', { name: '设备资产中心', exact: true }).click()
  await expect
    .poll(async () => (await api(page, '/agent/runs/' + runId)).data.status, { timeout: 15000 })
    .toBe('CANCELLED')
  await page.goto(url)
  await expect(page.getByText('已停止', { exact: true })).toBeVisible()
  await expect(page.getByLabel('运维问题', { exact: true })).toBeEnabled()
})
