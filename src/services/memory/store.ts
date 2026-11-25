import { openDB, type DBSchema } from 'idb'
import { create } from 'zustand'
import { createId } from '../../utils/id.js'
import type { MemoryChange, MemoryInput, MemoryRecord } from './types.js'

export const CORE_CHAR_LIMIT = 6000
export const MEMORY_CHAR_LIMIT = 4000

interface MemoryDB extends DBSchema {
  memories: { key: string; value: MemoryRecord }
  changes: { key: string; value: MemoryChange }
  embeddings: {
    key: string
    value: { key: string; revision: string; provider: string; vector: number[] }
  }
}

let connection: ReturnType<typeof openDB<MemoryDB>> | undefined
export const getMemoryDB = () =>
  (connection ??= openDB<MemoryDB>('chat-demo-memory', 1, {
    upgrade(db) {
      db.createObjectStore('memories', { keyPath: 'id' })
      db.createObjectStore('changes', { keyPath: 'id' })
      db.createObjectStore('embeddings', { keyPath: 'key' })
    },
  }).catch((error: unknown) => {
    connection = undefined
    throw error
  }))

export const useMemories = create(() => ({
  records: [] as MemoryRecord[],
  changes: [] as MemoryChange[],
  ready: false,
  error: false,
}))

let loading: Promise<void> | undefined
export const loadMemories = (): Promise<void> => {
  if (useMemories.getState().ready) {
    return Promise.resolve()
  }
  return (loading ??= (async () => {
    try {
      const db = await getMemoryDB()
      const tx = db.transaction(['memories', 'changes'])
      const [records, changes] = await Promise.all([
        tx.objectStore('memories').getAll(),
        tx.objectStore('changes').getAll(),
        tx.done,
      ])
      useMemories.setState({
        records,
        changes: changes.sort((a, b) => b.createdAt - a.createdAt),
        ready: true,
        error: false,
      })
    } catch (error) {
      useMemories.setState({ error: true })
      throw error
    } finally {
      loading = undefined
    }
  })())
}

const validate = (input: MemoryInput) => {
  if (
    !['core', 'rag'].includes(input.kind) ||
    typeof input.enabled !== 'boolean' ||
    typeof input.title !== 'string' ||
    typeof input.content !== 'string' ||
    !input.title.trim() ||
    input.title.trim().length > 120 ||
    !input.content.trim() ||
    input.content.trim().length > MEMORY_CHAR_LIMIT
  ) {
    throw new Error(
      'Memory needs a title (1–120 characters), content (1–4000 characters), a kind and enabled flag.'
    )
  }
}

type Actor = { actor: 'user' | 'assistant'; sessionId?: string }

/** Mutations, revision checks, core budget and audit writes share one transaction. */
export const mutateMemory = async (
  operation: 'create' | 'update' | 'delete',
  input: MemoryInput | undefined,
  target: { id?: string; revision?: string },
  actor: Actor,
  check?: () => void
): Promise<MemoryChange> => {
  check?.()
  if (operation === 'create' && target.id) {
    throw new Error('A new memory must not include an existing ID.')
  }
  if (operation !== 'delete') {
    if (!input) {
      throw new Error('Missing memory content.')
    }
    validate(input)
  }
  const db = await getMemoryDB()
  const tx = db.transaction(['memories', 'changes', 'embeddings'], 'readwrite')
  // Observe aborted transactions immediately, including synchronous validation failures.
  const done = tx.done.catch(() => {})
  try {
    const store = tx.objectStore('memories')
    const before = target.id ? await store.get(target.id) : undefined
    if (operation !== 'create' && (!before || before.revision !== target.revision)) {
      throw new Error('Memory changed or was deleted. Search again before editing.')
    }
    const now = Date.now()
    const after: MemoryRecord | undefined =
      operation === 'delete' || !input
        ? undefined
        : {
            ...input,
            title: input.title.trim(),
            content: input.content.trim(),
            id: before?.id ?? createId(),
            revision: createId(),
            source: actor.actor,
            createdAt: before?.createdAt ?? now,
            updatedAt: now,
          }
    if (after?.kind === 'core' && after.enabled) {
      const records = await store.getAll()
      const size = records
        .filter((item) => item.id !== after.id && item.kind === 'core' && item.enabled)
        .reduce((sum, item) => sum + item.title.length + item.content.length, 0)
      if (size + after.title.length + after.content.length > CORE_CHAR_LIMIT) {
        throw new Error(
          `Enabled core memories exceed ${CORE_CHAR_LIMIT} characters. Move some memories to RAG first.`
        )
      }
    }
    check?.()
    const memory = after ?? before
    if (!memory) {
      throw new Error('Memory not found.')
    }
    const change: MemoryChange = {
      ...actor,
      id: createId(),
      operation,
      title: memory.title,
      createdAt: now,
      ...(before ? { before } : {}),
      ...(after ? { after } : {}),
    }
    if (after) {
      await store.put(after)
    } else {
      await store.delete(memory.id)
    }
    await tx.objectStore('embeddings').delete(memory.id)
    await tx.objectStore('changes').put(change)
    await tx.done
    // Re-read to include writes committed concurrently by another tab.
    const [records, changes] = await Promise.all([db.getAll('memories'), db.getAll('changes')])
    useMemories.setState({
      records,
      changes: changes.sort((a, b) => b.createdAt - a.createdAt),
      ready: true,
      error: false,
    })
    return change
  } catch (error) {
    try {
      tx.abort()
    } catch {
      /* Already committed or aborted. */
    }
    await done
    throw error
  }
}

export const undoMemoryChange = async (change: MemoryChange) => {
  if (change.operation === 'undo') {
    throw new Error('This change is already an undo.')
  }
  const current = change.after
  // Use the normal mutation transaction so revisions and core budgets still apply.
  if (current) {
    return mutateMemory(
      change.before ? 'update' : 'delete',
      change.before,
      { id: current.id, revision: current.revision },
      { actor: 'user' }
    )
  }
  if (!change.before) {
    throw new Error('No memory to restore.')
  }
  // Deleted memories are restored as new records, so newer records cannot be overwritten.
  return mutateMemory('create', change.before, {}, { actor: 'user' })
}

export const refreshMemories = async () => {
  useMemories.setState({ ready: false })
  await loadMemories()
}
