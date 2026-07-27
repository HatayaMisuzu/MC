import { useTerminal } from '../context/TerminalContext'
import { useI18n } from '../i18n/I18nContext'
import { StatusBadge } from './StatusBadge'

export function EventStream() {
  const { events } = useTerminal()
  const { locale, t } = useI18n()
  return <section className="event-stream">
    <header><h2>{t('events.title')}</h2><span>{t('events.count', { count: events.length })}</span></header>
    <div className="event-rows">
      {!events.length && <div className="event-empty">{t('events.waiting')}</div>}
      {events.map((event, index) => <div className="event-row" key={`${event.at}-${index}`}>
        <time>{event.at ? new Date(event.at).toLocaleTimeString(locale, { hour12: false }) : '--:--:--'}</time>
        <strong>{event.type}</strong><span>{event.operationId?.slice(0, 8) ?? 'system'}</span>
        <StatusBadge value={event.state ?? (event.error ? 'FAILED' : 'ONLINE')} />
        <p>{event.message ?? event.error ?? t('events.channelHealthy')}</p>
      </div>)}
    </div>
  </section>
}
