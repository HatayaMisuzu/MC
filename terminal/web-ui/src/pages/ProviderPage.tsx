import { FlaskConical, Power, Save } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'

interface ProviderState { mode: string; baseUrl?: string; model?: string; apiKeyEnv?: string; timeoutSeconds?: number }
interface ProviderTest { success: boolean; latencyMillis: number; model: string; message: string }

export function ProviderPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const status = useResource(() => selectedId ? api<ProviderState>(`/api/provider/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<ProviderState>({ mode: 'rules' }), [selectedId])
  const [form, setForm] = useState({ baseUrl: 'https://api.openai.com/v1', model: '', apiKeyEnv: 'MC_COMPANION_API_KEY', timeoutSeconds: 15 })
  const [test, setTest] = useState<ProviderTest | null>(null)
  const [testing, setTesting] = useState(false)
  useEffect(() => {
    if (status.data?.mode === 'openai-compatible') setForm({
      baseUrl: status.data.baseUrl ?? '', model: status.data.model ?? '',
      apiKeyEnv: status.data.apiKeyEnv ?? 'MC_COMPANION_API_KEY',
      timeoutSeconds: status.data.timeoutSeconds ?? 15,
    })
  }, [status.data])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('provider.empty')}</EmptyState>
  const configure = () => requestPlan('provider', { instanceId: selectedId, action: 'configure', ...form })
  const testProvider = async () => {
    setTesting(true)
    try { setTest(await post<ProviderTest>('/api/provider/test', { instanceId: selectedId })) }
    finally { setTesting(false) }
  }
  return <div className="page">
    <PageHeader title={t('provider.title')} description={t('provider.description')} />
    <section className="provider-mode"><div><span>{t('provider.currentMode')}</span>
      <StatusBadge value={status.data?.mode === 'rules' ? 'SAFE_IDLE' : 'ONLINE'} />
      <strong>{status.data?.mode ?? 'rules'}</strong></div><p>{t('provider.keyBoundary')}</p></section>
    <div className="provider-layout"><form className="form-panel" onSubmit={(event) => { event.preventDefault(); void configure() }}>
      <h2>OpenAI-compatible</h2>
      <label className="field"><span>Base URL</span><input type="url" required value={form.baseUrl} onChange={(event) => setForm((value) => ({ ...value, baseUrl: event.target.value }))} /></label>
      <label className="field"><span>{t('provider.model')}</span><input required value={form.model} onChange={(event) => setForm((value) => ({ ...value, model: event.target.value }))} placeholder={t('provider.modelPlaceholder')} /></label>
      <label className="field"><span>{t('provider.keyEnv')}</span><input required pattern="[A-Za-z_][A-Za-z0-9_]*" value={form.apiKeyEnv} onChange={(event) => setForm((value) => ({ ...value, apiKeyEnv: event.target.value }))} /></label>
      <label className="field"><span>{t('provider.timeout')}</span><input type="number" min="1" max="300" value={form.timeoutSeconds} onChange={(event) => setForm((value) => ({ ...value, timeoutSeconds: Number(event.target.value) }))} /></label>
      <div className="form-actions"><ActionButton tone="primary" icon={<Save size={16} />} type="submit">{t('provider.reviewPlan')}</ActionButton>
        <ActionButton icon={<FlaskConical size={16} />} type="button" loading={testing} onClick={() => void testProvider()}>{t('provider.test')}</ActionButton></div>
    </form><section className="provider-test"><h2>{t('provider.test')}</h2>{test ? <dl className="detail-list">
      <div><dt>{t('provider.result')}</dt><dd><StatusBadge value={test.success ? 'PASS' : 'FAILED'} /></dd></div>
      <div><dt>{t('provider.latency')}</dt><dd>{test.latencyMillis} ms</dd></div>
      <div><dt>{t('provider.model')}</dt><dd>{test.model}</dd></div>
      <div><dt>{t('provider.detail')}</dt><dd>{test.message}</dd></div>
    </dl> : <p>{t('provider.testBody')}</p>}
      <ActionButton tone="danger" icon={<Power size={16} />} onClick={() => void requestPlan('provider', { instanceId: selectedId, action: 'disable' })}>{t('provider.rules')}</ActionButton>
    </section></div>
  </div>
}
