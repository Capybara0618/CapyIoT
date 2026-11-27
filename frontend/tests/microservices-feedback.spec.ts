import { expect, test } from '@playwright/test'

// Browser interactions use fixed offline API fixtures; no model or running backend is required.
test('a pending create can be queried and retried with its original payload and key', async ({
  page,
}) => {
  await page.addInitScript(() => {
    const user = {
      userId: 'supervisor',
      loginName: 'supervisor',
      displayName: '主管',
      authorizationVersion: 1,
      roleCodes: ['OPS_SUPERVISOR'],
    }
    sessionStorage.setItem('capyiot.token', 'offline-token')
    sessionStorage.setItem(
      'capyiot.session',
      JSON.stringify({
        accessToken: 'offline-token',
        tokenType: 'Bearer',
        expiresAt: new Date(Date.now() + 3600000).toISOString(),
        user,
      }),
    )
  })
  const submissions: { key: string | undefined; body: string | null }[] = []
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', data, traceId: 'offline' } })
    if (path === '/api/v1/auth/me')
      return ok({
        userId: 'supervisor',
        displayName: '主管',
        permissionCodes: ['workorder.create', 'workorder.read', 'device.read'],
        roleCodes: ['OPS_SUPERVISOR'],
      })
    if (path === '/api/v1/devices')
      return ok({
        items: [
          { deviceId: 'device-1', assetCode: 'IPC-1', name: '摄像机', assetStatus: 'IN_SERVICE' },
        ],
        total: 1,
        page: 1,
        size: 20,
      })
    if (path === '/api/v1/workorder-operations/operation-1')
      return ok({
        operationId: 'operation-1',
        state: 'PREPARED',
        workOrderId: null,
        checkedAt: '2026-11-27T00:00:00Z',
      })
    if (path === '/api/v1/workorders' && req.method() === 'POST') {
      submissions.push({ key: req.headers()['idempotency-key'], body: req.postData() })
      if (submissions.length === 3)
        return route.fulfill({
          status: 403,
          json: { code: 'FORBIDDEN', message: '当前授权无法核对' },
        })
      if (submissions.length === 4)
        return route.fulfill({
          status: 409,
          json: {
            code: 'WORKORDER_OPERATION_ABORTED',
            message: '操作已终止',
            details: { operationId: 'operation-1' },
          },
        })
      return route.fulfill({
        status: 503,
        json: {
          code: 'WORKORDER_OPERATION_PENDING',
          message: '操作结果待核查',
          details: { operationId: 'operation-1' },
          traceId: 'offline',
        },
      })
    }
    if (path === '/api/v1/workorders') return ok({ items: [], total: 0, page: 1, size: 20 })
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'fixture missing' } })
  })
  await page.goto('/workorders')
  await page.getByText('新建独立维修工单', { exact: true }).click()
  await page.getByLabel('标题', { exact: true }).fill('核查摄像机')
  await page.getByLabel('问题描述', { exact: true }).fill('视频数据中断，需人工检查')
  await page.getByLabel('预期结果', { exact: true }).fill('恢复视频数据')
  await page.getByLabel('维修设备', { exact: true }).selectOption('device-1')
  await page.getByLabel('其中的主设备', { exact: true }).selectOption('device-1')
  await page.getByRole('button', { name: '确认创建正式工单' }).click()
  await expect(page.getByLabel('待核对的工单操作')).toContainText('operation-1')
  await expect(page.getByRole('button', { name: '确认创建正式工单' })).toBeDisabled()
  await page.getByRole('button', { name: '查询操作状态' }).click()
  await expect(page.getByRole('status')).toContainText('已受理，待核对')
  await page.getByRole('button', { name: '核对并重试原操作' }).click()
  await expect.poll(() => submissions.length).toBe(2)
  expect(submissions[0].key).toBeTruthy()
  expect(submissions[1]).toEqual(submissions[0])
  await page.getByRole('button', { name: '核对并重试原操作' }).click()
  await expect(page.locator('.video-error')).toContainText('当前授权无法核对')
  await expect(page.getByLabel('待核对的工单操作')).toContainText('operation-1')
  await page.getByRole('button', { name: '核对并重试原操作' }).click()
  await expect(page.getByLabel('待核对的工单操作')).toHaveCount(0)
  expect(submissions[2]).toEqual(submissions[0])
  expect(submissions[3]).toEqual(submissions[0])
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})
