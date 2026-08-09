import { renderHook, waitFor } from '@testing-library/react'
import { expect, it } from 'vitest'
import { useResource } from './useResource'

it('does not let an older request overwrite the newest dependency state', async () => {
  let resolveOld!: (value: string) => void
  let resolveNew!: (value: string) => void
  const oldRequest = new Promise<string>((resolve) => { resolveOld = resolve })
  const newRequest = new Promise<string>((resolve) => { resolveNew = resolve })
  const { result, rerender } = renderHook(
    ({ selected }) => useResource(() => selected === 'old' ? oldRequest : newRequest, [selected]),
    { initialProps: { selected: 'old' } },
  )
  rerender({ selected: 'new' })
  resolveNew('new-result')
  await waitFor(() => expect(result.current.data).toBe('new-result'))
  resolveOld('old-result')
  await Promise.resolve()
  expect(result.current.data).toBe('new-result')
})
