import { getMemoryDB } from './store.js'
import { cosineSimilarity, fuseRanks, lexicalSearch, type RankedMemory } from './search.js'
import type { EmbeddingSettings, MemoryRecord } from './types.js'

export const embeddingEndpoint = (raw: string): string => {
  const url = new URL(raw)
  if (
    url.protocol !== 'https:' &&
    !(url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))
  ) {
    throw new Error('Use HTTPS for embeddings, or HTTP on localhost.')
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('Embedding URL must not contain credentials, a query, or a fragment.')
  }
  return url.href.replace(/\/$/, '').replace(/(?:\/embeddings)?$/, '/embeddings')
}

export const retrieveMemories = async (
  records: MemoryRecord[],
  query: string,
  settings: EmbeddingSettings,
  signal: AbortSignal
): Promise<{
  ranked: RankedMemory[]
  mode: 'lexical' | 'hybrid' | 'fallback'
  embeddingTokens?: number
}> => {
  const lexical = lexicalSearch(records, query)
  if (!settings.embeddingUrl || !settings.embeddingModel || records.length === 0 || !query.trim()) {
    return { ranked: lexical, mode: 'lexical' }
  }
  let embeddingTokens = 0
  try {
    const endpoint = embeddingEndpoint(settings.embeddingUrl)
    const provider = `${endpoint}|${settings.embeddingModel}`
    const db = await getMemoryDB()
    const cached = await db.getAll('embeddings')
    const vectors = new Map(
      cached.filter((item) => item.provider === provider).map((item) => [item.key, item])
    )
    const missing = records.filter((item) => vectors.get(item.id)?.revision !== item.revision)
    const documents = [query, ...missing.map((item) => `${item.title}\n${item.content}`)]
    const generated: number[][] = []
    // Batch calls remain bounded, and the whole retrieval has a timeout.
    const timeout = new AbortController()
    const onAbort = () => timeout.abort(signal.reason)
    signal.addEventListener('abort', onAbort, { once: true })
    if (signal.aborted) {
      onAbort()
    }
    const timer = setTimeout(() => timeout.abort(new Error('Embedding request timed out.')), 15000)
    try {
      for (let offset = 0; offset < documents.length; offset += 32) {
        const input = documents.slice(offset, offset + 32)
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(settings.embeddingApiKey
              ? { Authorization: `Bearer ${settings.embeddingApiKey}` }
              : {}),
          },
          body: JSON.stringify({ model: settings.embeddingModel, input, encoding_format: 'float' }),
          signal: timeout.signal,
          redirect: 'error',
        })
        if (!response.ok) {
          throw new Error(`Embedding service returned ${response.status}.`)
        }
        const body = (await response.json()) as {
          data?: { index: number; embedding: number[] }[]
          usage?: { total_tokens?: number }
        }
        if (!Array.isArray(body.data) || body.data.length !== input.length) {
          throw new Error('Invalid embedding response.')
        }
        const sorted = [...body.data].sort((a, b) => a.index - b.index)
        if (
          sorted.some(
            (item, index) =>
              item.index !== index ||
              !Array.isArray(item.embedding) ||
              !item.embedding.length ||
              item.embedding.length > 32768 ||
              !item.embedding.every((n) => typeof n === 'number' && Number.isFinite(n))
          )
        ) {
          throw new Error('Invalid embedding vector.')
        }
        generated.push(...sorted.map((item) => item.embedding))
        if (
          typeof body.usage?.total_tokens === 'number' &&
          Number.isFinite(body.usage.total_tokens) &&
          body.usage.total_tokens >= 0
        ) {
          embeddingTokens += body.usage.total_tokens
        }
      }
    } finally {
      clearTimeout(timer)
      signal.removeEventListener('abort', onAbort)
    }
    const [question, ...newVectors] = generated
    if (newVectors.some((vector) => vector.length !== question.length)) {
      throw new Error('Embedding dimensions changed.')
    }
    const tx = db.transaction('embeddings', 'readwrite')
    for (let index = 0; index < missing.length; index++) {
      const item = {
        key: missing[index].id,
        revision: missing[index].revision,
        provider,
        vector: newVectors[index],
      }
      vectors.set(item.key, item)
      await tx.store.put(item)
    }
    await tx.done
    const semantic = records
      .map((memory) => ({
        memory,
        score: cosineSimilarity(question, vectors.get(memory.id)?.vector ?? []),
      }))
      .filter((item) => item.score >= 0.3)
      .sort((a, b) => b.score - a.score)
    return { ranked: fuseRanks(lexical, semantic), mode: 'hybrid', embeddingTokens }
  } catch (error) {
    if (signal.aborted) {
      throw error
    }
    return { ranked: lexical, mode: 'fallback', embeddingTokens }
  }
}
