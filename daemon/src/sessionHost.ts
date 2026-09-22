import { randomUUID } from 'node:crypto'
import { query, type Options, type PermissionResult, type SDKMessage, type SDKUserMessage } from '@anthropic-ai/claude-agent-sdk'

import { PromptQueue } from './promptQueue.js'
import {
  approvalRequiredFrame,
  approvalResolvedFrame,
  assistantDeltaFrame,
  assistantTextFrom,
  classifyRisk,
  describeToolCall,
  errorFrame,
  explainToolCall,
  logFrame,
  sessionEndedFrame,
  statusFrame,
  statusFromSessionState,
  toolUsesFrom,
  type AgentStatus,
} from './protocol.js'

export interface SessionHostOptions {
  readonly sessionId: string
  readonly cwd: string
  readonly permissionMode: NonNullable<Options['permissionMode']>
  /** Called for every frame bound for the watch. */
  readonly emit: (frame: string) => void
}

interface PendingApproval {
  readonly id: string
  readonly resolve: (result: PermissionResult) => void
}

/**
 * Hosts one resumed Claude Code session and turns it into the wire protocol.
 *
 * The important part is [canUseTool]: the SDK calls it and *waits*, so the
 * promise it returns is literally the tool call held open until someone taps
 * a watch. Everything else here is bookkeeping around that one fact.
 */
export class SessionHost {
  private readonly prompts = new PromptQueue<SDKUserMessage>()
  private readonly pending = new Map<string, PendingApproval>()
  private readonly abort = new AbortController()

  private status: AgentStatus = 'idle'
  private running = false
  private stopped = false

  constructor(private readonly options: SessionHostOptions) {}

  /** Current state, replayed whenever a watch reconnects and asks. */
  snapshot(): void {
    this.options.emit(statusFrame(this.status))
    for (const approval of this.pendingApprovalFrames) this.options.emit(approval)
  }

  private readonly pendingApprovalFrames: string[] = []

  start(): void {
    if (this.running || this.stopped) return
    this.running = true
    void this.run()
  }

  private async run(): Promise<void> {
    try {
      const stream = query({
        prompt: this.prompts,
        options: {
          resume: this.options.sessionId,
          cwd: this.options.cwd,
          permissionMode: this.options.permissionMode,
          abortController: this.abort,
          canUseTool: (toolName, input) => this.requestApproval(toolName, input),
        },
      })

      for await (const message of stream) {
        this.handle(message)
        if (this.stopped) break
      }
      this.options.emit(sessionEndedFrame('session stream ended'))
    } catch (error) {
      if (this.stopped) return
      const message = error instanceof Error ? error.message : String(error)
      this.setStatus('error')
      this.options.emit(errorFrame(message))
    } finally {
      this.running = false
    }
  }

  private handle(message: SDKMessage): void {
    // The SDK's own turn state is authoritative; nothing here should be
    // inferring "is it busy" from message traffic.
    if (message.type === 'system' && 'subtype' in message && message.subtype === 'session_state_changed') {
      const state = (message as { state: 'idle' | 'running' | 'requires_action' }).state
      // A pending approval outranks 'running': the SDK keeps running while it
      // waits on us, but the watch should say what it is actually waiting for.
      if (this.pending.size > 0) this.setStatus('awaiting_approval')
      else this.setStatus(statusFromSessionState(state))
      return
    }

    if (message.type === 'assistant') {
      for (const use of toolUsesFrom(message)) {
        this.options.emit(logFrame('tool', describeToolCall(use.name, use.input)))
      }
      const text = assistantTextFrom(message)
      if (text !== null) {
        this.options.emit(assistantDeltaFrame(text.messageId, text.text, true))
      }
      return
    }

    if (message.type === 'result') {
      if (this.pending.size === 0) this.setStatus('idle')
      return
    }
  }

  /**
   * The approval gate.
   *
   * Returns a promise the SDK awaits, so the tool call is genuinely paused —
   * not optimistically allowed and cancelled later.
   */
  private requestApproval(toolName: string, input: Record<string, unknown>): Promise<PermissionResult> {
    const id = randomUUID()
    const command = describeToolCall(toolName, input)
    const frame = approvalRequiredFrame({
      id,
      sessionId: this.options.sessionId,
      toolName,
      command,
      explanation: explainToolCall(toolName, input),
      risk: classifyRisk(toolName, input),
    })

    return new Promise<PermissionResult>((resolve) => {
      this.pending.set(id, { id, resolve })
      this.pendingApprovalFrames.push(frame)
      this.setStatus('awaiting_approval')
      this.options.emit(frame)

      // If the session is torn down while a prompt is open, deny rather than
      // leave the SDK waiting forever.
      this.abort.signal.addEventListener(
        'abort',
        () => {
          if (this.pending.delete(id)) {
            this.clearFrame(id)
            resolve({ behavior: 'deny', message: 'The watch disconnected before answering.' })
          }
        },
        { once: true },
      )
    })
  }

  /** Answers a pending approval. Returns false if it was already resolved. */
  resolveApproval(requestId: string, approved: boolean): boolean {
    const approval = this.pending.get(requestId)
    if (approval === undefined) return false
    this.pending.delete(requestId)
    this.clearFrame(requestId)

    approval.resolve(
      approved
        ? { behavior: 'allow' }
        : // deny requires a message; it is shown to the model, so say why.
          { behavior: 'deny', message: 'Denied from the watch.' },
    )

    this.options.emit(approvalResolvedFrame(requestId, approved))
    this.setStatus(approved ? 'executing' : 'thinking')
    return true
  }

  sendPrompt(text: string): void {
    this.start()
    this.prompts.push({
      type: 'user',
      message: { role: 'user', content: text },
      parent_tool_use_id: null,
    })
    this.options.emit(logFrame('user', text))
    this.setStatus('thinking')
  }

  interrupt(): void {
    // Denying every open prompt is the honest interpretation of "stop": the
    // tool calls are what is in flight.
    for (const [id] of this.pending) this.resolveApproval(id, false)
    this.setStatus('idle')
  }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    this.prompts.close()
    this.abort.abort()
  }

  private clearFrame(requestId: string): void {
    const index = this.pendingApprovalFrames.findIndex((frame) => frame.includes(`"${requestId}"`))
    if (index >= 0) this.pendingApprovalFrames.splice(index, 1)
  }

  private setStatus(next: AgentStatus): void {
    if (this.status === next) return
    this.status = next
    this.options.emit(statusFrame(next))
  }

  get currentStatus(): AgentStatus {
    return this.status
  }
}
