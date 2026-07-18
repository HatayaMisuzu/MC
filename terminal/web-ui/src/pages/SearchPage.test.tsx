import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SearchPage } from './SearchPage'

const { post, requestPlan, searchState } = vi.hoisted(() => ({
  post: vi.fn(), requestPlan: vi.fn(),
  searchState: { mode: 'http', endpoint: 'https://search.example/query', tokenEnv: 'MCAC_SEARCH_TOKEN',
    timeoutSeconds: 12, allowedDomains: ['docs.example'], deniedDomains: ['blocked.example'] },
}))
let resourceCall = 0
const searchSessions = { trustBoundary: 'UNTRUSTED_EXTERNAL_CONTENT', sessions: [{
  searchId: 'search-1', companionId: 'companion-1', query: 'Fabric docs',
  expiresAt: Date.now() + 60_000,
  sources: [{ sourceId: 'docs-1', title: 'Fabric Documentation',
    url: 'https://docs.fabricmc.net/', domain: 'docs.fabricmc.net',
    snippet: 'Official external documentation', trustLevel: 'UNTRUSTED_EXTERNAL_CONTENT',
    contentType: 'text/html' }],
}] }

vi.mock('../api/client', () => ({ api: vi.fn(), post }))
vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({ selected: { mode: 'FULL' }, selectedId: 'instance-1', requestPlan }),
}))
vi.mock('../hooks/useResource', () => ({
  useResource: () => ({
    data: [searchState, searchSessions][(resourceCall++) % 2],
    refresh: vi.fn(), loading: false, error: null,
  }),
}))

beforeEach(() => {
  resourceCall = 0
  requestPlan.mockClear()
  post.mockReset().mockResolvedValue({ success: true, networkAttempted: true, latencyMillis: 8,
    code: 'OK', message: 'Search provider accepted the bounded Doctor query' })
})
afterEach(() => cleanup())

describe('SearchPage', () => {
  it('submits bounded privacy policy and displays Search Doctor evidence', async () => {
    render(<SearchPage />)
    expect(screen.getByDisplayValue('docs.example')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '审阅 Search 配置计划' }))
    expect(requestPlan).toHaveBeenCalledWith('search', {
      instanceId: 'instance-1', action: 'configure', endpoint: 'https://search.example/query',
      tokenEnv: 'MCAC_SEARCH_TOKEN', timeoutSeconds: 12,
      allowedDomains: ['docs.example'], deniedDomains: ['blocked.example'],
    })

    fireEvent.click(screen.getByRole('button', { name: '测试连接' }))
    expect(await screen.findByText('Search provider accepted the bounded Doctor query')).toBeVisible()
    expect(post).toHaveBeenCalledWith('/api/search/test', { instanceId: 'instance-1' })
    expect(screen.getByText('已发送有界探针')).toBeVisible()
    const source = screen.getByRole('link', { name: '打开外部来源' })
    expect(source).toHaveAttribute('href', 'https://docs.fabricmc.net/')
    expect(source).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText('Official external documentation')).toBeVisible()
  })
})
