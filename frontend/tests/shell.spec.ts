import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const credentials = Object.fromEntries(
  readFileSync(resolve('../.env'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const position = line.indexOf('=')
      return [line.slice(0, position), line.slice(position + 1)]
    }),
)

test('login preserves a deep link, reports errors, and signs out', async ({ page }) => {
  await page.goto('/devices?connectivity=OFFLINE')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page.getByRole('alert')).toHaveText('账号或密码错误')
  await page.getByLabel('密码', { exact: true }).fill(credentials.DEMO_ADMIN_PASSWORD)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/devices\?connectivity=OFFLINE$/)
  await expect(page.getByText('服务已连接', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '设备资产中心' })).toBeVisible()
  await page.reload()
  await expect(page).toHaveURL(/\/devices\?connectivity=OFFLINE$/)
  await expect(page.getByRole('heading', { name: '设备资产中心' })).toBeVisible()
  await page.locator('.user-menu summary').click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
})

test('eight routes use the specified themes and fit three desktop widths', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill('operator')
  await page.getByLabel('密码', { exact: true }).fill(credentials.DEMO_OPERATOR_PASSWORD)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page.getByText('服务已连接', { exact: true })).toBeVisible()
  const routes = [
    ['/dashboard', '运营驾驶舱', 'dark'],
    ['/devices', '设备资产中心', 'light'],
    ['/topology', '网络拓扑中心', 'dark'],
    ['/events', '实时事件中心', 'light'],
    ['/alarms', '告警治理中心', 'light'],
    ['/workorders', '工单协同中心', 'light'],
    ['/scenarios', '场景模拟中心', 'dark'],
    ['/observability', '系统可观测中心', 'dark'],
  ]
  for (const width of [1440, 1280, 1024]) {
    await page.setViewportSize({ width, height: 900 })
    for (const [path, title, theme] of routes) {
      await page.getByRole('link', { name: title, exact: true }).click()
      await expect(page).toHaveURL(new RegExp(`${path}$`))
      await expect(page.getByRole('heading', { name: title })).toBeVisible()
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      if (['/scenarios', '/observability'].includes(path)) {
        await expect(page.getByText('业务功能待接入', { exact: true })).toBeVisible()
      }
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true)
      if (['/devices', '/events'].includes(path)) {
        await expect(page.locator('.table-panel')).toHaveAttribute('aria-busy', 'false')
      }
      if (
        process.env.CAPYIOT_SCREENSHOT_DIR &&
        ['/dashboard', '/devices', '/topology', '/events', '/alarms', '/workorders'].includes(path)
      ) {
        await page.screenshot({
          path: resolve(process.env.CAPYIOT_SCREENSHOT_DIR, `${path.slice(1)}-${width}.png`),
          fullPage: true,
          animations: 'disabled',
        })
      }
    }
  }
  expect(errors).toEqual([])
})

test('an invalid session returns to login when the API rejects it', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(() => {
    const session = {
      accessToken: 'invalid',
      expiresAt: new Date(Date.now() + 60000).toISOString(),
      user: { username: 'admin', role: 'ADMIN', campusId: 'CAMPUS-A' },
    }
    sessionStorage.setItem('capyiot.token', session.accessToken)
    sessionStorage.setItem('capyiot.session', JSON.stringify(session))
  })
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login\?redirect=/)
})
