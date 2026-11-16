import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { execFile } from 'node:child_process'
import { resolve } from 'node:path'
import { promisify } from 'node:util'
import type { Alarm, AlarmDetail, Overview } from '../src/alarms'
import type { Page as Results } from '../src/domain'

const run = promisify(execFile)
const config = Object.fromEntries(
  readFileSync(resolve('../.env'), 'utf8')
    .split(/\r?\n/)
    .flatMap((line) => {
      if (!line || line.startsWith('#')) return []
      const equals = line.indexOf('=')
      return [[line.slice(0, equals), line.slice(equals + 1)]]
    }),
)

async function api<T>(page: Page, path: string) {
  const token = await page.evaluate(() => sessionStorage.getItem('capyiot.token'))
  const response = await page.request.get('/api/v1' + path, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.ok()).toBe(true)
  return ((await response.json()) as { data: T }).data
}

async function screenshot(page: Page, name: string) {
  const directory = process.env.CAPYIOT_SCREENSHOT_DIR
  if (directory) {
    await page.screenshot({
      path: resolve(directory, name),
      animations: 'disabled',
      fullPage: true,
    })
  }
}

test('real MQTT demo groups confirmed and suspected outages, preserves temperature evidence and recovers', async ({
  page,
}) => {
  test.setTimeout(155000)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill('operator')
  await page.getByLabel('密码', { exact: true }).fill(config.DEMO_OPERATOR_PASSWORD)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.locator('.live-status')).toContainText('实时连接已建立')
  const identities = (
    JSON.parse(readFileSync(resolve('../var/demo-devices.json'), 'utf8')) as {
      deviceCode: string
      deviceId: string
    }[]
  ).map(({ deviceCode, deviceId }) => [deviceCode, deviceId] as const)
  const devices = new Map(identities)
  const confirmedNode = devices.get('DEMO-1-SW')!
  const suspectedNode = devices.get('DEMO-2-SW')!
  const sensor = devices.get('DEMO-3-SEN')!

  // Observe the running scenario through the usual API and UI, then let the same process recover.
  const scenario = run(
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
      '--scenario',
      'alarm-demo',
    ],
    { cwd: resolve('..'), timeout: 135000 },
  ).then(
    () => undefined,
    (error: Error) => error,
  )
  try {
    let confirmed: Alarm | undefined
    let suspected: Alarm | undefined
    let temperature: Alarm | undefined
    await expect
      .poll(
        async () => {
          const rows = (await api<Results<Alarm>>(page, '/alarms?size=100')).items.filter(
            (alarm) => alarm.status !== 'RECOVERED',
          )
          confirmed = rows.find(
            (alarm) =>
              alarm.deviceId === confirmedNode && alarm.relationKind === 'CONFIRMED_UPSTREAM',
          )
          suspected = rows.find(
            (alarm) =>
              alarm.deviceId === suspectedNode && alarm.relationKind === 'SUSPECTED_UPSTREAM',
          )
          temperature = rows.find(
            (alarm) => alarm.deviceId === sensor && alarm.alarmType === 'SENSOR_THRESHOLD_BREACH',
          )
          return !!(
            confirmed?.affectedCount === 2 &&
            suspected?.affectedCount === 2 &&
            temperature?.parentAlarmId === null
          )
        },
        { timeout: 75000, intervals: [500, 1000, 2000] },
      )
      .toBe(true)

    const confirmedDetail = await api<AlarmDetail>(page, `/alarms/${confirmed!.id}`)
    expect(confirmedDetail.children.items.map((alarm) => alarm.alarmType).sort()).toEqual([
      'DEVICE_OFFLINE',
      'DEVICE_OFFLINE',
      'NETWORK_UPSTREAM_FAILURE',
    ])
    expect(
      confirmedDetail.evidence.items.some((item) => item.evidenceType === 'UPSTREAM_FAULT'),
    ).toBe(true)
    expect(
      (await api<Overview>(page, '/dashboard/overview')).alarms.affected,
    ).toBeGreaterThanOrEqual(4)

    await expect(page.locator('.dashboard-alarms')).toContainText('已确认上游故障')
    await screenshot(page, 'dashboard-active.png')
    await page.goto(`/topology?nodeId=${confirmedNode}`)
    await expect(page.locator('.network-detail')).toContainText('已确认上游故障')
    await expect(page.locator('.topology-graph .graph-label').first()).toBeVisible()
    await screenshot(page, 'topology-active.png')
    await page.getByRole('link', { name: '查看关联告警', exact: true }).click()
    const detail = page.getByRole('complementary', { name: '告警详情' })
    await expect(detail).toContainText('已确认上游故障')
    await expect(detail).toContainText('设备离线')
    await page.getByRole('button', { name: '确认告警', exact: true }).click()
    await expect(detail.locator('.alarm-labels').getByText('已确认', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '原始证据', exact: true }).click()
    await expect(detail).toContainText('上游故障证据')
    await screenshot(page, 'alarm-evidence.png')

    const failure = await scenario
    if (failure) throw failure
    await expect
      .poll(async () => (await api<AlarmDetail>(page, `/alarms/${confirmed!.id}`)).alarm.status, {
        timeout: 20000,
      })
      .toBe('RECOVERED')
    await expect
      .poll(async () => (await api<AlarmDetail>(page, `/alarms/${suspected!.id}`)).alarm.status)
      .toBe('RECOVERED')
    await expect
      .poll(async () => (await api<AlarmDetail>(page, `/alarms/${temperature!.id}`)).alarm.status)
      .toBe('RECOVERED')
    await expect(detail.locator('.alarm-labels')).toContainText('已恢复')
  } finally {
    await scenario
  }
})
