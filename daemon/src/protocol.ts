/**
 * Translation between the Agent SDK and the wire protocol in
 * ../../docs/protocol.md.
 *
 * Everything here is pure, because this is the layer where a mistake is
 * invisible until it reaches a wrist: a mislabelled risk level or a truncated
 * command is a user approving something they did not read.
 */

export type RiskLevel = 'low' | 'medium' | 'high'
export type AgentStatus = 'idle' | 'thinking' | 'executing' | 'awaiting_approval' | 'error'

/** Tools that only read. Approving these is close to free. */
const READ_ONLY_TOOLS = new Set([
  'Read',
  'Grep',
  'Glob',
  'WebFetch',
  'WebSearch',
  'TodoWrite',
  'NotebookRead',
  'ListMcpResources',
])

/** Tools that change the working tree. */
const MUTATING_TOOLS = new Set(['Write', 'Edit', 'MultiEdit', 'NotebookEdit'])

/**
 * Shell fragments worth waking someone up for.
 *
 * Deliberately conservative and deliberately not clever: this decides how hard
 * the watch buzzes, not whether the command runs. Anything not matched is
 * still `medium`, never `low`.
 */
const DANGEROUS_SHELL = [
  /\brm\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*[rf]/, //     rm -rf, rm -fr, rm -r -f
  /\bsudo\b/,
  /\bdd\s+if=/,
  /\bmkfs\b/,
  /\bchmod\s+(-[a-zA-Z]+\s+)*777\b/,
  /\bcurl\b[^|;]*\|\s*(sudo\s+)?(ba)?sh\b/, //    curl … | sh
  /\bwget\b[^|;]*\|\s*(sudo\s+)?(ba)?sh\b/,
  /\bgit\s+push\b[^;]*--force\b/,
  /\bgit\s+push\b[^;]*\s-f\b/,
  /\bgit\s+reset\s+--hard\b/,
  /\bnpm\s+publish\b/,
  /\b(shutdown|reboot|halt)\b/,
  />\s*\/dev\/(sd|nvme|disk)/,
  /\bkubectl\s+delete\b/,
  /\bterraform\s+(apply|destroy)\b/,
  /\bDROP\s+(TABLE|DATABASE)\b/i,
]

export function classifyRisk(toolName: string, input: Record<string, unknown>): RiskLevel {
  if (READ_ONLY_TOOLS.has(toolName)) return 'low'

  if (toolName === 'Bash' || toolName === 'BashOutput') {
    const command = typeof input['command'] === 'string' ? input['command'] : ''
    if (DANGEROUS_SHELL.some((pattern) => pattern.test(command))) return 'high'
    return 'medium'
  }

  if (MUTATING_TOOLS.has(toolName)) return 'medium'

  // Unknown tools, including every MCP tool, are medium: an unrecognised name
  // is a reason for more caution, not less.
  return 'medium'
}

/**
 * The single line the watch shows and speaks.
 *
 * Shown verbatim, never summarised — an approximation of a shell command is
 * worse than no command at all.
 */
export function describeToolCall(toolName: string, input: Record<string, unknown>): string {
  const str = (key: string): string | null =>
    typeof input[key] === 'string' ? (input[key] as string) : null

  switch (toolName) {
    case 'Bash':
    case 'BashOutput':
      return str('command') ?? toolName
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit':
      return `${toolName} ${str('file_path') ?? str('notebook_path') ?? ''}`.trim()
    case 'Read':
      return `Read ${str('file_path') ?? ''}`.trim()
    case 'WebFetch':
      return `WebFetch ${str('url') ?? ''}`.trim()
    case 'Glob':
    case 'Grep':
      return `${toolName} ${str('pattern') ?? ''}`.trim()
    default: {
      const compact = JSON.stringify(input)
      return compact.length > 160 ? `${toolName} ${compact.slice(0, 157)}...` : `${toolName} ${compact}`
    }
  }
}

/** Longer context for the approval screen, when the tool offers any. */
export function explainToolCall(toolName: string, input: Record<string, unknown>): string | null {
  const description = input['description']
  if (typeof description === 'string' && description.trim() !== '') return description.trim()
  if (toolName === 'Bash') return null
  return null
}

/** The SDK's authoritative turn state, mapped to what the ring shows. */
export function statusFromSessionState(state: 'idle' | 'running' | 'requires_action'): AgentStatus {
  switch (state) {
    case 'idle':
      return 'idle'
    case 'running':
      return 'thinking'
    case 'requires_action':
      return 'awaiting_approval'
  }
}

export interface AssistantText {
  readonly messageId: string
  readonly text: string
}

/**
 * Pulls displayable text out of an assistant message.
 *
 * Thinking blocks are deliberately excluded: they are long, they are not a
 * summary, and having a watch read them aloud would be actively unhelpful.
 */
export function assistantTextFrom(message: unknown): AssistantText | null {
  if (typeof message !== 'object' || message === null) return null
  const sdk = message as Record<string, unknown>
  if (sdk['type'] !== 'assistant') return null

  const inner = sdk['message']
  if (typeof inner !== 'object' || inner === null) return null
  const content = (inner as Record<string, unknown>)['content']
  if (!Array.isArray(content)) return null

  const parts: string[] = []
  for (const block of content) {
    if (typeof block !== 'object' || block === null) continue
    const b = block as Record<string, unknown>
    if (b['type'] === 'text' && typeof b['text'] === 'string') parts.push(b['text'])
  }
  if (parts.length === 0) return null

  const id = typeof (inner as Record<string, unknown>)['id'] === 'string'
    ? ((inner as Record<string, unknown>)['id'] as string)
    : String(sdk['uuid'] ?? 'assistant')

  return { messageId: id, text: parts.join('') }
}

/** Tool-use blocks in an assistant message, for the activity log. */
export function toolUsesFrom(message: unknown): Array<{ name: string; input: Record<string, unknown> }> {
  if (typeof message !== 'object' || message === null) return []
  const sdk = message as Record<string, unknown>
  if (sdk['type'] !== 'assistant') return []

  const inner = sdk['message']
  if (typeof inner !== 'object' || inner === null) return []
  const content = (inner as Record<string, unknown>)['content']
  if (!Array.isArray(content)) return []

  const out: Array<{ name: string; input: Record<string, unknown> }> = []
  for (const block of content) {
    if (typeof block !== 'object' || block === null) continue
    const b = block as Record<string, unknown>
    if (b['type'] !== 'tool_use' || typeof b['name'] !== 'string') continue
    out.push({
      name: b['name'],
      input: (typeof b['input'] === 'object' && b['input'] !== null
        ? (b['input'] as Record<string, unknown>)
        : {}),
    })
  }
  return out
}

// ------------------------------------------------------------- frames ---

export function statusFrame(status: AgentStatus): string {
  return JSON.stringify({ type: 'status', status })
}

export function approvalRequiredFrame(request: {
  id: string
  sessionId: string
  toolName: string
  command: string
  explanation: string | null
  risk: RiskLevel
}): string {
  return JSON.stringify({
    type: 'approval_required',
    session_id: request.sessionId,
    request: {
      id: request.id,
      tool: request.toolName,
      command: request.command,
      explanation: request.explanation,
      risk: request.risk,
    },
  })
}

export function approvalResolvedFrame(requestId: string, approved: boolean): string {
  return JSON.stringify({ type: 'approval_resolved', request_id: requestId, approved })
}

export function assistantDeltaFrame(messageId: string, text: string, final: boolean): string {
  return JSON.stringify({ type: 'assistant_delta', message_id: messageId, text, final })
}

export function logFrame(role: 'user' | 'assistant' | 'tool' | 'system', text: string): string {
  return JSON.stringify({
    type: 'log',
    entry: { id: `log-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, role, text, ts: String(Date.now()) },
  })
}

export function errorFrame(message: string): string {
  return JSON.stringify({ type: 'error', message })
}

export function sessionEndedFrame(reason: string): string {
  return JSON.stringify({ type: 'session_ended', reason })
}

// ------------------------------------------------- inbound from watch ---

export type ClientCommand =
  | { kind: 'approval'; requestId: string; approved: boolean }
  | { kind: 'prompt'; text: string }
  | { kind: 'interrupt' }
  | { kind: 'snapshot' }

/** Parses a frame from the watch. Unknown or malformed frames return null. */
export function parseClientFrame(raw: string): ClientCommand | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const frame = parsed as Record<string, unknown>

  switch (frame['type']) {
    case 'approval_response': {
      const requestId = frame['request_id']
      if (typeof requestId !== 'string' || requestId === '') return null
      return { kind: 'approval', requestId, approved: frame['approved'] === true }
    }
    case 'user_prompt': {
      const text = frame['text']
      if (typeof text !== 'string' || text.trim() === '') return null
      return { kind: 'prompt', text: text.trim() }
    }
    case 'interrupt':
      return { kind: 'interrupt' }
    case 'snapshot_request':
      return { kind: 'snapshot' }
    default:
      return null
  }
}
