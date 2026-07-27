import { RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { CompanionSnapshot, SkillSnapshot, SkillVersion, WorkspaceDraft } from '../types'

export function SkillsPage() {
  const { selected, selectedId } = useTerminal()
  const { t } = useI18n()
  const [selectedCompanion, setSelectedCompanion] = useState('')
  const [error, setError] = useState('')
  const companions = useResource<CompanionSnapshot>(() => selectedId
    ? api<CompanionSnapshot>(`/api/companions?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve({ instanceId: '', mode: 'SAFE_IDLE', companions: [], tasks: [], events: [], conversations: [], waitingQuestions: [] }), [selectedId])
  const companionId = companions.data?.companions.some((value) => value.id === selectedCompanion)
    ? selectedCompanion : companions.data?.companions[0]?.id ?? ''
  const skills = useResource<SkillSnapshot>(() => selectedId && companionId
    ? api<SkillSnapshot>(`/api/skills?instanceId=${encodeURIComponent(selectedId)}&companionId=${encodeURIComponent(companionId)}`)
    : Promise.resolve({ companionId: '', builtins: [], drafts: [], versions: [] }), [selectedId, companionId])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('skills.empty')}</EmptyState>

  const manage = async (version: SkillVersion, action: 'approve' | 'reject' | 'disable' | 'rollback') => {
    setError('')
    try {
      await post('/api/skills/manage', {
        instanceId: selectedId,
        companionId,
        action,
        requestId: version.requestId,
        skillId: version.skillId,
        version: version.version,
        ...(action === 'approve' ? {} : { reason: `${action} by local user` }),
      })
      await skills.refresh()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t('skills.manageFailed'))
    }
  }

  const restoreDraft = async (draft: WorkspaceDraft, version: number) => {
    const match = /^skills\/([a-z][a-z0-9_-]{2,63})\/draft\.(yaml|yml|json)$/.exec(draft.logicalPath)
    if (!match) { setError(t('skills.invalidDraft')); return }
    setError('')
    try {
      await post('/api/skills/manage', {
        instanceId: selectedId, companionId, action: 'restore_draft',
        skillId: match[1], format: match[2], version,
      })
      await skills.refresh()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t('skills.restoreFailed'))
    }
  }

  const revokeTrial = async (leaseId: string) => {
    setError('')
    try {
      await post('/api/skills/manage', {
        instanceId: selectedId, companionId, action: 'revoke_trial', leaseId,
      })
      await skills.refresh()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t('skills.revokeFailed'))
    }
  }

  return <div className="page">
    <PageHeader title={t('skills.title')} description={t('skills.description')}
      actions={<ActionButton icon={<RefreshCw size={15} />} onClick={() => { void companions.refresh(); void skills.refresh() }}>{t('common.refresh')}</ActionButton>} />
    <section className="companion-toolbar"><label className="field"><span>{t('term.companion')}</span>
      <select value={companionId} onChange={(event) => setSelectedCompanion(event.target.value)}>
        {(companions.data?.companions ?? []).map((companion) => <option key={companion.id} value={companion.id}>{companion.displayName}</option>)}
      </select></label></section>
    {error && <p role="alert">{error}</p>}
    {!companionId ? <EmptyState title={t('brain.noCompanion')}>{t('skills.noCompanionBody')}</EmptyState>
      : <>
        <h2>{t('skills.builtins')}</h2>
        <div className="event-rows">{(skills.data?.builtins ?? []).map((builtin) =>
          <article className="event-row" key={builtin.skillId}>
            <time>{builtin.format}</time><strong>{builtin.skillId}</strong>
            <StatusBadge value={builtin.trust} /><span>{builtin.sha256.slice(0, 12)} · read-only</span>
          </article>)}</div>
        <h2>{t('skills.drafts')}</h2>
        <div className="event-rows">{(skills.data?.drafts ?? []).map((draft) =>
          <article className="event-row skill-review-card" key={draft.logicalPath}>
            <time>v{draft.version}</time><strong>{draft.logicalPath}</strong>
            <StatusBadge value="QUARANTINED" /><span>{draft.sha256.slice(0, 12)} · {draft.sizeBytes} bytes</span>
            <details><summary>{t('skills.reviewDraft')}</summary><pre>{draft.document}</pre></details>
            <div className="inline-actions">{draft.retainedVersions.map((retained) =>
              <ActionButton key={retained.version} onClick={() => void restoreDraft(draft, retained.version)}>
                {t('skills.restoreVersion', { version: retained.version })}
              </ActionButton>)}</div>
          </article>)}</div>
        <h2>{t('skills.versions')}</h2>
        <div className="event-rows">{(skills.data?.versions ?? []).map((version) =>
        <article className="event-row skill-review-card" key={version.requestId}>
          <time>v{version.version} · {version.format}</time><strong>{version.skillId}</strong>
          <StatusBadge value={version.status} /><span>{version.sha256.slice(0, 12)} · {version.controllerId}</span>
          <p>{t('skills.permissions')}: {JSON.stringify(version.permissions)} · {t('skills.validation')}: {JSON.stringify(version.validation)}</p>
          <details><summary>{t('skills.reviewDocument')}</summary><pre>{version.document}</pre></details>
          <div className="inline-actions">
            <ActionButton tone="primary" disabled={version.status !== 'PENDING_REVIEW'} onClick={() => void manage(version, 'approve')}>{t('brain.approve')}</ActionButton>
            <ActionButton disabled={version.status !== 'PENDING_REVIEW'} onClick={() => void manage(version, 'reject')}>{t('brain.reject')}</ActionButton>
            <ActionButton disabled={version.status !== 'ACTIVE'} onClick={() => void manage(version, 'disable')}>{t('compat.deactivate')}</ActionButton>
            <ActionButton disabled={!['SUPERSEDED', 'DISABLED'].includes(version.status) || !version.approvedAt}
              onClick={() => void manage(version, 'rollback')}>{t('compat.rollback')}</ActionButton>
          </div>
        </article>)}</div>
        <h2>{t('skills.trials')}</h2>
        <p>{t('skills.trialBoundary')}</p>
        <div className="event-rows">{(skills.data?.trials ?? []).map((trial) =>
          <article className="event-row skill-review-card" key={trial.leaseId}>
            <time>{new Date(trial.expiresAt).toLocaleString()}</time><strong>{trial.skillId}</strong>
            <StatusBadge value={trial.status} />
            <span>{t('skills.uses', { count: trial.remainingUses })} · {trial.tools.join(', ') || t('skills.noToolCalls')}</span>
            <p>{t('skills.permissions')}: {JSON.stringify(trial.permissions)} · {t('skills.limits')}: {JSON.stringify(trial.limits)}</p>
            <p>{t('skills.execution')}: {trial.executionId || t('runtime.notStarted')} · {t('skills.evidence')}: {JSON.stringify(trial.evidence)}</p>
            <ActionButton tone="danger" disabled={!['AVAILABLE', 'RUNNING'].includes(trial.status)}
              onClick={() => void revokeTrial(trial.leaseId)}>{t('skills.revokeTrial')}</ActionButton>
          </article>)}</div>
      </>}
  </div>
}
