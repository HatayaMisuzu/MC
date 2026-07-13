import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it('always renders textual state in addition to color', () => {
    render(<StatusBadge value="LOCAL_ONLY" />)
    expect(screen.getByText('LOCAL_ONLY')).toHaveClass('status--warning')
  })
})
