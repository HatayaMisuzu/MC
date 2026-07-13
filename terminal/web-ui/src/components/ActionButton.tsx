import type { ButtonHTMLAttributes, ReactNode } from 'react'

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  tone?: 'primary' | 'secondary' | 'danger' | 'ghost'
  loading?: boolean
  icon?: ReactNode
}

export function ActionButton({ tone = 'secondary', loading, icon, children, ...props }: Props) {
  return (
    <button className={`button button--${tone}`} disabled={loading || props.disabled} {...props}>
      {loading ? <span className="spinner" aria-hidden /> : icon}
      <span>{loading ? '处理中…' : children}</span>
    </button>
  )
}
