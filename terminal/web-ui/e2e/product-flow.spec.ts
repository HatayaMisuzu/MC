import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

async function confirmAndWait(page: Page) {
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '确认并执行', exact: true }).click()
  const state = dialog.locator('.operation-meta strong')
  await expect(state).toHaveText(/SUCCEEDED|FAILED/, { timeout: 60_000 })
  const stateText = await state.textContent()
  const error = stateText === 'FAILED' ? await dialog.locator('.inline-error').textContent() : ''
  expect(stateText, error ?? '').toBe('SUCCEEDED')
  await dialog.locator('footer').getByRole('button', { name: '关闭', exact: true }).click()
}

test('ordinary user completes the managed HTML product loop', async ({ page }) => {
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  const state = JSON.parse(
    readFileSync(resolve('..', '..', 'build', 'playwright-server.json'), 'utf8'),
  ) as { bootstrapUrl: string }

  await page.goto(state.bootstrapUrl)
  await expect(page).toHaveTitle('Minecraft AI Companion')
  await expect(page.getByRole('heading', { name: 'Fabric 1.21.1', exact: true })).toBeVisible()
  await expect(page.getByText('本地后端已断开')).toHaveCount(0)

  await page.getByRole('button', { name: '启动器与实例', exact: true }).click()
  await expect(page.getByRole('heading', { name: '启动器与实例', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'FABRIC 0.16.14', exact: true })).toBeVisible()
  await expect(page.getByText('VERSION_DIRECTORY', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '安装管理', exact: true }).click()
  await page.getByRole('button', { name: '生成安装计划', exact: true }).click()
  await expect(page.getByText('destination', { exact: true })).toBeVisible()
  await confirmAndWait(page)
  await expect(page.getByText('PASS', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: '验证并修复', exact: true }).click()
  await confirmAndWait(page)

  await page.getByRole('button', { name: 'Runtime', exact: true }).click()
  await page.getByRole('button', { name: '启动', exact: true }).click()
  await confirmAndWait(page)
  await expect(page.getByText('ONLINE', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('authenticated health identity verified')).toBeVisible()

  await page.getByRole('button', { name: '安全轮换 Token', exact: true }).click()
  await confirmAndWait(page)
  await expect(page.getByText('authenticated health identity verified')).toBeVisible()

  await page.getByRole('button', { name: '游戏启动', exact: true }).click()
  await page.getByRole('button', { name: '附加当前会话', exact: true }).click()
  await confirmAndWait(page)
  await expect(page.getByText('SAFE_IDLE', { exact: true }).first()).toBeVisible()

  await page.reload()
  await expect(page.getByRole('heading', { name: '游戏启动', exact: true })).toBeVisible()
  await expect(page.getByText('本地后端已断开')).toHaveCount(0)

  await page.getByRole('button', { name: 'Doctor', exact: true }).click()
  await expect(page.getByText('launcher.detected', { exact: true })).toBeVisible()
  await expect(page.getByText('UNKNOWN', { exact: true })).toHaveCount(0)

  await page.getByRole('button', { name: '日志与支持', exact: true }).click()
  await page.locator('.segmented').getByRole('button', { name: 'Runtime', exact: true }).click()
  await expect(page.getByText('runtime-process.log', { exact: true })).toBeVisible()
  await expect(page.getByText(/行 · 实时/)).toBeVisible()
  await page.getByRole('button', { name: '生成支持包', exact: true }).click()
  await confirmAndWait(page)

  await page.locator('nav[aria-label="主要功能"]').getByRole('button', { name: 'Runtime', exact: true }).click()
  await page.getByRole('button', { name: '停止', exact: true }).click()
  await confirmAndWait(page)

  await page.getByRole('button', { name: '安装管理', exact: true }).click()
  const rollback = page.locator('.action-sections section select')
  await rollback.selectOption({ index: 1 })
  await page.getByRole('button', { name: '审阅回滚计划', exact: true }).click()
  await confirmAndWait(page)

  await page.getByRole('button', { name: '生成安装计划', exact: true }).click()
  await confirmAndWait(page)
  await page.getByRole('button', { name: '审阅卸载计划', exact: true }).click()
  await confirmAndWait(page)

  expect(consoleErrors).toEqual([])

  await page.getByRole('button', { name: '设置与安全', exact: true }).click()
  await page.getByRole('button', { name: '生成停止计划', exact: true }).click()
  await page.getByRole('button', { name: '确认停止后台', exact: true }).click()
  await expect(page.getByText('后台服务正在停止，可以关闭此页面。', { exact: true })).toBeVisible()
})
