import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { InstallPage } from './InstallPage'

const { requestPlan } = vi.hoisted(() => ({ requestPlan: vi.fn(() => Promise.resolve()) }))
vi.mock('../api/client', () => ({ api: vi.fn(() => Promise.resolve([])) }))
vi.mock('../hooks/useResource', () => ({ useResource: () => ({ data: [], refresh: vi.fn() }) }))
vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({
    selectedId: 'instance-1', requestPlan,
    selected: { id: 'instance-1', name: 'Fabric', compatible: true, installed: true, gameDir: 'D:/Minecraft', mode: 'FULL' },
  }),
}))

afterEach(() => cleanup())

it('offers separate preserve-data and delete-data uninstall plans', () => {
  requestPlan.mockClear()
  render(<InstallPage />)
  fireEvent.click(screen.getByRole('button', { name: '卸载并保留数据' }))
  expect(requestPlan).toHaveBeenCalledWith('install', { instanceId: 'instance-1', action: 'uninstall' })
  fireEvent.click(screen.getByRole('button', { name: '卸载并删除 MCAC 数据' }))
  expect(requestPlan).toHaveBeenCalledWith('install', { instanceId: 'instance-1', action: 'uninstall-delete-data' })
  expect(screen.getByText(/世界、启动器账号和其他 Mod 始终保留/)).toBeVisible()
})
