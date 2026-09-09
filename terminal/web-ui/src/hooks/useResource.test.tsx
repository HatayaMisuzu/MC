import { renderHook, waitFor } from '@testing-library/react'
import { expect, it } from 'vitest'
import { useResource } from './useResource'

it('does not expose old data under a new resource key or let its request overwrite the new data', async () => {
  let resolveOld!: (value: string) => void
  let resolveNew!: (value: string) => void
  const oldRequest = new Promise<string>((resolve) => { resolveOld = resolve })
  const newRequest = new Promise<string>((resolve) => { resolveNew = resolve })
  const { result, rerender } = renderHook(
    ({ selected }) => useResource(
      () => selected === 'old' ? oldRequest : newRequest, [selected], selected,
    ),
    { initialProps: { selected: 'old' } },
  )
  resolveOld('old-result')
  await waitFor(() => expect(result.current.data).toBe('old-result'))
  expect(result.current.dataKey).toBe('old')

  rerender({ selected: 'new' })
  expect(result.current.data).toBeNull()
  expect(result.current.dataKey).toBeNull()
  expect(result.current.loading).toBe(true)

  resolveNew('new-result')
  await waitFor(() => expect(result.current.data).toBe('new-result'))
  expect(result.current.dataKey).toBe('new')
  expect(result.current.data).toBe('new-result')
})
