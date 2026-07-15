import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CompanionsPage } from './CompanionsPage'

const requestPlan = vi.fn()
afterEach(() => cleanup())

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
      conversations: [{ eventId: 'e1', companionId: 'companion-1', direction: 'ASSISTANT', kind: 'QUESTION', content: '箱子里只有 6 个，还差 10 个。', gameDelivered: true, createdAt: 1 }],
      waitingQuestions: [{ questionId: 'q1', planId: 'p1', companionId: 'companion-1', prompt: '你想怎么做？', reason: 'RESOURCE_SHORTAGE', freeTextAllowed: true, state: 'WAITING', createdAt: 1, updatedAt: 1, options: [{ id: 'partial', label: '先拿 6 个', description: '交付现有数量' }] }],
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

  it('shows durable background questions and conversation delivery state', () => {
    render(<CompanionsPage />)
    expect(screen.getByText('箱子里只有 6 个，还差 10 个。')).toBeVisible()
    expect(screen.getByText('你想怎么做？')).toBeVisible()
    expect(screen.getByText('先拿 6 个')).toBeVisible()
    expect(screen.getByText('WAITING_FOR_USER')).toBeVisible()
  })

  it('submits a stable waiting-question option through the agent flow', () => {
    requestPlan.mockClear()
    const { container } = render(<CompanionsPage />)
    const option = container.querySelector<HTMLButtonElement>('.conversation-question button')
    expect(option).not.toBeNull()
    fireEvent.click(option!)
    expect(requestPlan).toHaveBeenCalledWith('agent', {
      instanceId: 'instance-1',
      companionId: 'companion-1',
      text: 'partial',
    })
  })

  it('submits a free-text answer or replacement goal for a waiting question', () => {
    requestPlan.mockClear()
    render(<CompanionsPage />)
    const input = screen.getByRole('textbox', { name: '回答问题：你想怎么做？' })
    fireEvent.change(input, { target: { value: '不要铁锭了，改为跟随我' } })
    fireEvent.click(screen.getByRole('button', { name: '发送回答' }))
    expect(requestPlan).toHaveBeenCalledWith('agent', {
      instanceId: 'instance-1',
      companionId: 'companion-1',
      text: '不要铁锭了，改为跟随我',
    })
  })
})
