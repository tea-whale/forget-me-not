import type { MessageUsage } from './types.js'

export const emptyUsage = (): MessageUsage => ({
  input_tokens: 0,
  cache_creation_input_tokens: 0,
  cache_read_input_tokens: 0,
  output_tokens: 0,
})

/** SSE fields are cumulative within one request, not increments. */
export const mergeUsage = (
  previous: MessageUsage | undefined,
  patch: Partial<MessageUsage> | undefined
): MessageUsage | undefined => {
  if (!patch) { return previous }
  return {
    ...emptyUsage(), ...previous, ...patch,
    ...((previous?.output_tokens_details || patch.output_tokens_details) ? {
      output_tokens_details: { ...previous?.output_tokens_details, ...patch.output_tokens_details },
    } : {}),
    ...((previous?.server_tool_use || patch.server_tool_use) ? {
      server_tool_use: { ...previous?.server_tool_use, ...patch.server_tool_use },
    } : {}),
  }
}

/** Separate API requests are summed, including tool continuations. */
export const addUsage = (
  a: MessageUsage | undefined,
  b: MessageUsage | undefined
): MessageUsage | undefined => {
  if (!a) {
    return b
  }
  if (!b) {
    return a
  }
  const usage: MessageUsage = {
    input_tokens: a.input_tokens + b.input_tokens,
    cache_creation_input_tokens: a.cache_creation_input_tokens + b.cache_creation_input_tokens,
    cache_read_input_tokens: a.cache_read_input_tokens + b.cache_read_input_tokens,
    output_tokens: a.output_tokens + b.output_tokens,
  }
  if (a.output_tokens_details || b.output_tokens_details) {
    usage.output_tokens_details = {
      thinking_tokens:
        (a.output_tokens_details?.thinking_tokens ?? 0) +
        (b.output_tokens_details?.thinking_tokens ?? 0),
      safety_tokens:
        (a.output_tokens_details?.safety_tokens ?? 0) +
        (b.output_tokens_details?.safety_tokens ?? 0),
    }
  }
  if (a.server_tool_use || b.server_tool_use) {
    usage.server_tool_use = {
      web_search_requests:
        (a.server_tool_use?.web_search_requests ?? 0) +
        (b.server_tool_use?.web_search_requests ?? 0),
      tool_search: (a.server_tool_use?.tool_search ?? 0) + (b.server_tool_use?.tool_search ?? 0),
      web_fetch_requests: (a.server_tool_use?.web_fetch_requests ?? 0) + (b.server_tool_use?.web_fetch_requests ?? 0),
      tool_fetch: (a.server_tool_use?.tool_fetch ?? 0) + (b.server_tool_use?.tool_fetch ?? 0),
      tool_image_search: (a.server_tool_use?.tool_image_search ?? 0) + (b.server_tool_use?.tool_image_search ?? 0),
    }
  }
  return usage
}

export const usageTotals = (usage: MessageUsage) => {
  const input =
    usage.input_tokens + usage.cache_creation_input_tokens + usage.cache_read_input_tokens
  return {
    input,
    total: input + usage.output_tokens,
    cacheRate: input ? usage.cache_read_input_tokens / input : 0,
  }
}
