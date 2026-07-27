import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { enUS, zhCN, type TranslationKey } from './resources'

export type Locale = 'zh-CN' | 'en-US'

interface I18nValue {
  locale: Locale
  setLocale: (locale: Locale) => void
  t: (key: TranslationKey, values?: Record<string, string | number>) => string
}

const defaultLocale = (): Locale => {
  const saved = localStorage.getItem('mcac.locale')
  if (saved === 'zh-CN' || saved === 'en-US') return saved
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

const translate = (locale: Locale, key: TranslationKey, values: Record<string, string | number> = {}) => {
  const source = locale === 'zh-CN' ? zhCN : enUS
  const template = source[key]
  if (template === undefined) {
    if (import.meta.env.DEV || import.meta.env.MODE === 'test') throw new Error(`Missing translation: ${key}`)
    return enUS[key] ?? 'Translation unavailable'
  }
  return Object.entries(values).reduce(
    (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
    template,
  )
}

const Context = createContext<I18nValue>({
  locale: 'en-US',
  setLocale: () => undefined,
  t: (key, values) => translate('en-US', key, values),
})

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [locale, updateLocale] = useState<Locale>(defaultLocale)
  const setLocale = useCallback((next: Locale) => {
    localStorage.setItem('mcac.locale', next)
    document.documentElement.lang = next
    updateLocale(next)
  }, [])
  const value = useMemo<I18nValue>(() => ({
    locale,
    setLocale,
    t: (key, values) => translate(locale, key, values),
  }), [locale, setLocale])
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export const useI18n = () => useContext(Context)
