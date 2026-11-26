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
const stamp = () => new Date().toISOString()
const conversation = (id = 'conversation-one') => ({
  conversationId: id,
  title: '入口摄像机排查',
  status: 'ACTIVE',
  createdAt: stamp(),
  lastMessageAt: stamp(),
})
function finished(id = 'run-one', conversationId = 'conversation-one') {
  const evidenceReferences = [
    {
      evidenceId: 'evidence-one',
      summary: '已核查设备及关联故障',
      retrievedAt: stamp(),
      freshnessStatus: 'FRESH',
      toolName: 'get_topology_context',
      sourceObjects: [
        { sourceType: 'DEVICE', sourceId: 'ipc-one' },
        { sourceType: 'INCIDENT', sourceId: 'incident-one' },
        { sourceType: 'WORK_ORDER', sourceId: '101' },
      ],
    },
  ]
  return {
    runId: id,
    requestKey: 'request-one',
    conversationId,
    status: 'SUCCEEDED',
    startedAt: stamp(),
    finishedAt: stamp(),
    answerMessageId: 'answer-one',
    answer: {
      facts: [{ statement: '入口摄像机当前离线', evidenceIds: ['evidence-one'] }],
      inferences: [{ statement: '网络关系是待人工核查的线索', evidenceIds: ['evidence-one'] }],
      recommendations: ['先核查网络与供电'],
      unknowns: ['现场根因仍需确认'],
    },
    toolCallCount: 1,
    draftId: null,
    executionStatus: 'NOT_EXECUTED',
    errorCode: null,
    version: 1,
    contentStatus: 'VISIBLE',
    evidenceReferences,
  }
}
type Run = ReturnType<typeof finished>
function history(run: Run | null, id = 'conversation-one') {
  return {
    ...conversation(id),
    messages: run
      ? [
          {
            messageId: 'user-' + run.runId,
            messageSeq: 1,
            sequence: 1,
            role: 'USER',
            content: '核查入口摄像机',
            contentStatus: 'AVAILABLE',
            createdAt: stamp(),
            runId: run.runId,
            run,
          },
          {
            messageId: 'answer-' + run.runId,
            messageSeq: 2,
            sequence: 2,
            role: 'ASSISTANT',
            content: run.answer ? JSON.stringify(run.answer) : null,
            contentStatus: run.contentStatus,
            createdAt: stamp(),
            runId: run.runId,
            run,
          },
        ]
      : [],
    firstSequence: run ? 1 : null,
    lastSequence: run ? 2 : null,
    hasMoreBefore: false,
    hasMoreAfter: false,
  }
}
async function mockChat(page: Page, initial: Run | null = null) {
  const state = { run: initial, posts: 0, keys: [] as string[], redacted: false, partial: false }
  await page.route('**/api/v1/agent/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname,
      body = req.postDataJSON()
    let data: unknown,
      status = 200
    if (path.endsWith('/messages')) {
      state.posts++
      state.keys.push(req.headers()['idempotency-key']!)
      state.run = finished('run-one', path.split('/')[5])
      state.run.requestKey = req.headers()['idempotency-key']!
      if (state.partial) state.run.status = 'RUNNING'
      const frames = [
        ['run.started', { conversationId: state.run.conversationId }],
        [
          'tool.started',
          { toolCallId: 'call-one', toolName: 'get_device_detail', thinking: 'hidden-chain' },
        ],
        ...(state.partial
          ? []
          : [
              [
                'tool.completed',
                { toolCallId: 'call-one', toolName: 'get_device_detail', status: 'SUCCEEDED' },
              ],
              ['answer.delta', { contentDelta: JSON.stringify(state.run.answer) }],
              ['run.completed', { status: 'SUCCEEDED' }],
            ]),
      ]
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: frames
          .map(
            ([name, values], i) =>
              `id: ${i + 1}\nevent: ${name}\ndata: ${JSON.stringify({ runId: 'run-one', seq: i + 1, ...(values as object) })}\n\n`,
          )
          .join(''),
      })
      return
    }
    if (path.endsWith('/cancel') && state.run) {
      state.run.status = 'CANCELLED'
      state.run.answer = null as unknown as Run['answer']
      data = state.run
    } else if (path.includes('/runs/')) data = state.run
    else if (path === '/api/v1/agent/conversations' && req.method() === 'POST') {
      data = { ...conversation(), title: body.title }
      status = 201
    } else if (path === '/api/v1/agent/conversations')
      data = { items: [conversation()], total: 1, page: 1, size: 20 }
    else {
      const run = state.run ? structuredClone(state.run) : null
      if (state.redacted && run) {
        run.answer = null as unknown as Run['answer']
        run.evidenceReferences = []
        run.contentStatus = 'REDACTED_BY_CURRENT_AUTHORIZATION'
      }
      data = history(run)
    }
    await route.fulfill({ status, json: { code: 'OK', message: '成功', data, traceId: 'fixture' } })
  })
  return state
}
test('chat consumes POST SSE, renders referenced facts and restores history without resending', async ({
  page,
}) => {
  await login(page)
  const state = await mockChat(page)
  await page.goto('/assistant')
  await page.getByLabel('运维问题', { exact: true }).fill('核查入口摄像机')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByText('入口摄像机当前离线', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '查看关联拓扑' })).toHaveAttribute(
    'href',
    '/topology?deviceId=ipc-one',
  )
  await expect(page.getByRole('link', { name: '设备 ipc-one', exact: true })).toHaveAttribute(
    'href',
    '/devices?id=ipc-one',
  )
  await expect(page.getByRole('link', { name: '故障 incident-one', exact: true })).toHaveAttribute(
    'href',
    '/alarms?id=incident-one',
  )
  await expect(page.getByRole('link', { name: '工单 101', exact: true })).toHaveAttribute(
    'href',
    '/workorders?id=101',
  )
  expect(await page.locator('body').innerText()).not.toContain('hidden-chain')
  await page.reload()
  await expect(page.getByText('入口摄像机当前离线', { exact: true })).toBeVisible()
  expect(state.posts).toBe(1)
  expect(state.keys[0]).toBeTruthy()
})
test('historical answers and citations disappear when current access changes', async ({ page }) => {
  await login(page)
  const state = await mockChat(page, finished())
  await page.goto('/assistant/conversation-one')
  await expect(page.getByText('入口摄像机当前离线', { exact: true })).toBeVisible()
  state.redacted = true
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(page.getByText('入口摄像机当前离线', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '设备 ipc-one', exact: true })).toHaveCount(0)
  await expect(page.getByText(/历史回答与引用已隐藏/)).toBeVisible()
})
test('an interrupted stream is reconciled and can be explicitly cancelled', async ({ page }) => {
  await login(page)
  const state = await mockChat(page)
  state.partial = true
  await page.goto('/assistant/conversation-one')
  await page.getByLabel('运维问题', { exact: true }).fill('查询连接状态')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByRole('button', { name: '停止查询', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '停止查询', exact: true }).click()
  await expect(page.getByText('已停止', { exact: true })).toBeVisible()
  expect(state.posts).toBe(1)
})
test('chat stays readable on mobile and untrusted text is displayed as text', async ({ page }) => {
  await login(page)
  const run = finished()
  run.answer.facts[0]!.statement = '<img src=x onerror=alert(1)> 设备备注'
  await mockChat(page, run)
  await page.setViewportSize({ width: 390, height: 950 })
  await page.goto('/assistant/conversation-one')
  await expect(
    page.getByText('<img src=x onerror=alert(1)> 设备备注', { exact: true }),
  ).toBeVisible()
  await expect(page.locator('.agent-answer img')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.screenshot({
    path: resolve('../var/verification/stage5-chat-390.png'),
    fullPage: true,
  })
})

test('older chat messages preserve the reading position instead of jumping to the newest turn', async ({
  page,
}) => {
  // A frontend-only regression check: credentials and all business API responses are local fixtures.
  await page.addInitScript(() => {
    const saved = {
      accessToken: 'offline-browser-fixture',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
      user: {
        userId: 'fixture-user',
        loginName: 'fixture',
        displayName: '验收账号',
        authorizationVersion: 1,
        roleCodes: ['OPS_SUPERVISOR'],
      },
    }
    sessionStorage.setItem('capyiot.token', saved.accessToken)
    sessionStorage.setItem('capyiot.session', JSON.stringify(saved))
  })
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: {
        code: 'OK',
        message: '成功',
        data: {
          userId: 'fixture-user',
          loginName: 'fixture',
          displayName: '验收账号',
          status: 'ACTIVE',
          authorizationVersion: 1,
          roleCodes: ['OPS_SUPERVISOR'],
          permissionCodes: ['agent.use'],
          scopeSummaries: [],
        },
        traceId: 'fixture',
      },
    }),
  )
  await page.route('**/api/v1/health', (route) =>
    route.fulfill({
      json: {
        code: 'OK',
        message: '成功',
        data: { service: 'frontend-fixture', status: 'UP', checkedAt: stamp() },
        traceId: 'fixture',
      },
    }),
  )
  await mockChat(page)
  await page.route('**/api/v1/agent/conversations/conversation-one?*', async (route) => {
    const older = new URL(route.request().url()).searchParams.has('before')
    const messages = Array.from({ length: older ? 1 : 5 }, (_, index) => {
      const number = older ? 0 : index + 1,
        run = finished('run-' + number)
      run.answer.facts[0]!.statement = '第 ' + number + ' 轮状态核查'
      const pair = history(run).messages
      pair[0]!.content = '历史问题 ' + number
      pair.forEach((message, offset) => {
        message.messageSeq = number * 2 + offset + 1
        message.sequence = message.messageSeq
      })
      return pair
    }).flat()
    await route.fulfill({
      json: {
        code: 'OK',
        message: '成功',
        data: {
          ...conversation(),
          messages,
          firstSequence: older ? 1 : 3,
          lastSequence: older ? 2 : 12,
          hasMoreBefore: !older,
          hasMoreAfter: older,
        },
        traceId: 'fixture',
      },
    })
  })
  await page.goto('/assistant/conversation-one')
  const transcript = page.getByRole('log', { name: '对话记录' })
  await expect(page.getByText('历史问题 5', { exact: true })).toBeAttached()
  await transcript.evaluate((element) => {
    element.scrollTop = 0
  })
  await page.getByRole('button', { name: '查看更早消息', exact: true }).click()
  await expect(page.getByText('历史问题 0', { exact: true })).toBeAttached()
  await expect
    .poll(() =>
      transcript.evaluate(
        (element) => element.scrollTop < element.scrollHeight - element.clientHeight - 50,
      ),
    )
    .toBeTruthy()
})
