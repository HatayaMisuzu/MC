import { Power, Save, Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'

interface SearchState { mode: string; endpoint?: string; tokenEnv?: string; timeoutSeconds?: number; allowedDomains?: string[]; deniedDomains?: string[] }
interface SearchTest { success: boolean; networkAttempted: boolean; latencyMillis: number; code: string; message: string }
interface SearchSourceView { sourceId: string; title: string; url: string; domain: string; publisher?: string; snippet?: string; trustLevel: string; contentType: string }
interface SearchSessionView { searchId: string; companionId: string; query: string; expiresAt: number; sources: SearchSourceView[] }
interface SearchSessions { trustBoundary: string; sessions: SearchSessionView[] }
const domains = (value: string) => value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean)
const safeSourceUrl = (value: string): string | null => {
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'https:' && !parsed.username && !parsed.password ? parsed.href : null
  } catch {
    return null
  }
}

export function SearchPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const status = useResource(() => selectedId ? api<SearchState>(`/api/search/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<SearchState>({ mode: 'disabled' }), [selectedId])
  const sessions = useResource(() => selectedId ? api<SearchSessions>(`/api/search/sessions?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<SearchSessions>({ trustBoundary: 'UNTRUSTED_EXTERNAL_CONTENT', sessions: [] }), [selectedId])
  const [form, setForm] = useState({ endpoint: 'https://search-provider.invalid/v1/search', tokenEnv: 'MC_COMPANION_SEARCH_TOKEN', timeoutSeconds: 15, allowed: '', denied: '' })
  const [test, setTest] = useState<SearchTest | null>(null)
  const [testing, setTesting] = useState(false)
  useEffect(() => {
    if (status.data?.mode === 'http') setForm({
      endpoint: status.data.endpoint ?? '', tokenEnv: status.data.tokenEnv ?? 'MC_COMPANION_SEARCH_TOKEN',
      timeoutSeconds: status.data.timeoutSeconds ?? 15,
      allowed: (status.data.allowedDomains ?? []).join('\n'),
      denied: (status.data.deniedDomains ?? []).join('\n'),
    })
  }, [status.data])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('search.empty')}</EmptyState>
  const configure = () => requestPlan('search', {
    instanceId: selectedId, action: 'configure', endpoint: form.endpoint, tokenEnv: form.tokenEnv,
    timeoutSeconds: form.timeoutSeconds, allowedDomains: domains(form.allowed), deniedDomains: domains(form.denied),
  })
  const testProvider = async () => {
    setTesting(true)
    try { setTest(await post<SearchTest>('/api/search/test', { instanceId: selectedId })) }
    finally { setTesting(false) }
  }
  return <div className="page">
    <PageHeader title={t('search.title')} description={t('search.description')} />
    <section className="provider-mode"><div><span>{t('search.currentState')}</span>
      <StatusBadge value={status.data?.mode === 'http' ? 'ONLINE' : 'DISABLED'} />
      <strong>{status.data?.mode ?? 'disabled'}</strong></div><p>{t('search.privacyBoundary')}</p></section>
    <div className="provider-layout"><form className="form-panel" onSubmit={(event) => { event.preventDefault(); void configure() }}>
      <h2>{t('search.provider')}</h2>
      <label className="field"><span>{t('search.endpoint')}</span><input type="url" required value={form.endpoint} onChange={(event) => setForm((value) => ({ ...value, endpoint: event.target.value }))} /></label>
      <label className="field"><span>{t('search.tokenEnv')}</span><input required pattern="[A-Za-z_][A-Za-z0-9_]*" value={form.tokenEnv} onChange={(event) => setForm((value) => ({ ...value, tokenEnv: event.target.value }))} /></label>
      <label className="field"><span>{t('provider.timeout')}</span><input type="number" min="1" max="30" value={form.timeoutSeconds} onChange={(event) => setForm((value) => ({ ...value, timeoutSeconds: Number(event.target.value) }))} /></label>
      <label className="field"><span>{t('search.allowed')}</span><textarea value={form.allowed} onChange={(event) => setForm((value) => ({ ...value, allowed: event.target.value }))} /></label>
      <label className="field"><span>{t('search.denied')}</span><textarea value={form.denied} onChange={(event) => setForm((value) => ({ ...value, denied: event.target.value }))} /></label>
      <div className="form-actions"><ActionButton tone="primary" icon={<Save size={16} />} type="submit">{t('search.reviewPlan')}</ActionButton>
        <ActionButton icon={<Search size={16} />} type="button" loading={testing} onClick={() => void testProvider()}>{t('provider.test')}</ActionButton></div>
    </form><section className="provider-test"><h2>{t('search.doctor')}</h2>{test ? <dl className="detail-list">
      <div><dt>{t('provider.result')}</dt><dd><StatusBadge value={test.success ? 'PASS' : 'FAILED'} /></dd></div>
      <div><dt>{t('search.code')}</dt><dd>{test.code}</dd></div>
      <div><dt>{t('search.network')}</dt><dd>{test.networkAttempted ? t('search.networkSent') : t('search.networkNotSent')}</dd></div>
      <div><dt>{t('provider.latency')}</dt><dd>{test.latencyMillis} ms</dd></div>
      <div><dt>{t('provider.detail')}</dt><dd>{test.message}</dd></div>
    </dl> : <p>{t('search.testBody')}</p>}<p>{t('search.openBoundary')}</p>
      <ActionButton tone="danger" icon={<Power size={16} />} onClick={() => void requestPlan('search', { instanceId: selectedId, action: 'disable' })}>{t('search.disable')}</ActionButton>
      <div className="privacy-note"><Search size={16} /> {t('search.noMemory')}</div>
    </section></div>
    <section className="search-sources"><header className="panel-header"><h2>{t('search.sources')}</h2>
      <span>{t('search.sessionCount', { count: sessions.data?.sessions?.length ?? 0 })}</span></header>
      <p>{t('search.sourcesBoundary')}</p><div className="search-source-list">
        {(sessions.data?.sessions ?? []).flatMap((session) => session.sources.map((source) => {
          const safeUrl = safeSourceUrl(source.url)
          return <article className="search-source" key={`${session.searchId}-${source.sourceId}`}>
            <div><strong>{source.title}</strong><span>{source.domain} · {session.companionId}</span></div>
            <p>{source.snippet || t('search.noSnippet')}</p>
            {safeUrl
              ? <a href={safeUrl} target="_blank" rel="noopener noreferrer">{t('search.openSource')}</a>
              : <span>{source.url}</span>}
          </article>
        }))}
      </div>{!sessions.loading && !sessions.data?.sessions?.length ? <p>{t('search.noSessions')}</p> : null}
    </section>
  </div>
}
