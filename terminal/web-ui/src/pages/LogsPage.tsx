import { Archive, FileClock, RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, streamLogSnapshots } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'

interface LogResult {
  kind: string
  available: boolean
  lines: string[]
}

export function LogsPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const [kind, setKind] = useState<'minecraft' | 'runtime'>('minecraft')
  const [liveLogs, setLiveLogs] = useState<LogResult | null>(null)
  const logs = useResource(
    () =>
      selectedId
        ? api<LogResult>(
            `/api/logs/tail?instanceId=${encodeURIComponent(selectedId)}&kind=${kind}`,
          )
        : Promise.resolve({ kind, available: false, lines: [] }),
    [selectedId, kind],
  )

  useEffect(() => {
    setLiveLogs(null)
    if (!selectedId) return
    const controller = new AbortController()
    void streamLogSnapshots(selectedId, kind, setLiveLogs, controller.signal)
    return () => controller.abort()
  }, [selectedId, kind])

  if (!selected)
    return <EmptyState title="请选择实例">日志和支持包必须绑定到明确实例。</EmptyState>

  const display = liveLogs ?? logs.data
  return (
    <div className="page logs-page">
      <PageHeader
        title="日志与支持"
        description="实时查看允许范围内的日志，并生成经过秘密与隐私扫描的支持包。"
        actions={
          <>
            <div className="segmented">
              <button
                className={kind === 'minecraft' ? 'active' : ''}
                onClick={() => setKind('minecraft')}
              >
                Minecraft
              </button>
              <button
                className={kind === 'runtime' ? 'active' : ''}
                onClick={() => setKind('runtime')}
              >
                Runtime
              </button>
            </div>
            <ActionButton
              icon={<RefreshCw size={15} />}
              loading={logs.loading}
              onClick={() => void logs.refresh()}
            >
              刷新
            </ActionButton>
          </>
        }
      />
      <section className="log-viewer">
        <header>
          <div>
            <FileClock size={17} />
            <strong>{kind === 'minecraft' ? 'latest.log' : 'runtime-process.log'}</strong>
          </div>
          <span>{display?.available ? `${display.lines.length} 行 · 实时` : '文件不存在'}</span>
        </header>
        <pre>
          {display?.lines.join('\n') ||
            '尚无可显示日志。启动游戏或 Runtime 后这里会显示真实输出。'}
        </pre>
      </section>
      <section className="support-panel">
        <div>
          <Archive size={24} />
          <div>
            <h2>脱敏支持包</h2>
            <p>
              仅收集允许列表内容；生成后再次扫描 API Key、Token、Authorization、IP、绝对路径、UUID、主机名和账号痕迹。
            </p>
          </div>
        </div>
        <ActionButton
          tone="primary"
          onClick={() => void requestPlan('support-bundle', { instanceId: selectedId })}
        >
          生成支持包
        </ActionButton>
      </section>
    </div>
  )
}
