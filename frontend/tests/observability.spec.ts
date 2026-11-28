import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const env = Object.fromEntries(
  readFileSync(resolve(process.env.CAPYIOT_CREDENTIAL_FILE ?? '../.env'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
)

test('observability reads bounded live queries without inferring deployment type from disabled observation', async ({
  page,
}) => {
  await page.goto('/observability')
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill(env.DEMO_ADMIN_PASSWORD)
  const initial = page.waitForResponse((response) =>
    response.url().includes('/observability/metrics?'),
  )
  await page.getByRole('button', { name: '进入工作台' }).click()
  const response = await initial
  expect(response.status()).toBe(200)
  const { data } = (await response.json()) as {
    data: {
      prometheusAvailable: boolean
      series: { id: string; points: { at: number; value: number | null }[] }[]
    }
  }
  if (data.prometheusAvailable) {
    await expect(page.getByLabel('Prometheus 指标')).toBeVisible()
    expect(data.series.map((series) => series.id)).toEqual([
      'lag',
      'tps',
      'httpP95',
      'heapMiB',
      'gcPause',
      'poolActive',
      'mysqlP95',
      'cacheHit',
    ])
    expect(
      data.series.every((series) =>
        series.points.every(
          (point) =>
            Number.isFinite(point.at) && (point.value === null || Number.isFinite(point.value)),
        ),
      ),
    ).toBe(true)
  } else {
    await expect(page.getByText('Prometheus 暂不可用', { exact: true })).toBeVisible()
    await expect(page.getByLabel('Prometheus 指标')).toHaveCount(0)
  }
  await expect(page.getByText('未启用集群状态读取，副本数量与自动扩缩容状态不可用。')).toBeVisible()
  const changed = page.waitForResponse((result) =>
    result.url().includes('/observability/metrics?minutes=60&service=alarm-service'),
  )
  await page.getByLabel('监控服务').selectOption('alarm-service')
  await page.getByLabel('监控时间范围').selectOption('60')
  expect((await changed).status()).toBe(200)

  const token = await page.evaluate(() => sessionStorage.getItem('capyiot.token'))
  const rejected = await page.request.get(
    '/api/v1/observability/metrics?minutes=999&service=arbitrary',
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  )
  expect(rejected.status()).toBe(400)
})
