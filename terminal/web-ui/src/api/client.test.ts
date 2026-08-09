import { expect, it, vi } from 'vitest'
import { streamEvents } from './client'

it('isolates malformed SSE events and continues with the next valid event', async () => {
  const encoder = new TextEncoder()
  const controller = new AbortController()
  const stream = new ReadableStream<Uint8Array>({
    start(streamController) {
      streamController.enqueue(encoder.encode('data: {broken}\n\ndata: {"type":"STATUS"}\n\n'))
    },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream, { status: 200 })))
  const received: string[] = []
  await streamEvents((event) => {
    received.push(event.type)
    if (event.type === 'STATUS') controller.abort()
  }, controller.signal)
  expect(received).toEqual(['SSE_EVENT_ERROR', 'STATUS'])
  vi.unstubAllGlobals()
})

it('stops after the bounded number of cleanly closed SSE connections', async () => {
  vi.useFakeTimers()
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(
    new ReadableStream<Uint8Array>({ start(streamController) {
      streamController.enqueue(new TextEncoder().encode('data: {"type":'))
      streamController.close()
    } }),
    { status: 200 },
  )))
  vi.stubGlobal('fetch', fetchMock)
  try {
    const received: string[] = []
    const pending = streamEvents((event) => received.push(event.type), new AbortController().signal)
    const assertion = expect(pending).rejects.toMatchObject({ code: 'SSE_RETRY_EXHAUSTED' })
    await vi.runAllTimersAsync()
    await assertion
    expect(fetchMock).toHaveBeenCalledTimes(6)
    expect(received).toEqual(Array(6).fill('SSE_EVENT_ERROR'))
  } finally {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  }
})
