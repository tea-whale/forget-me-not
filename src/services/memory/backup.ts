import { getMemoryDB, mutateMemory } from './store.js'
import type { MemoryInput, MemoryRecord } from './types.js'

/** Memory backups deliberately contain no API keys, settings or chat content. */
export const exportMemories = async () => {
  const memories = await (await getMemoryDB()).getAll('memories')
  const blob = new Blob(
    [JSON.stringify({ format: 'chat-demo-memory', version: 1, memories }, null, 2)],
    { type: 'application/json' }
  )
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `deepseek-memories-${new Date().toISOString().slice(0, 10)}.json`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export const parseMemoryBackup = (text: string): MemoryInput[] => {
  const value = JSON.parse(text) as { format?: string; version?: number; memories?: unknown[] }
  if (
    value?.format !== 'chat-demo-memory' ||
    value.version !== 1 ||
    !Array.isArray(value.memories) ||
    value.memories.length > 1000
  ) {
    throw new Error('Invalid memory backup (maximum 1000 entries).')
  }
  return value.memories.map((item) => {
    if (!item || typeof item !== 'object') {
      throw new Error('Invalid memory entry.')
    }
    const memory = item as MemoryRecord
    if (
      !['core', 'rag'].includes(memory.kind) ||
      typeof memory.title !== 'string' ||
      !memory.title.trim() ||
      memory.title.length > 120 ||
      typeof memory.content !== 'string' ||
      !memory.content.trim() ||
      memory.content.length > 4000 ||
      typeof memory.enabled !== 'boolean'
    ) {
      throw new Error('Invalid memory entry.')
    }
    return {
      kind: memory.kind,
      title: memory.title.trim(),
      content: memory.content.trim(),
      enabled: memory.enabled,
    }
  })
}

export const importMemories = async (file: File): Promise<number> => {
  if (file.size > 5 * 1024 * 1024) {
    throw new Error('Memory backup exceeds 5 MB.')
  }
  // Validate the entire file before making any changes.
  const inputs = parseMemoryBackup(await file.text())
  const db = await getMemoryDB()
  const existing = await db.getAll('memories')
  const fingerprint = (item: MemoryInput) =>
    JSON.stringify([item.kind, item.title, item.content, item.enabled])
  const known = new Set(existing.map(fingerprint))
  let count = 0
  for (const input of inputs) {
    if (known.has(fingerprint(input))) {
      continue
    }
    try {
      await mutateMemory('create', input, {}, { actor: 'user' })
    } catch (error) {
      throw new Error(
        `Imported ${count} memories before stopping: ${error instanceof Error ? error.message : 'storage error'}`
      )
    }
    known.add(fingerprint(input))
    count++
  }
  return count
}
