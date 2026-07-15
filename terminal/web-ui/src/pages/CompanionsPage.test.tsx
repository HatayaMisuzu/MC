import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CompanionsPage } from './CompanionsPage'

const requestPlan = vi.fn()

vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({
    selected: { mode: 'FULL', loader: 'FABRIC' },
    selectedId: 'instance-1',
    requestPlan,
    companionSnapshot: null,
  }),
}))

vi.mock('../hooks/useResource', () => ({
  useResource: () => ({
    data: {
      instanceId: 'instance-1',
      mode: 'SAFE_IDLE',
      companions: [{ id: 'companion-1', displayName: 'Misuzu', online: true, leaseActive: false, status: {} }],
      tasks: [],
      events: [],
    },
    refresh: vi.fn(),
  }),
}))

describe('CompanionsPage text companion input', () => {
  it('sends a natural-language goal through the reviewed agent plan flow', () => {
    render(<CompanionsPage />)
    const input = screen.getByPlaceholderText(/去基地箱子拿16个铁锭/)
    fireEvent.change(input, { target: { value: '帮我准备一把铁镐，材料不够就告诉我' } })
    fireEvent.click(screen.getByRole('button', { name: '发送目标' }))
    expect(requestPlan).toHaveBeenCalledWith('agent', {
      instanceId: 'instance-1',
      companionId: 'companion-1',
      text: '帮我准备一把铁镐，材料不够就告诉我',
    })
    expect(screen.getByText(/模型不能直接执行脚本/)).toBeVisible()
  })
})
