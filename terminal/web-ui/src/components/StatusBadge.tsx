const tones: Record<string, string> = {
  CONNECTED: 'success',
  ONLINE: 'success',
  PASS: 'success',
  SUCCEEDED: 'success',
  FULL: 'success',
  LOCAL_ONLY: 'warning',
  WARNING: 'warning',
  WAITING: 'neutral',
  SAFE_IDLE: 'neutral',
  RUNNING: 'accent',
  QUEUED: 'accent',
  FAILED: 'danger',
  BLOCKED: 'danger',
  STOPPED: 'neutral',
  NOT_CONFIGURED: 'neutral',
}

export function StatusBadge({ value }: { value?: string | boolean }) {
  const text = typeof value === 'boolean' ? (value ? '是' : '否') : (value ?? '未配置')
  return <span className={`status status--${tones[text] ?? 'neutral'}`}>{text}</span>
}
