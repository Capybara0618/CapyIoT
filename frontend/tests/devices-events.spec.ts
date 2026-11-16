import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execute = promisify(execFile)
const values = Object.fromEntries(
  readFileSync(resolve('../.env'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const position = line.indexOf('=')
      return [line.slice(0, position), line.slice(position + 1)]
    }),
)

async function login(page: Page, username = 'admin') {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page
    .getByLabel('密码', { exact: true })
    .fill(values[`DEMO_${username.toUpperCase()}_PASSWORD`])
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

async function simulator() {
  await execute(
    'docker',
    [
      'compose',
      '--profile',
      'simulation',
      'run',
      '--rm',
      '--no-deps',
      '-T',
      'simulator',
      '--rounds',
      '1',
      '--duplicate-first',
      'true',
    ],
    { cwd: resolve('..'), timeout: 120000 },
  )
}

async function eventTotal(page: Page) {
  const token = await page.evaluate(() => sessionStorage.getItem('capyiot.token'))
  const response = await page.request.get('/api/v1/events?size=1', {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status()).toBe(200)
  return ((await response.json()) as { data: { total: number } }).data.total
}

test('registers, filters and edits a persisted device through the UI', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: '设备资产中心', exact: true }).click()
  await page.getByRole('button', { name: '注册设备', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '注册设备', exact: true })
  const code = `UI-${Date.now().toString(36).toUpperCase()}`
  await dialog.getByLabel('设备编号', { exact: true }).fill(code)
  await dialog.getByLabel('设备名称', { exact: true }).fill('浏览器注册设备')
  await dialog
    .locator('.el-form-item')
    .filter({ hasText: '所属楼宇' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: '1号楼', exact: true }).click()
  await dialog
    .locator('.el-form-item')
    .filter({ hasText: '所属楼层' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: '1层', exact: true }).click()
  await dialog.getByRole('button', { name: '确认注册', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '设备注册成功' })).toBeVisible()
  await page.getByRole('button', { name: '已保存', exact: true }).click()
  await page.getByPlaceholder('搜索名称或编号').fill(code)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.el-table__body tr')).toHaveCount(1)
  await expect(page.locator('.el-table__body')).toContainText('待上报')
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const edit = page.getByRole('dialog', { name: '编辑设备', exact: true })
  await edit.getByLabel('设备名称', { exact: true }).fill('浏览器更新后的设备')
  await edit.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.locator('.el-table__body')).toContainText('浏览器更新后的设备')
  await page.reload()
  await expect(page.getByPlaceholder('搜索名称或编号')).toHaveValue(code)
  await expect(page.locator('.el-table__body')).toContainText('浏览器更新后的设备')
  await page.getByRole('button', { name: '详情', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '设备详情' })).toContainText('浏览器更新后的设备')
  if (process.env.CAPYIOT_SCREENSHOT_DIR) {
    await page.screenshot({
      path: resolve(process.env.CAPYIOT_SCREENSHOT_DIR, 'device-detail.png'),
      animations: 'disabled',
    })
  }
})

test('pauses only browser appending and catches up after a real disconnect', async ({
  page,
  context,
}) => {
  test.setTimeout(180000)
  await login(page)
  await page.getByRole('link', { name: '实时事件中心', exact: true }).click()
  await expect(page.locator('.live-status')).toContainText('实时连接已建立')
  await expect(page.locator('.table-panel')).toHaveAttribute('aria-busy', 'false')
  const before = await eventTotal(page)
  await page.getByRole('button', { name: '暂停追加', exact: true }).click()
  await simulator()
  await expect.poll(() => eventTotal(page), { timeout: 30000 }).toBe(before + 12)
  await expect(page.locator('.panel-heading h2')).toContainText(String(before))
  await page.getByRole('button', { name: '恢复追加', exact: true }).click()
  await expect(page.locator('.panel-heading h2')).toContainText(String(before + 12))
  await context.setOffline(true)
  try {
    await simulator()
  } finally {
    await context.setOffline(false)
  }
  await expect(page.locator('.live-status')).toContainText('实时连接已建立', { timeout: 30000 })
  await expect(page.locator('.panel-heading h2')).toContainText(String(before + 24), {
    timeout: 30000,
  })
  await page.getByRole('button', { name: '详情', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '事件详情' })
  await expect(drawer).toContainText('发生时间')
  await expect(drawer).toContainText('接收时间')
  await expect(drawer.locator('.json-view')).toContainText('healthStatus')
  if (process.env.CAPYIOT_SCREENSHOT_DIR) {
    await page.screenshot({
      path: resolve(process.env.CAPYIOT_SCREENSHOT_DIR, 'event-detail.png'),
      animations: 'disabled',
    })
  }
})

test('operator can browse assets and has no registration action', async ({ page }) => {
  await login(page, 'operator')
  await page.getByRole('link', { name: '设备资产中心', exact: true }).click()
  await expect(page.getByRole('heading', { name: '设备资产中心', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '注册设备', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0)
})
