import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ActionButton } from './ActionButton'

describe('ActionButton', () => {
  it('exposes disabled loading state without retaining stale success text', () => {
    render(<ActionButton loading>启动 Runtime</ActionButton>)
    const button = screen.getByRole('button', { name: '处理中…' })
    expect(button).toBeDisabled()
    expect(screen.queryByText('启动 Runtime')).not.toBeInTheDocument()
  })
})
