export type MemoryKind = 'core' | 'rag'

export type MemoryRecord = {
  id: string
  kind: MemoryKind
  title: string
  content: string
  enabled: boolean
  source: 'user' | 'assistant'
  createdAt: number
  updatedAt: number
  revision: string
}

export type MemoryInput = Pick<MemoryRecord, 'kind' | 'title' | 'content' | 'enabled'>

export type MemoryChange = {
  id: string
  operation: 'create' | 'update' | 'delete' | 'undo'
  actor: 'user' | 'assistant'
  title: string
  createdAt: number
  before?: MemoryRecord
  after?: MemoryRecord
  sessionId?: string
}

export type MemoryReference = Pick<MemoryRecord, 'id' | 'title' | 'kind' | 'revision'>

export type MemoryContext = {
  system: string
  recall: string
  references: MemoryReference[]
  retrieval: 'lexical' | 'hybrid' | 'fallback' | 'unavailable'
  embeddingTokens?: number
}

export type MemoryNotice = {
  operation: MemoryChange['operation'] | 'search'
  title: string
  id?: string
  embeddingTokens?: number
}

export type EmbeddingSettings = {
  embeddingUrl: string
  embeddingModel: string
  embeddingApiKey: string
}
