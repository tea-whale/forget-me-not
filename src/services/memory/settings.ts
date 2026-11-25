import { create } from 'zustand'
import type { EmbeddingSettings } from './types.js'

export type MemorySettings = EmbeddingSettings & {
  enabled: boolean
  aiEdit: boolean
  topK: number
  recallChars: number
}

const KEY = 'chat-demo:memory-settings'
const defaults: MemorySettings = {
  enabled: true,
  aiEdit: true,
  topK: 6,
  recallChars: 8000,
  embeddingUrl: '',
  embeddingModel: '',
  embeddingApiKey: '',
}

const initial = (): MemorySettings => {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(KEY) ?? 'null')
    if (!saved || typeof saved !== 'object') {
      return defaults
    }
    const data = saved as Record<string, unknown>
    return {
      enabled: typeof data.enabled === 'boolean' ? data.enabled : defaults.enabled,
      aiEdit: typeof data.aiEdit === 'boolean' ? data.aiEdit : defaults.aiEdit,
      topK:
        typeof data.topK === 'number' && Number.isFinite(data.topK)
          ? Math.max(1, Math.min(12, Math.round(data.topK)))
          : defaults.topK,
      recallChars: defaults.recallChars,
      embeddingUrl: typeof data.embeddingUrl === 'string' ? data.embeddingUrl : '',
      embeddingModel: typeof data.embeddingModel === 'string' ? data.embeddingModel : '',
      embeddingApiKey: typeof data.embeddingApiKey === 'string' ? data.embeddingApiKey : '',
    }
  } catch {
    return defaults
  }
}

export const useMemorySettings = create<MemorySettings>(initial)

export const setMemorySettings = (patch: Partial<MemorySettings>) => {
  const next = { ...useMemorySettings.getState(), ...patch }
  next.topK = Math.max(1, Math.min(12, Math.round(next.topK) || defaults.topK))
  // Persist before updating the UI, so a failed write is never reported as saved.
  localStorage.setItem(KEY, JSON.stringify(next))
  useMemorySettings.setState(next)
}
