import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SmokePage } from './SmokePage'

const terminal = {
  selected: { id: 'instance-a', name: 'A', minecraftVersion: '1.20.1', loader: 'FORGE',
    mode: 'FULL', installed: true },
  selectedId: 'instance-a',
  requestPlan: vi.fn(),
  operation: { id: 'op-b', category: 'smoke', instanceId: 'instance-b', state: 'RUNNING' },
}

vi.mock('../api/client', () => ({ api: vi.fn() }))
vi.mock('../context/TerminalContext', () => ({ useTerminal: () => terminal }))
vi.mock('../hooks/useResource', () => ({ useResource: () => ({
  data: { instanceId: 'instance-a', state: 'SUCCEEDED', success: true,
    manualRequired: false, summary: 'passed' },
}) }))

afterEach(() => cleanup())

describe('SmokePage instance-scoped durable status', () => {
  it('ignores a running smoke operation owned by another instance', () => {
    render(<SmokePage />)
    expect(screen.getByText('SUCCEEDED')).toBeVisible()
    expect(screen.queryByText('RUNNING')).toBeNull()
  })

  it('prefers the selected instance current operation over its persisted result', () => {
    terminal.operation = { ...terminal.operation, id: 'op-a', instanceId: 'instance-a' }
    render(<SmokePage />)
    expect(screen.getByText('RUNNING')).toBeVisible()
    expect(screen.queryByText('SUCCEEDED')).toBeNull()
  })
})
