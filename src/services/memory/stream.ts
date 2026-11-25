import { ApiError, streamMessage } from '../api/client.js'
import { addUsage, mergeUsage } from '../api/usage.js'
import type {
  AssistantBlockParam,
  CreateMessageRequest,
  MessageStreamEvent,
  MessageUsage,
  OutputBlockStart,
  ToolResultBlockParam,
  ToolUseBlock,
} from '../api/types.js'
import { executeMemoryTool, memoryTools } from './context.js'
import { useMemorySettings } from './settings.js'
import type { MemoryNotice } from './types.js'

/** Keep raw blocks (including signed thinking and server results) for tool round trips. */
export class ToolRound {
  blocks = new Map<number, OutputBlockStart>()
  json = new Map<number, string>()
  usage?: MessageUsage
  stopReason?: string
  stopped = new Set<number>()

  apply(event: MessageStreamEvent) {
    if (event.type === 'message_start') {
      this.usage = mergeUsage(this.usage, event.message.usage)
    }
    if (event.type === 'message_delta') {
      this.usage = mergeUsage(this.usage, event.usage)
      this.stopReason = event.delta.stop_reason
    }
    if (event.type === 'content_block_start') {
      this.blocks.set(event.index, structuredClone(event.content_block))
    }
    if (event.type === 'content_block_stop') {
      this.stopped.add(event.index)
    }
    if (event.type !== 'content_block_delta') {
      return
    }
    const block = this.blocks.get(event.index)
    const delta = event.delta
    if (block?.type === 'tool_use' && delta.type === 'input_json_delta') {
      const input = (this.json.get(event.index) ?? '') + delta.partial_json
      if (input.length > 32768) {
        throw new ApiError('protocol', 'Memory tool input is too large.')
      }
      this.json.set(event.index, input)
    } else if (block?.type === 'text' && delta.type === 'text_delta') {
      block.text += delta.text
    } else if (block?.type === 'text' && delta.type === 'citations_delta') {
      block.citations = [...(block.citations ?? []), delta.citation]
    } else if (block?.type === 'thinking' && delta.type === 'thinking_delta') {
      block.thinking += delta.thinking
    } else if (block?.type === 'thinking' && delta.type === 'signature_delta') {
      block.signature = (block.signature ?? '') + delta.signature
    }
  }

  content(): AssistantBlockParam[] {
    return [...this.blocks.entries()]
      .sort(([a], [b]) => a - b)
      .map(([index, block]) => {
        if (block.type === 'tool_use') {
          if (!this.stopped.has(index)) {
            throw new ApiError('protocol', 'Incomplete memory tool call.')
          }
          const partial = this.json.get(index)
          if (partial !== undefined) {
            try {
              return { ...block, input: JSON.parse(partial) as unknown }
            } catch {
              throw new ApiError('protocol', 'Invalid memory tool JSON.')
            }
          }
        }
        return block
      })
  }
}

const MAX_TOOL_ROUNDS = 5
const MAX_TOOL_CALLS = 12

export async function* streamMemoryMessage(
  apiKey: string,
  original: CreateMessageRequest,
  signal: AbortSignal,
  options: {
    sessionId: string
    previousUsage?: MessageUsage
    onNotice: (notice: MemoryNotice) => void
  }
): AsyncGenerator<MessageStreamEvent> {
  const messages = [...original.messages]
  let total = options.previousUsage
  let calls = 0
  for (let step = 0; ; step++) {
    if (signal.aborted) {
      throw signal.reason
    }
    const tools = memoryTools()
    const body = { ...original, messages, tools: [...original.tools, ...tools] }
    const round = new ToolRound()
    for await (const event of streamMessage(apiKey, body, signal)) {
      round.apply(event)
      if (event.type === 'message_start') {
        yield { ...event, message: { ...event.message, usage: addUsage(total, round.usage) } }
      } else if (event.type === 'message_delta') {
        yield { ...event, usage: addUsage(total, round.usage) }
      } else if (event.type !== 'message_stop') {
        yield event
      }
    }
    total = addUsage(total, round.usage)
    if (round.stopReason !== 'tool_use') {
      yield { type: 'message_stop' }
      return
    }
    const content = round.content()
    const pending = content.filter((block): block is ToolUseBlock => block.type === 'tool_use')
    if (!pending.length) {
      throw new ApiError('protocol', 'The API stopped for a tool call without providing one.')
    }
    if (step >= MAX_TOOL_ROUNDS || calls + pending.length > MAX_TOOL_CALLS) {
      throw new ApiError(
        'protocol',
        'Memory tool limit reached. Please send another message to continue.'
      )
    }
    const ids = new Set(pending.map((tool) => tool.id))
    if (ids.size !== pending.length) {
      throw new ApiError('protocol', 'Duplicate memory tool IDs.')
    }
    const results: ToolResultBlockParam[] = []
    for (const tool of pending) {
      if (signal.aborted) {
        throw signal.reason
      }
      // The executor rechecks the current permission, so toggles take effect mid-generation.
      const allowed =
        tools.some((item) => item.name === tool.name) && useMemorySettings.getState().enabled
      const result = allowed
        ? await executeMemoryTool(tool.name, tool.input, options.sessionId, signal)
        : {
            content: JSON.stringify({ success: false, error: 'Tool is unavailable.' }),
            is_error: true,
          }
      calls++
      if ('notice' in result && result.notice) {
        options.onNotice(result.notice)
      }
      results.push({
        type: 'tool_result',
        tool_use_id: tool.id,
        content: result.content,
        ...(result.is_error ? { is_error: true } : {}),
      })
    }
    messages.push({ role: 'assistant', content }, { role: 'user', content: results })
  }
}
