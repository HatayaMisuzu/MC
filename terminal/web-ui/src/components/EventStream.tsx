import { useTerminal } from '../context/TerminalContext'
import { StatusBadge } from './StatusBadge'

export function EventStream() {
  const { events } = useTerminal()
  return (
    <section className="event-stream">
      <header><h2>实时事件</h2><span>{events.length} 条</span></header>
      <div className="event-rows">
        {!events.length && <div className="event-empty">等待安装、Runtime、会话或行为事件…</div>}
        {events.map((event, index) => (
          <div className="event-row" key={`${event.at}-${index}`}>
            <time>{event.at ? new Date(event.at).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'}</time>
            <strong>{event.type}</strong>
            <span>{event.operationId?.slice(0, 8) ?? 'system'}</span>
            <StatusBadge value={event.state ?? (event.error ? 'FAILED' : 'ONLINE')} />
            <p>{event.message ?? event.error ?? '事件通道正常'}</p>
          </div>
        ))}
      </div>
    </section>
  )
}
