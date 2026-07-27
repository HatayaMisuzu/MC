import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { I18nProvider, useI18n } from './I18nContext'
import { enUS, zhCN } from './resources'

function Probe() {
  const { locale, setLocale, t } = useI18n()
  const [value, setValue] = useState('kept')
  return <div>
    <span>{t('nav.compatibility')}</span>
    <span data-testid="locale">{locale}</span>
    <input aria-label="state" value={value} onChange={(event) => setValue(event.target.value)} />
    <button onClick={() => setLocale(locale === 'en-US' ? 'zh-CN' : 'en-US')}>switch</button>
  </div>
}

describe('I18nProvider', () => {
  it('keeps both resource catalogs complete and fails fast for a missing key', () => {
    expect(Object.keys(zhCN).sort()).toEqual(Object.keys(enUS).sort())
    function MissingKey() {
      const { t } = useI18n()
      return <span>{t('missing.key' as never)}</span>
    }
    expect(() => render(<I18nProvider><MissingKey /></I18nProvider>))
      .toThrow('Missing translation: missing.key')
  })

  it('switches languages without reload or component state loss and persists the choice', () => {
    localStorage.setItem('mcac.locale', 'en-US')
    render(<I18nProvider><Probe /></I18nProvider>)
    expect(screen.getByText('Compatibility')).toBeVisible()
    fireEvent.change(screen.getByLabelText('state'), { target: { value: 'still here' } })
    fireEvent.click(screen.getByRole('button', { name: 'switch' }))
    expect(screen.getByText('兼容层')).toBeVisible()
    expect(screen.getByDisplayValue('still here')).toBeVisible()
    expect(localStorage.getItem('mcac.locale')).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
  })
})
