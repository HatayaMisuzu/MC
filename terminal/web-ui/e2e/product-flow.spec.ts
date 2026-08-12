import { expect, test, type Page } from '@playwright/test'
import { appendFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { Socket } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import type { AddressInfo } from 'node:net'

type Locale = 'zh-CN' | 'en-US'
interface ProtocolCommand {
  commandId: string
}
interface TaskGraphListEntry {
  executionId: string
  graphId: string
}
interface CompanionListEntry {
  id: string
  online: boolean
}
interface CompanionTaskEntry {
  state: string
}
interface E2EInstance {
  id: string
  gameDir: string
}
let activeHermesServer: Server | undefined

test.afterEach(async () => {
  if (!activeHermesServer) return
  const server = activeHermesServer
  activeHermesServer = undefined
  server.closeAllConnections()
  await new Promise<void>((resolveClosed) => server.close(() => resolveClosed()))
})

const copy = {
  'zh-CN': {
    confirm: '确认并执行',
    close: '关闭',
    nav: {
      overview: '总览',
      instances: '启动器与实例',
      install: '安装管理',
      game: '游戏启动',
      companions: 'AI 伙伴',
      smoke: '自动检查',
      runtime: '运行服务',
      provider: '模型服务',
      search: '搜索与隐私',
      brain: '外部 AI 控制器',
      skills: '技能',
      compatibility: '兼容层',
      doctor: '诊断',
      logs: '日志与支持',
      settings: '设置与安全',
    },
    actions: {
      install: '审阅安装计划',
      update: '检查并更新',
      repair: '验证并修复',
      rollback: '审阅回滚计划',
      runtimeStart: '启动',
      runtimeStop: '停止',
      runtimeRestart: '重启',
      rotateToken: '安全轮换令牌',
      attach: '验证当前会话',
      support: '生成支持包',
      uninstallKeep: '卸载并保留数据',
      uninstallDelete: '卸载并删除 MCAC 数据',
    },
  },
  'en-US': {
    confirm: 'Confirm and execute',
    close: 'Close',
    nav: {
      overview: 'Overview',
      instances: 'Launchers & instances',
      install: 'Installation',
      game: 'Game launch',
      companions: 'AI companions',
      smoke: 'Automated checks',
      runtime: 'Runtime service',
      provider: 'Model service',
      search: 'Search & privacy',
      brain: 'External AI controller',
      skills: 'Skills',
      compatibility: 'Compatibility',
      doctor: 'Diagnostics',
      logs: 'Logs & support',
      settings: 'Settings & security',
    },
    actions: {
      install: 'Review installation plan',
      update: 'Check and update',
      repair: 'Verify and repair',
      rollback: 'Review rollback plan',
      runtimeStart: 'Start',
      runtimeStop: 'Stop',
      runtimeRestart: 'Restart',
      rotateToken: 'Rotate token safely',
      attach: 'Verify current session',
      support: 'Create support bundle',
      uninstallKeep: 'Uninstall and keep data',
      uninstallDelete: 'Uninstall and delete MCAC data',
    },
  },
} as const

async function setLocale(page: Page, locale: Locale) {
  await page.locator('.language-switcher select').selectOption(locale)
  await expect(page.locator('html')).toHaveAttribute('lang', locale)
  await expect.poll(() => page.evaluate(() => localStorage.getItem('mcac.locale'))).toBe(locale)
}

async function navigate(page: Page, locale: Locale, name: keyof typeof copy['en-US']['nav']) {
  const label = copy[locale].nav[name]
  const button = page.locator('nav').getByRole('button', { name: label, exact: true })
  await button.click()
  await expect(button).toHaveClass(/active/)
  await expect(page.locator('main')).toBeVisible()
}

async function confirmAndWait(page: Page, locale: Locale) {
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  const confirm = dialog.getByRole('button', { name: copy[locale].confirm, exact: true })
  await expect(confirm).toBeVisible({ timeout: 15_000 })
  await confirm.click()
  const state = dialog.locator('.operation-meta strong')
  await expect(state).toHaveText(/SUCCEEDED|FAILED/, { timeout: 60_000 })
  const stateText = await state.textContent()
  const error = stateText === 'FAILED' ? await dialog.locator('.inline-error').last().textContent() : ''
  expect(stateText, error ?? '').toBe('SUCCEEDED')
  await dialog.locator('footer').getByRole('button', { name: copy[locale].close, exact: true }).click()
}

async function clickPlan(page: Page, locale: Locale, label: string) {
  await page.getByRole('button', { name: label, exact: true }).click()
  await confirmAndWait(page, locale)
}

async function clickPlanExpectFailure(
  page: Page,
  locale: Locale,
  label: string,
  expectedCode: string,
) {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: copy[locale].confirm, exact: true }).click()
  await expect(dialog.locator('.operation-meta strong')).toHaveText('FAILED', { timeout: 60_000 })
  await expect(dialog.locator('.inline-error').last()).toContainText(expectedCode)
  await dialog.locator('footer').getByRole('button', { name: copy[locale].close, exact: true }).click()
}

async function apiJson(page: Page, path: string) {
  return page.evaluate(async (url) => {
    const controlUrl = url.startsWith('/api/') && !url.includes('view=')
      ? `${url}${url.includes('?') ? '&' : '?'}view=control`
      : url
    const response = await fetch(controlUrl, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-MCAC-CSRF': sessionStorage.getItem('mcac.csrf') ?? '' },
    })
    if (!response.ok) throw new Error(`API ${controlUrl} failed with ${response.status}: ${await response.text()}`)
    return response.json()
  }, path)
}

async function portOpen(port: number) {
  return new Promise<boolean>((resolve) => {
    const socket = new Socket()
    const finish = (open: boolean) => { socket.destroy(); resolve(open) }
    socket.setTimeout(500)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(port, '127.0.0.1')
  })
}

function startHermesFixture(): Promise<{
  server: Server
  endpoint: string
  calls: { opened: number; turned: number; cancelled: number }
}> {
  const calls = { opened: 0, turned: 0, cancelled: 0 }
  const server = createServer((request, response) => {
    const path = request.url ?? ''
    response.setHeader('Content-Type', 'application/json')
    if (request.method === 'POST' && path === '/sessions') {
      calls.opened += 1
      response.end('{"sessionId":"playwright-health-session"}')
    } else if (request.method === 'POST' && path.endsWith('/turns')) {
      calls.turned += 1
      response.end(JSON.stringify({
        kind: 'TOOL_CALLS',
        toolCalls: [{ callId: 'playwright-probe', name: 'mcac_health_probe', arguments: {} }],
      }))
    } else if (request.method === 'POST' && path.endsWith('/cancel')) {
      calls.cancelled += 1
      response.end('{}')
    } else {
      response.statusCode = 404
      response.end('{}')
    }
  })
  return new Promise((resolveStarted, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address() as AddressInfo
      server.unref()
      resolveStarted({
        server,
        endpoint: `http://127.0.0.1:${address.port}`,
        calls,
      })
    })
  })
}

async function verifyDiscoveryAndBrain(
  page: Page,
  endpoint: string,
  calls: { opened: number; turned: number; cancelled: number },
) {
  await navigate(page, 'en-US', 'instances')
  const rescanResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === '/api/discovery/rescan'
      && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Rescan', exact: true }).click()
  expect((await rescanResponse).status()).toBe(200)
  await expect(page.locator('.inline-error')).toHaveCount(0)
  expect((await apiJson(page, '/api/instances')).length).toBeGreaterThan(0)

  await navigate(page, 'en-US', 'brain')
  await page.getByLabel('Endpoint', { exact: true }).fill(endpoint)
  await page.getByLabel('Token environment variable', { exact: true })
    .fill('MCAC_E2E_BRAIN_TOKEN')
  await clickPlan(page, 'en-US', 'Review configuration')
  const instances = await apiJson(page, '/api/instances')
  const brain = await apiJson(page,
    `/api/brain/config?instanceId=${encodeURIComponent(instances[0].id as string)}`)
  expect(brain.mode).toBe('hermes')
  expect(brain.endpoint).toBe(endpoint)

  const probesBeforeExplicitTest = calls.opened
  await page.getByRole('button', { name: 'Verify MCAC protocol', exact: true }).click()
  await expect(page.getByText('TOOL_CALL_VERIFIED', { exact: true })).toBeVisible()
  await expect.poll(() => calls.opened > probesBeforeExplicitTest
    && calls.opened === calls.turned && calls.opened === calls.cancelled).toBe(true)

  await clickPlan(page, 'en-US', 'Disable')
  const disabled = await apiJson(page,
    `/api/brain/config?instanceId=${encodeURIComponent(instances[0].id as string)}`)
  expect(disabled.mode).toBe('disabled')
}

async function connectProtocolCompanion(runtimePort: number, instanceId: string) {
  const profile = resolve(tmpdir(), 'mcac-playwright-fixture', 'local-app-data',
    'MinecraftAICompanion', 'profiles', instanceId)
  const token = readFileSync(resolve(profile, 'pairing.token'), 'ascii').trim()
  const socket = new WebSocket(`ws://127.0.0.1:${runtimePort}`)
  let sessionId = ''
  let sequence = 0
  let behaviorRevision = 0
  const current = { behaviorId: '', controlEpoch: 0 }
  const send = (type: string, payload: Record<string, unknown>) => {
    socket.send(JSON.stringify({ type, sessionId, sequence: sequence++, payload }))
  }
  const opened = new Promise<void>((resolveOpened, reject) => {
    socket.addEventListener('open', () => resolveOpened(), { once: true })
    socket.addEventListener('error', () => reject(new Error('Runtime WebSocket failed')), { once: true })
  })
  await opened
  const acknowledged = new Promise<void>((resolveAck, reject) => {
    const timer = setTimeout(() => reject(new Error('Runtime hello acknowledgement timed out')), 5_000)
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data))
      if (message.type === 'hello_ack') {
        clearTimeout(timer)
        sessionId = message.sessionId
        resolveAck()
      }
    })
  })
  socket.send(JSON.stringify({
    type: 'hello',
    protocol: 'mc-companion/1',
    token,
    modVersion: 'playwright-fixture',
    minecraftVersion: '1.21.1',
    loader: 'fabric',
    worldId: 'playwright-world',
    capabilities: {
      NavigateTo: true,
      FollowOwner: true,
      DeliverItem: true,
      EatAndRecover: true,
      CraftItem: true,
    },
  }))
  await acknowledged
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(String(event.data))
    if (String(message.type).toLowerCase() !== 'command') return
    const payload = message.payload ?? {}
    const command = String(payload.command ?? '').toUpperCase()
    if (command === 'START_BEHAVIOR') {
      current.behaviorId = payload.arguments.behaviorId
      current.controlEpoch = payload.controlEpoch
      behaviorRevision += 1
      send('command_accepted', {
        commandId: payload.commandId,
        duplicate: false,
        behaviorId: current.behaviorId,
        behaviorRevision,
        acceptedAt: new Date().toISOString(),
      })
      behaviorRevision += 1
      send('behavior_event', behaviorEvent(
        payload, current, behaviorRevision, 'STARTED', 'RUNNING', 0.05))
    } else if (command === 'PAUSE_BEHAVIOR') {
      behaviorRevision += 1
      send('behavior_event', behaviorEvent(
        payload, current, behaviorRevision, 'PAUSED', 'PAUSED', 0.25))
    } else if (command === 'RESUME_BEHAVIOR') {
      behaviorRevision += 1
      send('behavior_event', behaviorEvent(
        payload, current, behaviorRevision, 'RESUMED', 'RUNNING', 0.3))
    } else if (command === 'CANCEL_BEHAVIOR') {
      behaviorRevision += 1
      send('behavior_event', behaviorEvent(
        payload, current, behaviorRevision, 'CANCELLED', 'CANCELLED', 1))
    }
  })
  send('companion_status', {
    companionId: 'playwright-companion',
    ownerId: 'playwright-owner',
    displayName: 'Playwright Companion',
    worldId: 'playwright-world',
    dimension: 'minecraft:overworld',
    position: { x: 0, y: 64, z: 0 },
    bodyState: 'spawned',
    behaviorRevision: 0,
    controlEpoch: 0,
    runtimeConnected: true,
    capabilities: {},
    observedAt: new Date().toISOString(),
  })
  const heartbeat = setInterval(() => {
    if (socket.readyState === WebSocket.OPEN) send('heartbeat', {})
  }, 5_000)
  return {
    socket,
    token,
    profile,
    close: async () => {
      clearInterval(heartbeat)
      if (socket.readyState === WebSocket.CLOSED) return
      const closed = new Promise<void>((resolveClosed) =>
        socket.addEventListener('close', () => resolveClosed(), { once: true }))
      socket.close()
      await closed
    },
  }
}

function behaviorEvent(
  command: ProtocolCommand,
  current: { behaviorId: string; controlEpoch: number },
  revision: number,
  event: string,
  state: string,
  progress: number,
) {
  return {
    eventId: `playwright-event-${revision}`,
    behaviorId: current.behaviorId,
    commandId: command.commandId,
    companionId: 'playwright-companion',
    event,
    state,
    revision,
    tick: revision,
    progress,
    failureCode: null,
    message: `Playwright ${event.toLowerCase()}`,
    occurredAt: new Date().toISOString(),
    snapshot: { controlEpoch: current.controlEpoch },
  }
}

async function startWaitingTaskGraph(page: Page, healthPort: number, token: string) {
  const endpoint = `http://127.0.0.1:${healthPort}/mcp`
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    Accept: 'application/json',
    'X-MCAC-Controller-Id': 'playwright-controller',
    'X-MCAC-Brain-Session-Id': 'playwright-brain-session',
    'X-MCAC-Companion-Id': 'playwright-companion',
  }
  const initialized = await page.request.post(endpoint, {
    headers,
    timeout: 20_000,
    data: {
      jsonrpc: '2.0',
      id: 'playwright-init',
      method: 'initialize',
      params: {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'playwright', version: '1' },
      },
    },
  })
  expect(initialized.status()).toBe(200)
  const mcpSession = initialized.headers()['mcp-session-id']
  expect(mcpSession).toBeTruthy()
  const completion = page.request.post(endpoint, {
    headers: {
      ...headers,
      'MCP-Protocol-Version': '2025-06-18',
      'Mcp-Session-Id': mcpSession as string,
    },
    timeout: 70_000,
    data: {
      jsonrpc: '2.0',
      id: 'playwright-graph',
      method: 'tools/call',
      params: {
        name: 'task_graph.execute',
        arguments: {
          graph: {
            version: 'mcac-task-graph/1',
            id: 'playwright-waiting-graph',
            permissions: [],
            root: {
              id: 'delay',
              type: 'wait',
              durationMillis: 60_000,
            },
          },
        },
      },
    },
  })
  let executionId = ''
  await expect.poll(async () => {
    const listed = await page.request.get(
      `http://127.0.0.1:${healthPort}/task-graphs?companionId=playwright-companion`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    expect(listed.status()).toBe(200)
    const body = await listed.json() as { executions?: TaskGraphListEntry[] }
    executionId = body.executions?.find(
      (execution) => execution.graphId === 'playwright-waiting-graph',
    )?.executionId ?? ''
    return executionId
  }).not.toBe('')
  return { executionId, completion }
}

async function verifyCompanionAndTaskGraphControls(
  page: Page,
  instanceId: string,
  runtime: { port: number; healthPort: number },
) {
  const fixture = await connectProtocolCompanion(runtime.port, instanceId)
  try {
    await expect.poll(async () => {
      const value = await apiJson(page,
        `/api/companions?instanceId=${encodeURIComponent(instanceId)}`) as {
        companions?: CompanionListEntry[]
      }
      return value.companions?.some((companion) =>
        companion.id === 'playwright-companion' && companion.online)
    }).toBe(true)
    await navigate(page, 'en-US', 'companions')
    await expect(page.getByRole('option', { name: /playwright-companion/ }))
      .toHaveJSProperty('selected', true)

    await clickPlan(page, 'en-US', 'status')
    await clickPlan(page, 'en-US', 'follow')
    await expect.poll(async () => {
      const value = await apiJson(page,
        `/api/companions?instanceId=${encodeURIComponent(instanceId)}`) as {
        tasks?: CompanionTaskEntry[]
      }
      return value.tasks?.[0]?.state
    }).toBe('RUNNING')
    await clickPlan(page, 'en-US', 'pause')
    await expect.poll(async () => {
      const value = await apiJson(page,
        `/api/companions?instanceId=${encodeURIComponent(instanceId)}`) as {
        tasks?: CompanionTaskEntry[]
      }
      return value.tasks?.[0]?.state
    }).toBe('PAUSED')
    await clickPlan(page, 'en-US', 'resume')
    await clickPlan(page, 'en-US', 'stop')
    await expect.poll(async () => {
      const value = await apiJson(page,
        `/api/companions?instanceId=${encodeURIComponent(instanceId)}`) as {
        tasks?: CompanionTaskEntry[]
      }
      return value.tasks?.every((task) =>
        ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.state))
    }).toBe(true)

    await clickPlan(page, 'en-US', 'come')
    await clickPlan(page, 'en-US', 'stop')
    await page.getByLabel('X', { exact: true }).fill('12')
    await page.getByLabel('Y', { exact: true }).fill('70')
    await page.getByLabel('Z', { exact: true }).fill('-8')
    await clickPlan(page, 'en-US', 'goto')
    await clickPlan(page, 'en-US', 'stop')

    const graph = await startWaitingTaskGraph(page, runtime.healthPort, fixture.token)
    await page.getByRole('main').getByRole(
      'button', { name: 'Refresh status', exact: true },
    ).click()
    const graphRow = page.getByRole('row').filter({ hasText: 'playwright-waiting-graph' })
    await expect(graphRow).toContainText(graph.executionId.slice(0, 10))
    await graphRow.getByRole('button', { name: 'Pause', exact: true }).click()
    await expect(graphRow).toContainText('PAUSED')
    await graphRow.getByRole('button', { name: 'Resume', exact: true }).click()
    await expect(graphRow).toContainText(/RUNNING|READY.*RESUME_REQUESTED/)
    await graphRow.getByRole('button', { name: 'Cancel', exact: true }).click()
    await expect(graphRow).toContainText('CANCELLED')
    const completed = await graph.completion
    expect(completed.status()).toBe(200)
  } finally {
    await fixture.close()
  }
}

async function stopRuntime(page: Page) {
  await navigate(page, 'en-US', 'runtime')
  await clickPlan(page, 'en-US', copy['en-US'].actions.runtimeStop)
}

async function repairDoctorCheck(page: Page, code: string) {
  await navigate(page, 'en-US', 'doctor')
  await page.getByRole('button', { name: 'Check again', exact: true }).click()
  const row = page.locator('article').filter({ hasText: code })
  await expect(row.getByRole('button', { name: 'Repair', exact: true })).toBeVisible()
  await row.getByRole('button', { name: 'Repair', exact: true }).click()
  await confirmAndWait(page, 'en-US')
}

async function verifyDoctorRepairButtons(page: Page, instance: E2EInstance) {
  for (const code of ['runtime.pid', 'runtime.health', 'protocol.compatible']) {
    await repairDoctorCheck(page, code)
    await stopRuntime(page)
  }

  const profile = resolve(tmpdir(), 'mcac-playwright-fixture', 'local-app-data',
    'MinecraftAICompanion', 'profiles', instance.id as string)
  writeFileSync(resolve(profile, 'pairing.token'),
    'doctor-mismatch-token-that-is-long-enough-for-the-fixture\n', 'ascii')
  await repairDoctorCheck(page, 'runtime.token_match')

  const mods = resolve(tmpdir(), 'mcac-playwright-fixture', '.minecraft',
    'versions', 'Fabric 1.21.1', 'mods')
  const managedJar = readdirSync(mods, { recursive: true })
    .map((entry) => resolve(mods, String(entry)))
    .find((entry) => entry.toLowerCase().endsWith('.jar')
      && entry.toLowerCase().includes('minecraft-ai-companion'))
  expect(managedJar).toBeTruthy()
  appendFileSync(managedJar as string, 'playwright-hash-corruption')
  await repairDoctorCheck(page, 'install.hash')
}

async function verifyNavigationAndShell(page: Page, locale: Locale) {
  for (const route of Object.keys(copy[locale].nav) as Array<keyof typeof copy['en-US']['nav']>) {
    await navigate(page, locale, route)
  }

  const themeButton = page.getByRole('button', {
    name: locale === 'zh-CN' ? '切换深色或浅色主题' : 'Toggle light or dark theme',
  })
  const before = await page.locator('html').getAttribute('data-theme')
  await themeButton.click()
  await expect(page.locator('html')).not.toHaveAttribute('data-theme', before ?? '')

  const currentInstance = locale === 'zh-CN' ? '当前实例' : 'Current instance'
  await expect(page.getByRole('combobox', { name: currentInstance })).toHaveValue(/.+/)

  await navigate(page, locale, 'brain')
  await expect(page.getByRole('heading', {
    name: locale === 'zh-CN' ? '独立外部 Brain' : 'Independent external Brain',
  })).toBeVisible()
  await navigate(page, locale, 'provider')
  await expect(page.getByRole('heading', {
    name: locale === 'zh-CN' ? '旧版内部模型服务' : 'Legacy internal Provider',
  })).toBeVisible()
}

async function verifyManagedCriticalPath(page: Page, locale: Locale, targetedControls = false) {
  const labels = copy[locale].actions
  await navigate(page, locale, 'install')
  await clickPlan(page, locale, labels.install)
  const instances = await apiJson(page, '/api/instances')
  expect(instances[0].installed).toBe(true)

  await clickPlan(page, locale, labels.update)
  await clickPlan(page, locale, labels.repair)

  const rollback = page.locator('.action-sections section select')
  await expect(rollback.locator('option')).not.toHaveCount(1)
  await rollback.selectOption({ index: 1 })
  await clickPlan(page, locale, labels.rollback)
  await clickPlan(page, locale, labels.install)
  expect((await apiJson(page, '/api/instances'))[0].installed).toBe(true)

  await navigate(page, locale, 'runtime')
  await clickPlan(page, locale, labels.runtimeStart)
  const instanceId = instances[0].id as string
  let runtime = await apiJson(page, `/api/runtime/status?instanceId=${encodeURIComponent(instanceId)}`)
  expect(runtime.pidAlive).toBe(true)
  expect(runtime.healthy).toBe(true)
  expect(await portOpen(runtime.port as number)).toBe(true)

  await clickPlan(page, locale, labels.rotateToken)
  await clickPlan(page, locale, labels.runtimeRestart)
  runtime = await apiJson(page, `/api/runtime/status?instanceId=${encodeURIComponent(instanceId)}`)
  expect(runtime.pidAlive).toBe(true)
  expect(runtime.identityMatches).toBe(true)
  expect(runtime.detail).toContain('authenticated health identity verified')
  if (targetedControls) {
    await verifyCompanionAndTaskGraphControls(page, instanceId, runtime)
  }

  await navigate(page, locale, 'game')
  await clickPlanExpectFailure(page, locale, labels.attach, 'NO_ACTIVE_GAME_SESSION')
  const session = await apiJson(page, `/api/session/status?instanceId=${encodeURIComponent(instanceId)}`)
  expect(session.connected).toBe(false)
  expect(session.mode).toBe('SAFE_IDLE')
  expect(session.runtimeHealthy).toBe(true)

  await navigate(page, locale, 'logs')
  await page.locator('.segmented').getByRole('button', { name: locale === 'zh-CN' ? '运行服务' : 'Runtime service' }).click()
  await expect(page.getByText('runtime-process.log', { exact: true })).toBeVisible()
  await clickPlan(page, locale, labels.support)

  await navigate(page, locale, 'runtime')
  await clickPlan(page, locale, labels.runtimeStop)
  runtime = await apiJson(page, `/api/runtime/status?instanceId=${encodeURIComponent(instanceId)}`)
  expect(runtime.pidAlive).toBe(false)
  expect(await portOpen(runtime.port as number)).toBe(false)
  expect(await portOpen(runtime.healthPort as number)).toBe(false)
  if (targetedControls) {
    await verifyDoctorRepairButtons(page, instances[0])
  }
}

async function verifyCompatibilityLifecycle(page: Page) {
  const archive = page.locator('.compat-install input')
  const v1Path = resolve('..', '..', 'build', 'playwright-compat-v1.mcac-compat')
  const v2Path = resolve('..', '..', 'build', 'playwright-compat-v2.mcac-compat')
  const row = (version: string) => page.getByRole('row').filter({
    has: page.getByRole('cell', { name: version, exact: true }),
  })

  await archive.fill(v1Path)
  await clickPlan(page, 'zh-CN', '审阅安装计划')
  await expect(row('1.0.0')).toContainText('STAGING')
  await expect(row('1.0.0')).toContainText('等待系统生成的测试证据')
  await expect(row('1.0.0').getByRole('button', { name: '记录 Fixture 证据', exact: true })).toHaveCount(0)

  await archive.fill(v2Path)
  await clickPlan(page, 'zh-CN', '审阅更新计划')
  await expect(row('2.0.0')).toContainText('STAGING')
  await expect(row('2.0.0')).toContainText('等待系统生成的测试证据')
  await row('2.0.0').getByRole('button', { name: '隔离', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('2.0.0')).toContainText('QUARANTINED')
  await row('2.0.0').getByRole('button', { name: '移除', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('2.0.0')).toHaveCount(0)

  await row('1.0.0').getByRole('button', { name: '隔离', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toContainText('QUARANTINED')
  await row('1.0.0').getByRole('button', { name: '移除', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toHaveCount(0)
}

test('packaged Terminal completes bilingual real-backend product paths', async ({ page }) => {
  test.setTimeout(420_000)
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    const text = message.text()
    const expectedUnavailableResource =
      text === 'Failed to load resource: the server responded with a status of 409 (Conflict)'
    if (message.type() === 'error' && !expectedUnavailableResource) consoleErrors.push(text)
  })
  const state = JSON.parse(
    readFileSync(resolve(tmpdir(), 'mcac-playwright-server.json'), 'utf8'),
  ) as { bootstrapUrl: string }
  const hermes = await startHermesFixture()
  activeHermesServer = hermes.server

  await page.goto(state.bootstrapUrl)
  await expect(page).toHaveTitle('Minecraft AI Companion')
  const discovered = await apiJson(page, '/api/instances') as Array<E2EInstance & { name: string }>
  expect(discovered).toHaveLength(1)
  await expect(page.getByRole('heading', { name: discovered[0].name, exact: true })).toBeVisible()

  mkdirSync(resolve('..', '..', 'output', 'playwright'), { recursive: true })

  for (const locale of ['zh-CN', 'en-US'] as const) {
    await setLocale(page, locale)
    await verifyNavigationAndShell(page, locale)
    if (locale === 'en-US') {
      await verifyDiscoveryAndBrain(page, hermes.endpoint, hermes.calls)
    }
    await navigate(page, locale, 'compatibility')
    const selected = await apiJson(page, '/api/instances')
    const compatibility = await apiJson(page,
      `/api/compatibility?instanceId=${encodeURIComponent(selected[0].id as string)}`)
    expect(compatibility.fingerprint.digest).toMatch(/^[a-f0-9]{64}$/)
    await expect(page.locator('main')).not.toContainText('undefined')
    await page.screenshot({
      path: resolve('..', '..', 'output', 'playwright', `compatibility-${locale}.png`),
      fullPage: true,
    })
    if (locale === 'zh-CN') await verifyCompatibilityLifecycle(page)
    await verifyManagedCriticalPath(page, locale, locale === 'en-US')
  }

  await setLocale(page, 'zh-CN')
  await navigate(page, 'zh-CN', 'install')
  await clickPlan(page, 'zh-CN', copy['zh-CN'].actions.uninstallKeep)
  const instances = await apiJson(page, '/api/instances')
  const instanceId = instances[0].id as string
  let runtime = await apiJson(page, `/api/runtime/status?instanceId=${encodeURIComponent(instanceId)}`)
  expect(runtime.configured).toBe(true)
  expect(runtime.pidAlive).toBe(false)

  await clickPlan(page, 'zh-CN', copy['zh-CN'].actions.install)
  await clickPlan(page, 'zh-CN', copy['zh-CN'].actions.uninstallDelete)
  runtime = await apiJson(page, `/api/runtime/status?instanceId=${encodeURIComponent(instanceId)}`)
  expect(runtime.configured).toBe(false)

  expect(consoleErrors).toEqual([])

  await navigate(page, 'zh-CN', 'settings')
  await page.getByRole('button', { name: '生成停止计划', exact: true }).click()
  await page.getByRole('button', { name: '确认停止后台', exact: true }).click()
  await expect(page.getByText('后台正在停止，可以关闭此页面。', { exact: true })).toBeVisible()
})
