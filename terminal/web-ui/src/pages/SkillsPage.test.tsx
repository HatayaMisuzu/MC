import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { SkillsPage } from './SkillsPage'

const { post } = vi.hoisted(() => ({ post: vi.fn(() => Promise.resolve({})) }))
vi.mock('../api/client', () => ({ api: vi.fn(), post }))
vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({ selected: { mode: 'FULL' }, selectedId: 'instance-1' }),
}))
vi.mock('../hooks/useResource', () => ({
  useResource: (_loader: unknown, dependencies: unknown[]) => dependencies.length === 1 ? ({
    data: { companions: [{ id: 'c1', displayName: 'Misuzu' }] }, refresh: vi.fn(),
  }) : ({
    data: {
      companionId: 'c1',
      builtins: [{ skillId: 'defend_owner', format: 'yaml', sha256: '1111222233334444', trust: 'BUILT_IN' }],
      drafts: [{ logicalPath: 'skills/safe_skill/draft.yaml', version: 3, sha256: 'dddd222233334444',
        sizeBytes: 40, updatedAt: '', document: 'root:\n  type: return',
        retainedVersions: [{ version: 2, sha256: 'old', sizeBytes: 35 }] }],
      versions: [{
      requestId: 'r1', companionId: 'c1', skillId: 'safe_skill', version: 2, format: 'yaml',
      document: 'root:\n  type: return', sha256: 'abcdef1234567890', permissions: ['READ_WORLD'],
      provenance: {}, validation: { valid: true }, status: 'PENDING_REVIEW',
      controllerId: 'hermes', brainSessionId: 'b1', createdAt: '', updatedAt: '',
      }],
    }, refresh: vi.fn(() => Promise.resolve()),
  }),
}))

afterEach(() => cleanup())

it('reviews a quarantined declarative Skill through the local management path', () => {
  post.mockClear()
  render(<SkillsPage />)
  expect(screen.getByText('safe_skill')).toBeVisible()
  expect(screen.getByText('defend_owner')).toBeVisible()
  expect(screen.getByText(/READ_WORLD/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
  expect(post).toHaveBeenCalledWith('/api/skills/manage', {
    instanceId: 'instance-1', companionId: 'c1', action: 'approve',
    requestId: 'r1', skillId: 'safe_skill', version: 2,
  })
  fireEvent.click(screen.getByRole('button', { name: 'Restore v2' }))
  expect(post).toHaveBeenCalledWith('/api/skills/manage', {
    instanceId: 'instance-1', companionId: 'c1', action: 'restore_draft',
    skillId: 'safe_skill', format: 'yaml', version: 2,
  })
})
