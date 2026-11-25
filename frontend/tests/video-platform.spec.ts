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
async function login(page: Page, name: string) {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(name)
  await page
    .getByLabel('密码', { exact: true })
    .fill(
      ['admin', 'supervisor'].includes(name) ? env.DEMO_ADMIN_PASSWORD : env.DEMO_OPERATOR_PASSWORD,
    )
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

test('business pages keep the same light theme and navigation at desktop and mobile widths', async ({
  page,
}) => {
  await login(page, 'supervisor')
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: 950 })
    for (const path of [
      '/dashboard',
      '/devices',
      '/topology',
      '/events',
      '/alarms',
      '/workorders',
      '/scenarios',
    ]) {
      await page.goto(path)
      await expect(page.locator('.page-header h1')).toBeVisible()
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
      await expect(page.locator('.video-error')).toHaveCount(0)
    }
  }
})

test('an operator can inspect scope-limited assets but cannot register or replace credentials', async ({
  page,
}) => {
  await login(page, 'operator')
  await page.goto('/devices')
  await expect(page.getByRole('heading', { name: '设备资产中心' })).toBeVisible()
  await expect(page.getByText('注册设备', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '更换设备密钥' })).toHaveCount(0)
  await expect(page.locator('.video-error')).toHaveCount(0)
})

test('platform administrators manage identities without implicit business data access', async ({
  page,
}) => {
  await login(page, 'admin')
  await page.getByRole('link', { name: '管理用户与授权' }).click()
  await expect(page.getByRole('heading', { name: '账号与授权' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'supervisor', exact: true })).toBeVisible()
  await page.goto('/devices')
  await expect(page.locator('.video-error')).toContainText(/无权|授权|允许/)
})

test('new assets expose a one-time credential and remain unknown until real input arrives', async ({
  page,
}) => {
  await login(page, 'supervisor')
  const code = 'UI-IPC-' + Date.now()
  await page.goto('/devices')
  const registration = page
    .locator('details')
    .filter({ has: page.locator('summary', { hasText: '注册设备' }) })
  await registration.locator('summary').click()
  await registration.getByLabel('资产编号', { exact: true }).fill(code)
  await registration.getByLabel('设备名称', { exact: true }).fill(code)
  const area = registration.getByLabel('安装区域', { exact: true })
  const option = await area.locator('option').nth(1).getAttribute('value')
  expect(option).toBeTruthy()
  await area.selectOption(option!)
  await registration.getByRole('button', { name: '注册并获取密钥' }).click()
  await expect(page.getByRole('heading', { name: '请立即保存设备密钥' })).toBeVisible()
  expect(
    await page
      .locator('.secret')
      .evaluate((el) => /^[A-Za-z0-9_-]{30,}$/.test(el.textContent?.trim() ?? '')),
  ).toBe(true)
  await page.getByRole('button', { name: '已保存，隐藏密钥' }).click()
  await expect(page.locator('.secret')).toHaveCount(0)
  await page.getByLabel('搜索设备').fill(code)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const row = page.locator('tbody tr').filter({ hasText: code }).first()
  await expect(row).toContainText('待投用')
  await expect(row).toContainText('未知')
  await expect(row).toContainText('暂无数据')
  await page.reload()
  await expect(page.locator('.secret')).toHaveCount(0)
})
