import { ArchiveRestore, Download, RefreshCcw, ShieldAlert, Trash2, Wrench } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'

export function InstallPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const [rollbackId, setRollbackId] = useState('')
  const rollbackPoints = useResource(() => selectedId ? api<string[]>(`/api/install/rollback-points?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<string[]>([]), [selectedId])
  if (!selected) return <EmptyState title="请选择实例">安装、修复和回滚必须绑定到明确的 gameDir。</EmptyState>
  const plan = (action: string, extra: Record<string, unknown> = {}) => requestPlan('install', { instanceId: selectedId, action, ...extra })
  return (
    <div className="page">
      <PageHeader title="安装管理" description="所有改动先显示计划，写入失败由事务自动恢复；不会删除未知 Mod、存档或配置。" />
      <section className="install-summary">
        <div><span>当前实例</span><strong>{selected.name}</strong></div><div><span>兼容性</span><StatusBadge value={selected.compatible ? 'PASS' : 'BLOCKED'} /></div>
        <div><span>安装状态</span><StatusBadge value={selected.installed ? 'PASS' : 'WAITING'} /></div><div><span>gameDir</span><strong>{selected.gameDir}</strong></div>
      </section>
      {!selected.compatible && <div className="warning-callout"><ShieldAlert size={18} /><div><strong>目标不兼容</strong><span>该实例不会被写入。Forge/NeoForge 仅在已支持的精确版本执行安装，并保持 LOCAL_ONLY。</span></div></div>}
      <div className="action-sections">
        <section><h2>安装与更新</h2><p>解析精确 Loader/Minecraft metadata，备份现有受管文件并校验 SHA-256。</p><div><ActionButton tone="primary" disabled={!selected.compatible} icon={<Download size={16} />} onClick={() => void plan('install')}>生成安装计划</ActionButton><ActionButton disabled={!selected.compatible} icon={<RefreshCcw size={16} />} onClick={() => void plan('update')}>检查并更新</ActionButton><ActionButton disabled={!selected.compatible} icon={<Wrench size={16} />} onClick={() => void plan('repair')}>验证并修复</ActionButton></div></section>
        <section><h2>回滚</h2><p>选择由安装事务创建的回滚点；后端会验证路径、清单与备份边界。</p><label className="field"><span>可用回滚点</span><select value={rollbackId} onChange={(event) => setRollbackId(event.target.value)}><option value="">请选择</option>{(rollbackPoints.data ?? []).map((point) => <option key={point} value={point}>{point}</option>)}</select></label><ActionButton tone="danger" disabled={!rollbackId.trim()} icon={<ArchiveRestore size={16} />} onClick={() => void plan('rollback', { rollbackId: rollbackId.trim() })}>审阅回滚计划</ActionButton></section>
        <section><h2>安全卸载</h2><p>只删除安装清单管理且哈希匹配的 Companion 文件；检测到外部修改时拒绝覆盖。</p><ActionButton tone="danger" disabled={!selected.installed} icon={<Trash2 size={16} />} onClick={() => void plan('uninstall')}>审阅卸载计划</ActionButton></section>
      </div>
    </div>
  )
}
