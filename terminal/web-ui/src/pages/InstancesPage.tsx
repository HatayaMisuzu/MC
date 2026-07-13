import { RefreshCw } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { InstanceTable } from '../components/InstanceTable'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { Launcher } from '../types'

export function InstancesPage() {
  const { instances, selectedId, select, refresh } = useTerminal()
  const launchers = useResource(() => api<Launcher[]>('/api/launchers'), [])
  return (
    <div className="page">
      <PageHeader title="启动器与实例" description="自动发现 PCL2/HMCL、真实 gameDir、Java 与 Loader 兼容性。" actions={<ActionButton icon={<RefreshCw size={15} />} loading={launchers.loading} onClick={() => { void launchers.refresh(); void refresh() }}>重新扫描</ActionButton>} />
      {launchers.error && <div className="inline-error">{launchers.error}</div>}
      <section className="launcher-strip">
        {(launchers.data ?? []).map((launcher) => (
          <article key={launcher.id}>
            <div><strong>{launcher.type}</strong><span>{launcher.version}</span></div>
            <StatusBadge value={launcher.confidence} />
            <p>{launcher.executable}</p>
          </article>
        ))}
        {!launchers.loading && !(launchers.data?.length) && <p>未发现 PCL2 或 HMCL。</p>}
      </section>
      <section className="main-panel">
        <header className="panel-header"><h2>实例清单</h2><span>安装只允许 HIGH 置信度</span></header>
        <InstanceTable instances={instances} selectedId={selectedId} onSelect={select} />
      </section>
      <section className="evidence-panel">
        <h2>当前实例证据</h2>
        {instances.filter((value) => value.id === selectedId).map((instance) => (
          <dl className="detail-list two-column" key={instance.id}>
            <div><dt>gameDir</dt><dd>{instance.gameDir}</dd></div><div><dt>隔离方式</dt><dd>{instance.isolation}</dd></div>
            <div><dt>Java</dt><dd>{instance.javaConfigured || `需要 Java ${instance.javaRequired}`}</dd></div><div><dt>兼容性</dt><dd><StatusBadge value={instance.compatible ? 'PASS' : 'BLOCKED'} /></dd></div>
          </dl>
        ))}
      </section>
    </div>
  )
}
