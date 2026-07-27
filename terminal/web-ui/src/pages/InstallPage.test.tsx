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
  fireEvent.click(screen.getByRole('button', { name: 'Uninstall and keep data' }))
  expect(requestPlan).toHaveBeenCalledWith('install', { instanceId: 'instance-1', action: 'uninstall' })
  fireEvent.click(screen.getByRole('button', { name: 'Uninstall and delete MCAC data' }))
  expect(requestPlan).toHaveBeenCalledWith('install', { instanceId: 'instance-1', action: 'uninstall-delete-data' })
  expect(screen.getByText(/Worlds, launcher accounts, and other Mods are preserved/)).toBeVisible()
})
