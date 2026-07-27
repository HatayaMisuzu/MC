import { Archive, RefreshCw, ShieldCheck } from 'lucide-react'
import { useMemo, useState } from 'react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { CompatibilityPackSummary, CompatibilitySnapshot } from '../types'

export function CompatibilityPage() {
  const { selected, selectedId, requestPlan, planError } = useTerminal()
  const { t } = useI18n()
  const [archivePath, setArchivePath] = useState('')
  const snapshot = useResource<CompatibilitySnapshot>(
    () => selectedId
      ? api(`/api/compatibility?instanceId=${encodeURIComponent(selectedId)}`)
      : Promise.reject(new Error('NO_INSTANCE')),
    [selectedId],
  )

  const plan = (action: string, pack?: CompatibilityPackSummary, extra: Record<string, unknown> = {}) =>
    requestPlan('compatibility', {
      instanceId: selectedId,
      action,
      ...(pack ? { coordinate: pack.coordinate, packId: pack.packId } : {}),
      ...extra,
    })

  const nextStep = useMemo(() => {
    const packs = snapshot.data?.packs ?? []
    if (snapshot.data?.matchedPacks.some((match) => match.stale)) return t('compat.next.stale')
    if (packs.some((pack) => ['STAGING', 'TESTED'].includes(pack.state))) return t('compat.next.staging')
    if (packs.some((pack) => pack.state === 'VERIFIED')) return t('compat.next.verified')
    return t('compat.next.none')
  }, [snapshot.data, t])

  if (!selected) return <EmptyState title={t('compat.noInstance.title')}>{t('compat.noInstance.body')}</EmptyState>

  const data = snapshot.data
  const issues = [...(data?.conflicts ?? []), ...(data?.suppressions ?? [])]
  return <div className="page compatibility-page">
    <PageHeader title={t('compat.title')} description={t('compat.description')}
      actions={<ActionButton icon={<RefreshCw size={15} />} loading={snapshot.loading}
        onClick={() => void snapshot.refresh()}>{t('common.refresh')}</ActionButton>} />

    {(snapshot.error || planError) && <p className="inline-error" role="alert">
      {t('compat.refreshError')} {snapshot.error || planError}
    </p>}

    <section className="compat-summary">
      <article>
        <span>{t('compat.fingerprint')}</span>
        <strong>{data?.fingerprint.digest?.slice(0, 16) ?? t('common.loading')}</strong>
        <small>{data ? `${data.fingerprint.minecraftVersion} · ${data.fingerprint.loader} ${data.fingerprint.loaderVersion}` : ''}</small>
        <small>{data ? t('compat.fingerprint.mods', { count: Object.keys(data.fingerprint.mods ?? {}).length }) : ''}</small>
      </article>
      <article>
        <span>{t('compat.capabilities')}</span>
        <strong>{data ? t('compat.capabilities.enabled', { enabled: data.enabledCapabilityCount, total: data.capabilityCount }) : t('common.loading')}</strong>
        <small>{t('compat.currentState')}: {data?.store ?? 'PROFILE_INSTANCE_SCOPED'}</small>
      </article>
      <article>
        <span>{t('compat.native.title')}</span>
        <strong><StatusBadge value={data?.nativeExecutionAvailable ? 'OPEN' : 'CLOSED'} /></strong>
        <small>{t('compat.native.closed')}</small>
      </article>
      <article>
        <span>{t('compat.authorization')}</span>
        <strong>{data?.authorization.maximumRisk ?? '—'}</strong>
        <small>{t('compat.authorization.scope')}</small>
      </article>
    </section>

    <section className="compat-next">
      <ShieldCheck size={20} />
      <div><strong>{t('compat.nextStep')}</strong><p>{nextStep}</p><small>{t('compat.help')}</small></div>
    </section>

    <section className="form-panel compat-install">
      <div><Archive size={20} /><h2>{t('compat.install')}</h2></div>
      <label className="field"><span>{t('compat.archivePath')}</span>
        <input value={archivePath} placeholder={t('compat.archivePlaceholder')}
          onChange={(event) => setArchivePath(event.target.value)} />
      </label>
      <ActionButton tone="primary" disabled={!archivePath.trim()}
        onClick={() => void plan('install', undefined, { archivePath: archivePath.trim(), source: 'terminal' })}>
        {t('compat.reviewInstall')}
      </ActionButton>
    </section>

    <section className="compat-panel">
      <header className="panel-header"><h2>{t('compat.packs')}</h2><span>{data?.packs.length ?? 0}</span></header>
      {!data?.packs.length ? <EmptyState title={t('compat.noPacks.title')}>{t('compat.noPacks.body')}</EmptyState>
        : <div className="table-scroll"><table className="data-table"><thead><tr>
          <th>{t('compat.pack')}</th><th>{t('compat.type')}</th><th>{t('compat.version')}</th>
          <th>{t('compat.hash')}</th><th>{t('compat.source')}</th><th>{t('compat.state')}</th><th>{t('compat.effective')}</th><th />
        </tr></thead><tbody>{data.packs.map((pack) => {
          const match = data.matchedPacks.find((value) => value.pack.manifest.coordinate === pack.coordinate)
          return <tr key={pack.coordinate}>
            <td>{pack.packId}</td><td>{pack.type}</td><td>{pack.version}</td>
            <td title={pack.contentHash}>{pack.contentHash.slice(0, 12)}</td>
            <td>{pack.source}</td>
            <td><StatusBadge value={pack.state} /> {match?.stale && <StatusBadge value="STALE" />}</td>
            <td>{match ? t('compat.active') : t('compat.inactive')}</td>
            <td><div className="inline-actions">
              {pack.state === 'STAGING' && <ActionButton onClick={() => void plan('record-evidence', pack, {
                evidenceId: `fixture-${Date.now()}`, kind: 'FIXTURE', matchLevel: 'EXACT',
                passed: true, summary: 'Fixture evidence recorded by authenticated local user',
                artifactHash: pack.contentHash,
              })}>{t('compat.evidenceFixture')}</ActionButton>}
              {pack.state === 'TESTED' && <ActionButton onClick={() => void plan('index', pack)}>{t('compat.index')}</ActionButton>}
              {pack.state === 'VERIFIED' && <ActionButton tone="primary" onClick={() => void plan('activate', pack)}>{t('compat.activate')}</ActionButton>}
              {pack.state === 'ACTIVE' && <ActionButton onClick={() => void plan('deactivate', pack)}>{t('compat.deactivate')}</ActionButton>}
              <ActionButton onClick={() => void plan('rollback', pack)}>{t('compat.rollback')}</ActionButton>
              <ActionButton tone="danger" onClick={() => void plan('quarantine', pack)}>{t('compat.quarantine')}</ActionButton>
              <ActionButton tone="danger" onClick={() => void plan('remove', pack)}>{t('compat.remove')}</ActionButton>
            </div></td>
          </tr>
        })}</tbody></table></div>}
    </section>

    <div className="compat-lower">
      <section className="compat-panel"><header className="panel-header"><h2>{t('compat.capabilitySources')}</h2></header>
        {!data?.capabilities.length ? <p className="compat-empty">{t('compat.noCapabilities')}</p>
          : <div className="event-rows">{data.capabilities.map((capability) =>
            <article className="event-row" key={capability.id}><time>{capability.risk}</time>
              <strong>{capability.id}</strong><StatusBadge value={capability.enabled ? 'ACTIVE' : 'SUPPRESSED'} />
              <span>{capability.sourcePack}</span><p>{capability.suppressionReason || capability.kind}</p>
            </article>)}</div>}
      </section>
      <section className="compat-panel"><header className="panel-header"><h2>{t('compat.conflicts')}</h2></header>
        {!issues.length ? <p className="compat-empty">{t('compat.noConflicts')}</p>
          : <ul>{issues.map((issue) => <li key={issue}>{issue}</li>)}</ul>}
      </section>
      <section className="compat-panel"><header className="panel-header"><h2>{t('compat.trace')}</h2></header>
        {!data?.trace.length ? <p className="compat-empty">{t('compat.noTrace')}</p>
          : <div className="event-rows">{data.trace.map((entry, index) =>
            <article className="event-row" key={`${entry.at}-${index}`}><time>{entry.at}</time>
              <strong>{entry.capability}</strong><StatusBadge value={entry.decision} /><span>{entry.packCoordinate ?? '—'}</span>
              <p>{entry.reason}</p></article>)}</div>}
      </section>
    </div>
  </div>
}
