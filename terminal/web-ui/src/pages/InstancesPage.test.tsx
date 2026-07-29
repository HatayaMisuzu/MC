import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InstancesPage } from './InstancesPage'

const refreshInstances = vi.fn(() => Promise.resolve())
const refreshLaunchers = vi.fn(() => Promise.resolve())
const { post } = vi.hoisted(() => ({ post: vi.fn(() => Promise.resolve({ rescanned: true })) }))

vi.mock('../api/client', () => ({ api: vi.fn(), post }))
vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({
    instances: [], selectedId: '', select: vi.fn(), refresh: refreshInstances,
  }),
}))
vi.mock('../hooks/useResource', () => ({
  useResource: () => ({
    data: [], loading: false, error: null, refresh: refreshLaunchers,
  }),
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('InstancesPage', () => {
  it('invalidates backend discovery before refreshing visible launchers and instances', async () => {
    render(<InstancesPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Rescan' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/discovery/rescan', {}))
    expect(refreshLaunchers).toHaveBeenCalled()
    expect(refreshInstances).toHaveBeenCalled()
  })
})
