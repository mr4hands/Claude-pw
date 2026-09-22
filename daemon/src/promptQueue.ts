/**
 * A pushable async iterable, so a session started earlier can take new turns.
 *
 * `query()` accepts an AsyncIterable of user messages for exactly this: the
 * queue stays open for the life of the session, and each prompt dictated on
 * the watch becomes the next turn.
 */
export class PromptQueue<T> implements AsyncIterable<T> {
  private readonly buffered: T[] = []
  private readonly waiting: Array<(result: IteratorResult<T>) => void> = []
  private closed = false

  push(value: T): void {
    if (this.closed) return
    const waiter = this.waiting.shift()
    if (waiter === undefined) this.buffered.push(value)
    else waiter({ value, done: false })
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    while (this.waiting.length > 0) {
      this.waiting.shift()?.({ value: undefined as never, done: true })
    }
  }

  get pending(): number {
    return this.buffered.length
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: (): Promise<IteratorResult<T>> => {
        const buffered = this.buffered.shift()
        if (buffered !== undefined) return Promise.resolve({ value: buffered, done: false })
        if (this.closed) return Promise.resolve({ value: undefined as never, done: true })
        return new Promise((resolve) => this.waiting.push(resolve))
      },
      return: (): Promise<IteratorResult<T>> => {
        this.close()
        return Promise.resolve({ value: undefined as never, done: true })
      },
    }
  }
}
