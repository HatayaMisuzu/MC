import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DoctorPage } from './DoctorPage'

const requestPlan = vi.fn()

vi.mock('../api/client', () => ({ api: vi.fn() }))
vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({
    selected: { name: 'Fabric fixture', gameDir: 'fixture' },
    selectedId: 'instance-1',
    requestPlan,
  }),
}))
vi.mock('../hooks/useResource', () => ({
  useResource: () => ({
    loading: false,
    error: null,
    refresh: vi.fn(),
    data: {
      instanceId: 'instance-1',
      state: 'WARNING',
      checks: [
        { severity: 'WARNING', code: 'brain.protocol', summary: 'manual configuration',
          evidence: {}, repairs: ['Configure Brain'], repairable: false },
        { severity: 'WARNING', code: 'runtime.health', summary: 'runtime stopped',
          evidence: {}, repairs: ['Restart Runtime'], repairable: true, repairAction: 'runtime/restart' },
      ],
    },
  }),
}))

afterEach(() => {
  cleanup()
  requestPlan.mockClear()
})

describe('DoctorPage', () => {
  it('offers repair only for checks with an executable backend route', () => {
    render(<DoctorPage />)
    const repair = screen.getByRole('button', { name: 'Repair' })
    expect(screen.getAllByRole('button', { name: 'Repair' })).toHaveLength(1)
    fireEvent.click(repair)
    expect(requestPlan).toHaveBeenCalledWith('doctor/repair', {
      instanceId: 'instance-1',
      code: 'runtime.health',
    })
  })
})
