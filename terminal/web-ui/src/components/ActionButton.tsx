import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { useI18n } from '../i18n/I18nContext'

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  tone?: 'primary' | 'secondary' | 'danger' | 'ghost'
  loading?: boolean
  icon?: ReactNode
}

export function ActionButton({ tone = 'secondary', loading, icon, children, ...props }: Props) {
  const { t } = useI18n()
  return <button className={`button button--${tone}`} disabled={loading || props.disabled} {...props}>
    {loading ? <span className="spinner" aria-hidden /> : icon}
    <span>{loading ? t('common.processing') : children}</span>
  </button>
}
