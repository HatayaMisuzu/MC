import { Power, Save, Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'

interface SearchState {
  mode: string
  endpoint?: string
  tokenEnv?: string
  timeoutSeconds?: number
  allowedDomains?: string[]
  deniedDomains?: string[]
}
interface SearchTest { success: boolean; networkAttempted: boolean; latencyMillis: number; code: string; message: string }
interface SearchSourceView {
  sourceId: string; title: string; url: string; domain: string; publisher?: string; snippet?: string
  trustLevel: string; contentType: string
}
interface SearchSessionView {
  searchId: string; companionId: string; query: string; expiresAt: number; sources: SearchSourceView[]
}
interface SearchSessions { trustBoundary: string; sessions: SearchSessionView[] }

const domains = (value: string) => value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean)

export function SearchPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const status = useResource(() => selectedId
    ? api<SearchState>(`/api/search/status?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve<SearchState>({ mode: 'disabled' }), [selectedId])
  const sessions = useResource(() => selectedId
    ? api<SearchSessions>(`/api/search/sessions?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve<SearchSessions>({ trustBoundary: 'UNTRUSTED_EXTERNAL_CONTENT', sessions: [] }), [selectedId])
  const [form, setForm] = useState({ endpoint: 'https://search-provider.invalid/v1/search', tokenEnv: 'MC_COMPANION_SEARCH_TOKEN', timeoutSeconds: 15, allowed: '', denied: '' })
  const [test, setTest] = useState<SearchTest | null>(null)
  const [testing, setTesting] = useState(false)
  useEffect(() => {
    if (status.data?.mode === 'http') setForm({
      endpoint: status.data.endpoint ?? '', tokenEnv: status.data.tokenEnv ?? 'MC_COMPANION_SEARCH_TOKEN',
      timeoutSeconds: status.data.timeoutSeconds ?? 15,
      allowed: (status.data.allowedDomains ?? []).join('\n'), denied: (status.data.deniedDomains ?? []).join('\n'),
    })
  }, [status.data])
  if (!selected) return <EmptyState title="请选择实例">Search 配置属于独立 Runtime Profile。</EmptyState>
  const configure = () => requestPlan('search', {
    instanceId: selectedId, action: 'configure', endpoint: form.endpoint, tokenEnv: form.tokenEnv,
    timeoutSeconds: form.timeoutSeconds, allowedDomains: domains(form.allowed), deniedDomains: domains(form.denied),
  })
  const testProvider = async () => {
    setTesting(true)
    try { setTest(await post<SearchTest>('/api/search/test', { instanceId: selectedId })) } finally { setTesting(false) }
  }
  return <div className="page">
    <PageHeader title="Search 与隐私" description="搜索默认关闭；凭据只从环境变量读取，结果始终作为不可信外部内容处理。" />
    <section className="provider-mode"><div><span>当前状态</span><StatusBadge value={status.data?.mode === 'http' ? 'ONLINE' : 'DISABLED'} /><strong>{status.data?.mode ?? 'disabled'}</strong></div><p>不会读取 Cookie、浏览器登录态、聊天全文、坐标或本地路径。</p></section>
    <div className="provider-layout"><form className="form-panel" onSubmit={(event) => { event.preventDefault(); void configure() }}>
      <h2>受限 Search Provider</h2>
      <label className="field"><span>查询 Endpoint</span><input type="url" required value={form.endpoint} onChange={(event) => setForm((value) => ({ ...value, endpoint: event.target.value }))} /></label>
      <label className="field"><span>Token 环境变量</span><input required pattern="[A-Za-z_][A-Za-z0-9_]*" value={form.tokenEnv} onChange={(event) => setForm((value) => ({ ...value, tokenEnv: event.target.value }))} /></label>
      <label className="field"><span>超时（秒）</span><input type="number" min="1" max="30" value={form.timeoutSeconds} onChange={(event) => setForm((value) => ({ ...value, timeoutSeconds: Number(event.target.value) }))} /></label>
      <label className="field"><span>允许域名（逗号或换行分隔；留空表示不额外收窄）</span><textarea value={form.allowed} onChange={(event) => setForm((value) => ({ ...value, allowed: event.target.value }))} /></label>
      <label className="field"><span>拒绝域名</span><textarea value={form.denied} onChange={(event) => setForm((value) => ({ ...value, denied: event.target.value }))} /></label>
      <div className="form-actions"><ActionButton tone="primary" icon={<Save size={16} />} type="submit">审阅 Search 配置计划</ActionButton><ActionButton icon={<Search size={16} />} type="button" loading={testing} onClick={() => void testProvider()}>测试连接</ActionButton></div>
    </form><section className="provider-test"><h2>Search Doctor</h2>{test ? <dl className="detail-list"><div><dt>结果</dt><dd><StatusBadge value={test.success ? 'PASS' : 'FAILED'} /></dd></div><div><dt>代码</dt><dd>{test.code}</dd></div><div><dt>网络请求</dt><dd>{test.networkAttempted ? '已发送有界探针' : '未发送'}</dd></div><div><dt>延迟</dt><dd>{test.latencyMillis} ms</dd></div><div><dt>说明</dt><dd>{test.message}</dd></div></dl> : <p>连接测试使用固定、无私人内容的查询，最多请求一个结果；不返回响应正文或凭据。</p>}<p>Provider 只能接收经过隐私过滤的有界查询。打开来源时仅允许本次会话返回的 sourceId，并拒绝重定向、脚本、表单和非公开 HTTPS 来源。</p><ActionButton tone="danger" icon={<Power size={16} />} onClick={() => void requestPlan('search', { instanceId: selectedId, action: 'disable' })}>关闭 Search</ActionButton><div className="privacy-note"><Search size={16} /> 搜索内容不会写入 verified World Memory。</div></section></div>
    <section className="search-sources">
      <header className="panel-header"><h2>最近的受限来源</h2><span>{sessions.data?.sessions?.length ?? 0} 个会话</span></header>
      <p>以下链接来自外部 Search Provider，始终是不可信内容。只有点击链接时才会由浏览器打开；页面正文不会写入 verified World Memory。</p>
      <div className="search-source-list">{(sessions.data?.sessions ?? []).flatMap((session) =>
        session.sources.map((source) => <article className="search-source" key={`${session.searchId}-${source.sourceId}`}>
          <div><strong>{source.title}</strong><span>{source.domain} · {session.companionId}</span></div>
          <p>{source.snippet || '无摘要'}</p>
          <a href={source.url} target="_blank" rel="noopener noreferrer">打开外部来源</a>
        </article>))}</div>
      {!sessions.loading && !(sessions.data?.sessions?.length) ? <p>当前没有未过期的 Search 会话。</p> : null}
    </section>
  </div>
}
