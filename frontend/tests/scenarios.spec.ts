import { expect, test, type Page } from '@playwright/test'
import { readFileSync, renameSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { Alarm, AlarmDetail } from '../src/alarms'
import type { Device, EventPage, Page as ApiPage } from '../src/domain'
import type { Catalogue, ScenarioDetail, ScenarioRun } from '../src/scenarios'
import type { WorkOrder } from '../src/workorders'

test.use({ actionTimeout: 12000, navigationTimeout: 15000 })

const credentials = new Map(
  readFileSync(resolve('../.env'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
)

async function login(page: Page, user: 'admin' | 'operator') {
  await page.goto('/scenarios')
  await page.getByLabel('账号', { exact: true }).fill(user)
  await page
    .getByLabel('密码', { exact: true })
    .fill(credentials.get(`DEMO_${user.toUpperCase()}_PASSWORD`)!)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page.getByRole('heading', { name: '场景模拟中心' })).toBeVisible()
}

async function headers(page: Page) {
  return {
    Authorization: `Bearer ${await page.evaluate(() => sessionStorage.getItem('capyiot.token'))}`,
  }
}

async function api<T>(page: Page, path: string) {
  const result = await page.request.get(`/api/v1${path}`, { headers: await headers(page) })
  expect(result.ok(), `${path} returned ${result.status()}`).toBe(true)
  return ((await result.json()) as { data: T }).data
}

async function start(page: Page, template: string, code: string, duration: number) {
  await page.getByRole('button', { name: new RegExp(`^${template}`) }).click()
  const catalogue = await api<Catalogue>(page, '/scenarios/templates')
  const device = catalogue.scope.devices.find((item) => item.code === code)!
  expect(device).toBeDefined()
  await page.getByLabel('所属楼宇', { exact: true }).selectOption(device.buildingId)
  await page.getByLabel('目标设备', { exact: true }).selectOption(device.id)
  await page.getByLabel('持续时间（秒）').fill(String(duration))
  const accepted = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/scenario-runs') && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '开始模拟' }).click()
  const response = await accepted
  expect(response.status()).toBe(202)
  const { data } = (await response.json()) as { data: ScenarioRun }
  await expect(page).toHaveURL(new RegExp(`runId=${data.id}$`))
  return data
}

async function saveImage(page: Page, name: string) {
  if (process.env.CAPYIOT_SCREENSHOT_DIR)
    await page.screenshot({
      path: resolve(process.env.CAPYIOT_SCREENSHOT_DIR, name),
      fullPage: true,
      animations: 'disabled',
    })
}

async function workAction(page: Page, label: string, remark?: string) {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog')
  if (remark) await dialog.getByLabel('处置说明').fill(remark)
  await dialog.getByRole('button', { name: '确认操作', exact: true }).click()
  await expect(dialog).toBeHidden()
}

async function detachedDevice(page: Page) {
  const code = `FLAP-${Date.now().toString(36).toUpperCase()}`
  const response = await page.request.post('/api/v1/devices', {
    headers: await headers(page),
    data: {
      deviceCode: code,
      type: 'CAMERA',
      name: '抖动验收设备',
      buildingId: 'BLD-2',
      floorId: 'FL-2-1',
    },
  })
  expect(response.status()).toBe(200)
  const { data } = (await response.json()) as { data: { device: Device; deviceSecret: string } }
  const file = resolve('../var/demo-devices.json')
  const original = readFileSync(file)
  const temporary = `${file}.e2e`
  function replaceConfig(content: string | Buffer) {
    writeFileSync(temporary, content)
    renameSync(temporary, file)
  }
  // An unlinked device isolates the flap rule from the preceding network scenario's correlation window.
  replaceConfig(
    JSON.stringify([
      ...JSON.parse(original.toString('utf8')),
      {
        deviceId: data.device.id,
        deviceCode: code,
        campusId: data.device.campusId,
        type: data.device.type,
        secret: data.deviceSecret,
      },
    ]),
  )
  return { code, restoreConfig: () => replaceConfig(original) }
}

test('browser starts confirmed network incident and closes its work order after recovery', async ({
  page,
  context,
}) => {
  test.setTimeout(140000)
  await login(page, 'admin')
  const run = await start(page, '交换机故障', 'DEMO-1-SW', 100)
  const path = `/scenario-runs/${run.id}`
  const operatorPage = await context.newPage()
  try {
    const response = await page.request.post('/api/v1/scenario-runs', {
      headers: await headers(page),
      data: { template: run.template, deviceId: run.targetIds[0], durationSeconds: 100 },
    })
    expect(response.status()).toBe(409)

    await expect(page.locator('.scenario-log')).toContainText('已观察到交换机故障及下游心跳超时', {
      timeout: 65000,
    })
    const detail = await api<ScenarioDetail>(page, path)
    const fault = detail.evidence.find((event) => event.eventType === 'DEVICE_FAULT')!
    expect(fault.processingStatus).toBe('PROCESSED')
    expect(detail.targets.filter((item) => item.connectivityStatus === 'OFFLINE')).toHaveLength(3)
    let alarm: Alarm | undefined
    await expect
      .poll(async () => {
        const alarms = await api<Alarm[]>(page, `/alarms/by-event/${fault.eventId}`)
        alarm = alarms.find(
          (item) => item.relationKind === 'CONFIRMED_UPSTREAM' && item.affectedCount === 3,
        )
        return alarm?.id
      })
      .toBeTruthy()
    const alarmId = alarm!.id
    await page.bringToFront()
    await saveImage(page, 'scenario-network-active.png')

    await operatorPage.bringToFront()
    await login(operatorPage, 'operator')
    await expect(operatorPage.getByRole('button', { name: '开始模拟' })).toBeDisabled()
    const rejected = await operatorPage.request.post(`/api/v1${path}/stop`, {
      headers: await headers(operatorPage),
    })
    expect(rejected.status()).toBe(403)
    await operatorPage.goto(`/events?id=${fault.eventId}&scenarioRunId=${run.id}`)
    const event = operatorPage.getByRole('dialog', { name: '事件详情' })
    await expect(event).toContainText(fault.traceId)
    await event.getByRole('link', { name: new RegExp(alarm!.alarmNo) }).click()
    const alarmDetail = operatorPage.getByRole('complementary', { name: '告警详情' })
    await expect(alarmDetail).toContainText('已确认上游故障')
    await alarmDetail.getByRole('link', { name: /查看工单 WO-/ }).click()
    const work = operatorPage.getByRole('complementary', { name: '工单详情' })
    await workAction(operatorPage, '领取工单')
    await workAction(operatorPage, '开始处理')
    await workAction(operatorPage, '完成处理', '交换机与下游链路已检查，等待恢复验证')
    await expect(work).toContainText('待验证')

    await page.bringToFront()
    await page.getByRole('button', { name: '立即恢复' }).click()
    await expect(page.locator('.scenario-run-status')).toContainText('已恢复', { timeout: 20000 })
    await expect
      .poll(async () => (await api<AlarmDetail>(operatorPage, `/alarms/${alarmId}`)).alarm.status)
      .toBe('RECOVERED')
    await operatorPage.bringToFront()
    await expect(work).toContainText('来源告警已恢复')
    await workAction(operatorPage, '通过恢复验证', '交换机恢复事件与下游心跳均已确认')
    await workAction(operatorPage, '关闭工单')
    await expect(work.locator('.alarm-labels')).toContainText('已关闭')
    const order = await api<WorkOrder>(operatorPage, `/workorders/by-alarm/${alarmId}`)
    expect(order.status).toBe('CLOSED')
    await saveImage(operatorPage, 'scenario-order-closed.png')

    await page.reload()
    await expect(page.locator('.scenario-log')).toContainText('请求立即恢复设备')
    await expect(page.locator('.scenario-log')).toContainText('恢复事件已处理')
    await operatorPage.goto(`/devices?id=${run.targetIds[1]}`)
    const drawer = operatorPage.getByRole('dialog', { name: '设备详情' })
    await drawer.getByRole('tab', { name: '告警与工单' }).click()
    await expect(drawer.getByRole('link', { name: order.orderNo })).toBeVisible()
    await saveImage(operatorPage, 'device-related-workorder.png')
  } finally {
    await operatorPage.close()
    await page.request.post(`/api/v1${path}/recover`, { headers: await headers(page) })
  }
})

test('temperature keeps the device online and stopping restores it after an SSE reconnect', async ({
  page,
  context,
}) => {
  test.setTimeout(70000)
  await login(page, 'admin')
  const run = await start(page, '温度越限', 'DEMO-3-SEN', 45)
  const path = `/scenario-runs/${run.id}`
  try {
    await context.setOffline(true)
    await expect(page.getByText(/正在重连/)).toBeVisible()
    await context.setOffline(false)
    await expect(page.locator('.scenario-log')).toContainText('传感器仍在线')
    const observed = await api<ScenarioDetail>(page, path)
    expect(observed.targets[0]).toMatchObject({
      connectivityStatus: 'ONLINE',
      healthStatus: 'ABNORMAL',
    })
    await page.getByRole('button', { name: '停止', exact: true }).click()
    await expect(page.locator('.scenario-run-status')).toContainText('已停止')
    await page.reload()
    await expect(page.locator('.scenario-run-status')).toContainText('已停止')
    const recovered = await api<ScenarioDetail>(page, path)
    expect(recovered.targets[0]).toMatchObject({
      connectivityStatus: 'ONLINE',
      healthStatus: 'NORMAL',
    })
    const events = await api<EventPage>(page, `/events?scenarioRunId=${run.id}`)
    expect(events.items.length).toBeGreaterThanOrEqual(4)
    expect(events.items.every((item) => item.payload.scenarioRunId === run.id)).toBe(true)
    expect(
      events.items.some(
        (item) => item.eventType === 'DEVICE_RECOVERED' && item.processingStatus === 'PROCESSED',
      ),
    ).toBe(true)
    await saveImage(page, 'scenario-temperature-stopped.png')
  } finally {
    await context.setOffline(false)
    await page.request.post(`/api/v1${path}/recover`, { headers: await headers(page) })
  }
})

test('two real heartbeat timeouts reuse the offline alarm within the flap window', async ({
  page,
}) => {
  test.setTimeout(130000)
  await login(page, 'admin')
  const fixture = await detachedDevice(page)
  let path: string | undefined
  try {
    await page.reload()
    const run = await start(page, '设备状态抖动', fixture.code, 90)
    path = `/scenario-runs/${run.id}`
    await expect(page.locator('.scenario-log')).toContainText('已观察到第一次离线', {
      timeout: 55000,
    })
    let first: Alarm | undefined
    await expect
      .poll(
        async () => {
          const alarms = await api<ApiPage<Alarm>>(
            page,
            '/alarms?alarmType=DEVICE_OFFLINE&size=100',
          )
          first = alarms.items.find((item) => item.deviceId === run.targetIds[0])
          return first?.id
        },
        { timeout: 12000 },
      )
      .toBeTruthy()
    await expect(page.locator('.scenario-log')).toContainText('已观察到第二次离线', {
      timeout: 55000,
    })
    await expect
      .poll(async () => {
        const detail = await api<AlarmDetail>(page, `/alarms/${first!.id}`)
        return detail.evidence.items.some((item) => item.evidenceType === 'FLAP_SUPPRESSED')
      })
      .toBe(true)
    await expect(page.locator('.scenario-run-status')).toContainText('已恢复', { timeout: 30000 })
    const detail = await api<ScenarioDetail>(page, path)
    expect(detail.targets[0].connectivityStatus).toBe('ONLINE')
    expect(detail.run.endMode).toBeNull()
    await saveImage(page, 'scenario-flapping-recovered.png')
  } finally {
    try {
      if (path) {
        const runPath = path
        await page.request.post(`/api/v1${runPath}/recover`, { headers: await headers(page) })
        await expect
          .poll(async () => (await api<ScenarioDetail>(page, runPath)).run.endedAt)
          .toBeTruthy()
      }
    } finally {
      fixture.restoreConfig()
    }
  }
})
