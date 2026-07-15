import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BrainPage } from './BrainPage'

const requestPlan = vi.fn()
let resourceCall = 0

vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({ selected: { mode: 'FULL', loader: 'FABRIC' }, selectedId: 'instance-1', requestPlan }),
}))

vi.mock('../hooks/useResource', () => ({
  useResource: () => {
    const values = [
      { instanceId: 'instance-1', mode: 'SAFE_IDLE', companions: [{ id: 'c1', displayName: 'Misuzu' }], tasks: [], events: [], conversations: [], waitingQuestions: [] },
      { activeControllerId: 'runtime-primary', health: { status: 'CONFIGURED', adapter: 'hermes', detail: '', checkedAt: '' } },
      [{ sessionId: 's1', controllerId: 'runtime-primary', provider: 'replay', state: 'ACTIVE', lastCode: 'FINAL_RESPONSE', createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:01Z', toolCalls: [{ callId: 't1', toolName: 'search.query', success: true, code: 'OK', terminal: true, observation: { sources: 1 } }] }],
      { companionId: 'c1', byKind: { PREFERENCE: [{ memoryId: 'm1', kind: 'PREFERENCE', key: 'reply_style', value: 'concise', verified: false, confidence: 0.7, source: 'EXTERNAL_BRAIN_SUGGESTION', createdAt: '', updatedAt: '' }] } },
    ]
    return { data: values[(resourceCall++) % values.length], refresh: vi.fn(), loading: false, error: null }
  },
}))

beforeEach(() => { resourceCall = 0; requestPlan.mockClear() })
afterEach(() => cleanup())

describe('BrainPage', () => {
  it('shows Brain audit and memory provenance and submits through the external Brain flow', () => {
    render(<BrainPage />)
    expect(screen.getByText('search.query')).toBeVisible()
    expect(screen.getByText(/EXTERNAL_BRAIN_SUGGESTION/)).toBeVisible()
    const input = screen.getByPlaceholderText(/Ask a question/)
    fireEvent.change(input, { target: { value: 'Check the Fabric docs' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(requestPlan).toHaveBeenCalledWith('agent', {
      instanceId: 'instance-1', companionId: 'c1', text: 'Check the Fabric docs',
    })
  })
})
