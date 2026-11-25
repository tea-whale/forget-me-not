import type { MemoryRecord } from './types.js'

const stopWords = new Set([
  'a',
  'an',
  'the',
  'is',
  'are',
  'to',
  'of',
  'and',
  'or',
  'i',
  'my',
  'me',
  'you',
  'in',
  'on',
  'for',
  'with',
])

/** Latin words plus CJK bigrams avoid requiring a language-specific tokenizer. */
export const tokenize = (text: string): string[] => {
  const lower = text.normalize('NFKC').toLowerCase()
  const latin = (lower.match(/[\p{L}\p{N}_]+/gu) ?? []).filter(
    (word) =>
      !/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/u.test(word) && !stopWords.has(word)
  )
  const cjk = lower.match(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]+/gu) ?? []
  return [
    ...latin,
    ...cjk.flatMap((word) =>
      word.length === 1
        ? [word]
        : Array.from({ length: word.length - 1 }, (_, index) => word.slice(index, index + 2))
    ),
  ]
}

export type RankedMemory = { memory: MemoryRecord; score: number }

export const lexicalSearch = (records: MemoryRecord[], query: string): RankedMemory[] => {
  const terms = [...new Set(tokenize(query))]
  if (terms.length === 0 || records.length === 0) {
    return []
  }
  const documents = records.map((memory) => ({
    memory,
    tokens: tokenize(`${memory.title} ${memory.title} ${memory.content}`),
  }))
  const averageLength =
    documents.reduce((sum, doc) => sum + doc.tokens.length, 0) / documents.length || 1
  const frequencies = new Map(
    terms.map((term) => [term, documents.filter((doc) => doc.tokens.includes(term)).length])
  )
  return documents
    .map(({ memory, tokens }) => ({
      memory,
      score: terms.reduce((score, term) => {
        const tf = tokens.filter((token) => token === term).length
        if (!tf) {
          return score
        }
        const df = frequencies.get(term) ?? 0
        const idf = Math.log(1 + (documents.length - df + 0.5) / (df + 0.5))
        return (
          score + (idf * (tf * 2.2)) / (tf + 1.2 * (0.25 + (0.75 * tokens.length) / averageLength))
        )
      }, 0),
    }))
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score || a.memory.id.localeCompare(b.memory.id))
}

export const cosineSimilarity = (left: number[], right: number[]) => {
  if (left.length !== right.length || left.length === 0) {
    return 0
  }
  let dot = 0,
    a = 0,
    b = 0
  for (let i = 0; i < left.length; i++) {
    dot += left[i] * right[i]
    a += left[i] ** 2
    b += right[i] ** 2
  }
  return a && b ? dot / Math.sqrt(a * b) : 0
}

/** Reciprocal rank fusion avoids comparing incompatible BM25 and cosine scores. */
export const fuseRanks = (lexical: RankedMemory[], semantic: RankedMemory[]): RankedMemory[] => {
  const scores = new Map<string, RankedMemory>()
  for (const list of [lexical, semantic]) {
    list.forEach(({ memory }, index) => {
      const previous = scores.get(memory.id)
      scores.set(memory.id, { memory, score: (previous?.score ?? 0) + 1 / (60 + index + 1) })
    })
  }
  return [...scores.values()].sort(
    (a, b) => b.score - a.score || a.memory.id.localeCompare(b.memory.id)
  )
}

export const selectWithinBudget = (
  ranked: RankedMemory[],
  topK: number,
  charBudget: number
): MemoryRecord[] => {
  const selected: MemoryRecord[] = []
  let size = 0
  for (const { memory } of ranked) {
    const length = JSON.stringify({
      id: memory.id,
      revision: memory.revision,
      title: memory.title,
      content: memory.content,
    }).length
    if (size + length > charBudget) {
      continue
    }
    selected.push(memory)
    size += length
    if (selected.length >= topK) {
      break
    }
  }
  return selected
}
