import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BrainPage } from './BrainPage'

const requestPlan = vi.fn()
const { post } = vi.hoisted(() => ({ post: vi.fn(() => Promise.resolve({})) }))
let resourceCall = 0

vi.mock('../api/client', () => ({ api: vi.fn(), post }))

vi.mock('../context/TerminalContext', () => ({
  useTerminal: () => ({ selected: { mode: 'FULL', loader: 'FABRIC' }, selectedId: 'instance-1', requestPlan }),
}))

vi.mock('../hooks/useResource', () => ({
  useResource: () => {
    const values = [
      { instanceId: 'instance-1', mode: 'SAFE_IDLE', companions: [{ id: 'c1', displayName: 'Misuzu' }], tasks: [], events: [], conversations: [], waitingQuestions: [] },
      { mode: 'hermes', endpoint: 'http://127.0.0.1:8080', tokenEnv: 'MCAC_BRAIN_TOKEN',
        model: 'hermes', timeoutSeconds: 60, maxToolCallsPerTurn: 12, maxOutputTokens: 1024 },
      { activeControllerId: 'runtime-primary', health: { status: 'CONFIGURED', adapter: 'hermes', detail: '', checkedAt: '' },
        contextBudget: { totalChars: 40000, worldChars: 12000, conversationChars: 10000, taskChars: 8000,
          approvedMemoryChars: 6000, episodeCapsuleChars: 6000, fullGraphIncluded: false,
          fullToolLogIncluded: false, fullSearchPageIncluded: false } },
      [{ sessionId: 's1', controllerId: 'runtime-primary', provider: 'replay', state: 'ACTIVE', lastCode: 'FINAL_RESPONSE', createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:01Z',
        semanticStateRevision: 2, semanticStateAuthoredAt: '2026-07-15T00:00:01Z',
        semanticState: { schemaVersion: 1, conversationContext: 'Owner requested status', immediateInstruction: 'Observe',
          currentTask: 'Inspect the base', longTermGoal: 'Keep supplies ready', pauseReason: '', userTakeover: false,
          initiativeMode: 'NORMAL', personalityMode: 'COMPANION', permissionPreset: 'ASK_FOR_EFFECTS',
          playerExplicitlyAway: false, latestRealWorldObservationAt: '2026-07-15T00:00:00Z',
          staleAssumptions: ['old chest count'] },
        completionClaims: [{ sequence: 1, certainty: 'VERIFIED', claim: 'Base state checked',
          observationCallId: 't1', taskId: 'task-1',
          conditions: [{ pointer: '/health', operator: 'AT_LEAST', expected: 18 }],
          explanation: '', createdAt: '2026-07-15T00:00:01Z' }],
        toolCalls: [{ callId: 't1', toolName: 'search.query', success: true, code: 'OK', terminal: true, observation: { sources: 1 } }] }],
      { companionId: 'c1', initiativeMode: 'NORMAL', personalityMode: 'COMPANION', revision: 1,
        updatedBy: 'LOCAL_MANAGEMENT_USER', updatedAt: '2026-07-15T00:00:00Z',
        changesToolPermissions: false, changesSafetyPolicy: false, changesBudgets: false, changesMemoryPolicy: false },
      { companionId: 'c1', byKind: { PREFERENCE: [{ memoryId: 'm1', kind: 'PREFERENCE', key: 'reply_style', value: 'concise', verified: false, confidence: 0.7, source: 'INFERENCE', createdAt: '', updatedAt: '2026-07-15T00:00:00Z' }] },
        settings: { companionId: 'c1', autoSaveEnabled: true, revision: 1, updatedBy: 'LOCAL_MANAGEMENT_USER', updatedAt: '2026-07-15T00:00:00Z' },
        history: [{ historyId: 'h1', memoryId: 'm1', companionId: 'c1', kind: 'PREFERENCE', key: 'reply_style',
          value: 'verbose', verified: true, confidence: 1, source: 'USER', changeKind: 'EDITED',
          changedBy: 'LOCAL_MANAGEMENT_USER', changedAt: '2026-07-15T00:00:00Z' }],
        safeSummary: { companionId: 'c1', containsValues: false, counts: { PREFERENCE: 1 } },
        suggestions: [{ suggestionId: 'ms1', companionId: 'c1', kind: 'WORLD', key: 'landmark:moon', value: { dimension: 'examplemod:moon' }, confidence: 0.5, status: 'QUARANTINED', source: 'EPISODE_CAPSULE', brainSessionId: 'b1', capsuleId: 'episode-1', conflictsWithVerified: true, expiresAt: '', createdAt: '', updatedAt: '' }],
        episodeCapsules: [{ episodeId: 'episode-1', companionId: 'c1', brainSessionId: 'b1', startedAt: '2026-07-15T00:00:00Z', endedAt: '2026-07-15T00:01:00Z', taskSummaries: [], verifiedWorldChanges: [], verifiedInventoryChanges: [], verifiedLocations: [], askUserDecisions: [], userConfirmedChoices: [], failureCategories: [], evidenceRefs: [{ callId: 't1' }], sourceSha: 'abc1234', createdAt: '2026-07-15T00:01:00Z' }] },
    ]
    return { data: values[(resourceCall++) % values.length], refresh: vi.fn(), loading: false, error: null }
  },
}))

beforeEach(() => { resourceCall = 0; requestPlan.mockClear(); post.mockClear() })
afterEach(() => cleanup())

describe('BrainPage', () => {
  it('shows Brain audit, quarantined memory review, and submits through the external Brain flow', async () => {
    render(<BrainPage />)
    expect(screen.getByText('search.query')).toBeVisible()
    expect(screen.getByText(/EPISODE_CAPSULE/)).toBeVisible()
    expect(screen.getByText('CONFLICT')).toBeVisible()
    expect(screen.getByText(/Total 40000 chars/)).toBeVisible()
    expect(screen.getByText('episode-1')).toBeVisible()
    expect(screen.getByText('Inspect the base')).toBeVisible()
    expect(screen.getByText(/old chest count/)).toBeVisible()
    expect(screen.getByText('Base state checked')).toBeVisible()
    expect(screen.getByText(/final observation t1/)).toBeVisible()
    expect(screen.getByText('Memory management')).toBeVisible()
    expect(screen.getByText('EDITED')).toBeVisible()
    fireEvent.change(screen.getByLabelText('Initiative'), { target: { value: 'QUIET' } })
    expect(post).toHaveBeenCalledWith('/api/brain/settings', {
      instanceId: 'instance-1', companionId: 'c1', initiativeMode: 'QUIET', personalityMode: 'COMPANION',
    })
    const input = screen.getByPlaceholderText(/Ask a question/)
    fireEvent.change(input, { target: { value: 'Check the Fabric docs' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(requestPlan).toHaveBeenCalledWith('agent', {
      instanceId: 'instance-1', companionId: 'c1', text: 'Check the Fabric docs',
    })
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    expect(post).toHaveBeenCalledWith('/api/memories/review', {
      instanceId: 'instance-1', companionId: 'c1', suggestionId: 'ms1',
      action: 'approve_suggestion',
    })
    fireEvent.change(screen.getByLabelText('Automatic observed-memory save'), { target: { value: 'false' } })
    expect(post).toHaveBeenCalledWith('/api/memories/manage', {
      instanceId: 'instance-1', companionId: 'c1', action: 'set_autosave', enabled: false,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Export safe summary' }))
    expect(post).toHaveBeenCalledWith('/api/memories/manage', {
      instanceId: 'instance-1', companionId: 'c1', action: 'export_safe_summary',
    })
  })
})
