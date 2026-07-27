import { ArchiveRestore, Download, RefreshCcw, ShieldAlert, Trash2, Wrench } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'

export function InstallPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const [rollbackId, setRollbackId] = useState('')
  const rollbackPoints = useResource(() => selectedId
    ? api<string[]>(`/api/install/rollback-points?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve<string[]>([]), [selectedId])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('install.empty')}</EmptyState>
  const plan = (action: string, extra: Record<string, unknown> = {}) =>
    requestPlan('install', { instanceId: selectedId, action, ...extra })
  return <div className="page">
    <PageHeader title={t('install.title')} description={t('install.description')} />
    <section className="install-summary">
      <div><span>{t('shell.currentInstance')}</span><strong>{selected.name}</strong></div>
      <div><span>{t('instances.compatibility')}</span><StatusBadge value={selected.compatible ? 'PASS' : 'BLOCKED'} /></div>
      <div><span>{t('install.state')}</span><StatusBadge value={selected.installed ? 'PASS' : 'WAITING'} /></div>
      <div><span>gameDir</span><strong>{selected.gameDir}</strong></div>
    </section>
    {!selected.compatible && <div className="warning-callout"><ShieldAlert size={18} /><div>
      <strong>{t('install.incompatible')}</strong><span>{t('install.incompatibleBody')}</span></div></div>}
    <div className="action-sections">
      <section><h2>{t('install.installUpdate')}</h2><p>{t('install.installUpdateBody')}</p><div>
        <ActionButton tone="primary" disabled={!selected.compatible} icon={<Download size={16} />} onClick={() => void plan('install')}>{t('install.plan')}</ActionButton>
        <ActionButton disabled={!selected.compatible} icon={<RefreshCcw size={16} />} onClick={() => void plan('update')}>{t('install.update')}</ActionButton>
        <ActionButton disabled={!selected.compatible} icon={<Wrench size={16} />} onClick={() => void plan('repair')}>{t('install.repair')}</ActionButton>
      </div></section>
      <section><h2>{t('compat.rollback')}</h2><p>{t('install.rollbackBody')}</p>
        <label className="field"><span>{t('install.rollbackPoint')}</span><select value={rollbackId} onChange={(event) => setRollbackId(event.target.value)}>
          <option value="">{t('common.select')}</option>{(rollbackPoints.data ?? []).map((point) => <option key={point} value={point}>{point}</option>)}
        </select></label><ActionButton tone="danger" disabled={!rollbackId.trim()} icon={<ArchiveRestore size={16} />}
          onClick={() => void plan('rollback', { rollbackId: rollbackId.trim() })}>{t('install.reviewRollback')}</ActionButton>
      </section>
      <section><h2>{t('install.uninstall')}</h2><p>{t('install.uninstallBody')}</p><div>
        <ActionButton tone="danger" disabled={!selected.installed} icon={<Trash2 size={16} />} onClick={() => void plan('uninstall')}>{t('install.uninstallKeep')}</ActionButton>
        <ActionButton tone="danger" disabled={!selected.installed} icon={<Trash2 size={16} />} onClick={() => void plan('uninstall-delete-data')}>{t('install.uninstallDelete')}</ActionButton>
      </div></section>
    </div>
  </div>
}
