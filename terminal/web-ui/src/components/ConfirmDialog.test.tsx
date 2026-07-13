import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({
    pendingPlan: null,
    dismissPlan: vi.fn(),
    confirmPlan: vi.fn(),
    planError: null,
    operation: {
      id: 'operation-1',
      category: 'runtime',
      action: 'rotate-token',
      instanceId: 'fabric-1.21.1',
      state: 'FAILED',
      progress: 100,
      message: '执行失败，已应用业务层回滚策略',
      error: 'Runtime 本地端口未能安全释放',
      startedAt: '2026-07-13T00:00:00Z',
      finishedAt: '2026-07-13T00:00:01Z',
    },
  }),
}))

describe('ConfirmDialog', () => {
  it('shows the structured backend error for a failed operation', () => {
    render(<ConfirmDialog />)
    expect(screen.getByText('FAILED')).toBeVisible()
    expect(screen.getByText('Runtime 本地端口未能安全释放')).toBeVisible()
    expect(screen.getAllByRole('button', { name: '关闭' })).toHaveLength(2)
  })
})
