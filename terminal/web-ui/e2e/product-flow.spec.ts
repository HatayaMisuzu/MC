import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { Socket } from 'node:net'
import { resolve } from 'node:path'

type Locale = 'zh-CN' | 'en-US'

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
      attach: '附加当前会话',
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
      attach: 'Attach current session',
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

async function apiJson(page: Page, path: string) {
  return page.evaluate(async (url) => {
    const response = await fetch(url, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-MCAC-CSRF': sessionStorage.getItem('mcac.csrf') ?? '' },
    })
    if (!response.ok) throw new Error(`API ${url} failed with ${response.status}: ${await response.text()}`)
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
}

async function verifyManagedCriticalPath(page: Page, locale: Locale) {
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

  await navigate(page, locale, 'game')
  await clickPlan(page, locale, labels.attach)
  const session = await apiJson(page, `/api/session/status?instanceId=${encodeURIComponent(instanceId)}`)
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
  await row('1.0.0').getByRole('button', { name: '记录 Fixture 证据', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toContainText('TESTED')
  await row('1.0.0').getByRole('button', { name: '验证并建立索引', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toContainText('VERIFIED')
  await row('1.0.0').getByRole('button', { name: '激活', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toContainText('ACTIVE')

  await archive.fill(v2Path)
  await clickPlan(page, 'zh-CN', '审阅更新计划')
  await expect(row('2.0.0')).toContainText('STAGING')
  await row('2.0.0').getByRole('button', { name: '记录 Fixture 证据', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await row('2.0.0').getByRole('button', { name: '验证并建立索引', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await row('2.0.0').getByRole('button', { name: '激活', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('2.0.0')).toContainText('ACTIVE')

  await row('2.0.0').getByRole('button', { name: '回滚', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toContainText('ACTIVE')
  await row('2.0.0').getByRole('button', { name: '隔离', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('2.0.0')).toContainText('QUARANTINED')
  await row('2.0.0').getByRole('button', { name: '移除', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('2.0.0')).toHaveCount(0)

  await row('1.0.0').getByRole('button', { name: '禁用', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toContainText('DISABLED')
  await row('1.0.0').getByRole('button', { name: '移除', exact: true }).click()
  await confirmAndWait(page, 'zh-CN')
  await expect(row('1.0.0')).toHaveCount(0)
}

test('packaged Terminal completes bilingual real-backend product paths', async ({ page }) => {
  test.setTimeout(300_000)
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    const text = message.text()
    const expectedUnavailableResource =
      text === 'Failed to load resource: the server responded with a status of 409 (Conflict)'
    if (message.type() === 'error' && !expectedUnavailableResource) consoleErrors.push(text)
  })
  const state = JSON.parse(
    readFileSync(resolve('..', '..', 'build', 'playwright-server.json'), 'utf8'),
  ) as { bootstrapUrl: string }

  await page.goto(state.bootstrapUrl)
  await expect(page).toHaveTitle('Minecraft AI Companion')
  await expect(page.getByRole('heading', { name: 'Fabric 1.21.1', exact: true })).toBeVisible()

  mkdirSync(resolve('..', '..', 'output', 'playwright'), { recursive: true })

  for (const locale of ['zh-CN', 'en-US'] as const) {
    await setLocale(page, locale)
    await verifyNavigationAndShell(page, locale)
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
    await verifyManagedCriticalPath(page, locale)
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
