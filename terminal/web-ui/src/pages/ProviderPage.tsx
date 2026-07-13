import { FlaskConical, Power, Save } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'

interface ProviderState { mode: string; baseUrl?: string; model?: string; apiKeyEnv?: string; timeoutSeconds?: number }
interface ProviderTest { success: boolean; latencyMillis: number; model: string; message: string }

export function ProviderPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const status = useResource(() => selectedId ? api<ProviderState>(`/api/provider/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<ProviderState>({ mode: 'rules' }), [selectedId])
  const [form, setForm] = useState({ baseUrl: 'https://api.openai.com/v1', model: '', apiKeyEnv: 'MC_COMPANION_API_KEY', timeoutSeconds: 15 })
  const [test, setTest] = useState<ProviderTest | null>(null)
  const [testing, setTesting] = useState(false)
  useEffect(() => { if (status.data?.mode === 'openai-compatible') setForm({ baseUrl: status.data.baseUrl ?? '', model: status.data.model ?? '', apiKeyEnv: status.data.apiKeyEnv ?? 'MC_COMPANION_API_KEY', timeoutSeconds: status.data.timeoutSeconds ?? 15 }) }, [status.data])
  if (!selected) return <EmptyState title="请选择实例">Provider 配置属于独立 Runtime Profile。</EmptyState>
  const configure = () => requestPlan('provider', { instanceId: selectedId, action: 'configure', ...form })
  const testProvider = async () => { setTesting(true); try { setTest(await post<ProviderTest>('/api/provider/test', { instanceId: selectedId })) } finally { setTesting(false) } }
  return (
    <div className="page">
      <PageHeader title="Provider 配置" description="默认 rules 无需联网；OpenAI-compatible Key 只从环境变量或 Windows Credential Manager 获取。" />
      <section className="provider-mode"><div><span>当前模式</span><StatusBadge value={status.data?.mode === 'rules' ? 'SAFE_IDLE' : 'ONLINE'} /><strong>{status.data?.mode ?? 'rules'}</strong></div><p>API Key 永远不会由页面读取、显示或写入 provider.json。</p></section>
      <div className="provider-layout"><form className="form-panel" onSubmit={(event) => { event.preventDefault(); void configure() }}><h2>OpenAI-compatible</h2><label className="field"><span>Base URL</span><input type="url" required value={form.baseUrl} onChange={(event) => setForm((value) => ({ ...value, baseUrl: event.target.value }))} /></label><label className="field"><span>模型</span><input required value={form.model} onChange={(event) => setForm((value) => ({ ...value, model: event.target.value }))} placeholder="例如 gpt-4.1-mini" /></label><label className="field"><span>API Key 环境变量</span><input required pattern="[A-Za-z_][A-Za-z0-9_]*" value={form.apiKeyEnv} onChange={(event) => setForm((value) => ({ ...value, apiKeyEnv: event.target.value }))} /></label><label className="field"><span>超时（秒）</span><input type="number" min="1" max="300" value={form.timeoutSeconds} onChange={(event) => setForm((value) => ({ ...value, timeoutSeconds: Number(event.target.value) }))} /></label><div className="form-actions"><ActionButton tone="primary" icon={<Save size={16} />} type="submit">审阅配置计划</ActionButton><ActionButton icon={<FlaskConical size={16} />} type="button" loading={testing} onClick={() => void testProvider()}>测试连接</ActionButton></div></form><section className="provider-test"><h2>连接测试</h2>{test ? <dl className="detail-list"><div><dt>结果</dt><dd><StatusBadge value={test.success ? 'PASS' : 'FAILED'} /></dd></div><div><dt>延迟</dt><dd>{test.latencyMillis} ms</dd></div><div><dt>模型</dt><dd>{test.model}</dd></div><div><dt>说明</dt><dd>{test.message}</dd></div></dl> : <p>测试只验证 Provider URL、凭据来源和模型，不要求启动 Minecraft。</p>}<ActionButton tone="danger" icon={<Power size={16} />} onClick={() => void requestPlan('provider', { instanceId: selectedId, action: 'disable' })}>切换回 rules</ActionButton></section></div>
    </div>
  )
}
