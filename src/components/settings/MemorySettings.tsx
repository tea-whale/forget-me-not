import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { exportMemories, importMemories } from '../../services/memory/backup.js'
import { embeddingEndpoint } from '../../services/memory/embeddings.js'
import { setMemorySettings, useMemorySettings } from '../../services/memory/settings.js'
import {
  CORE_CHAR_LIMIT,
  loadMemories,
  mutateMemory,
  refreshMemories,
  undoMemoryChange,
  useMemories,
} from '../../services/memory/store.js'
import type { MemoryInput, MemoryRecord } from '../../services/memory/types.js'
import { Button } from '../base/Button.js'
import { Modal } from '../base/Modal.js'
import { showToast } from '../base/toastState.js'
import styles from './MemorySettings.module.css'

const blank = (): MemoryInput => ({ title: '', content: '', kind: 'rag', enabled: true })

export const MemorySettings = () => {
  const t = useTranslate()
  const { records, changes, ready, error } = useMemories()
  const settings = useMemorySettings()
  const [query, setQuery] = useState('')
  const [editor, setEditor] = useState<{ input: MemoryInput; original?: MemoryRecord }>()
  const [deleting, setDeleting] = useState<MemoryRecord>()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const [embedding, setEmbedding] = useState(() => ({
    embeddingUrl: settings.embeddingUrl,
    embeddingModel: settings.embeddingModel,
    embeddingApiKey: settings.embeddingApiKey,
  }))
  const fileInput = useRef<HTMLInputElement>(null)
  const saving = useRef(false)
  const run = async (action: () => Promise<unknown>) => {
    if (saving.current) {
      return
    }
    saving.current = true
    setBusy(true)
    setFailure('')
    try {
      await action()
    } catch (error) {
      setFailure(
        t('memoryError', { message: error instanceof Error ? error.message : String(error) })
      )
    } finally {
      saving.current = false
      setBusy(false)
    }
  }
  useEffect(() => {
    void loadMemories().catch(() => {})
  }, [])
  const used = records
    .filter((item) => item.enabled && item.kind === 'core')
    .reduce((sum, item) => sum + item.title.length + item.content.length, 0)
  const filtered = records
    .filter((item) => `${item.title}\n${item.content}`.toLowerCase().includes(query.toLowerCase()))
    .sort((a, b) => a.kind.localeCompare(b.kind) || b.updatedAt - a.updatedAt)
  const save = (event: FormEvent) => {
    event.preventDefault()
    if (!editor) {
      return
    }
    void run(async () => {
      await mutateMemory(
        editor.original ? 'update' : 'create',
        editor.input,
        { id: editor.original?.id, revision: editor.original?.revision },
        { actor: 'user' }
      )
      setEditor(undefined)
      showToast({ key: 'memorySaved' })
    })
  }
  const operationLabel = {
    create: 'memoryCreated',
    update: 'memoryUpdated',
    delete: 'memoryDeleted',
    undo: 'memoryUndone',
  } as const
  return (
    <div className={styles.root} aria-busy={busy}>
      <p className={styles.intro}>{t('memoryIntro')}</p>
      <label className={styles.toggle}>
        <input
          type="checkbox"
          checked={settings.enabled}
          onChange={(event) => {
            const enabled = event.target.checked
            void run(async () => setMemorySettings({ enabled }))
          }}
        />
        {t('memoryEnabled')}
      </label>
      <label className={styles.toggle}>
        <input
          type="checkbox"
          checked={settings.aiEdit}
          onChange={(event) => {
            const aiEdit = event.target.checked
            void run(async () => setMemorySettings({ aiEdit }))
          }}
        />
        {t('memoryAiEdit')}
      </label>
      <p className={styles.hint}>{t('memoryAiEditHint')}</p>
      <div className={styles.budget}>
        <span>{t('memoryCoreBudget', { used, limit: CORE_CHAR_LIMIT })}</span>
        <progress value={used} max={CORE_CHAR_LIMIT} aria-label={t('memoryCore')} />
      </div>
      <div className={styles.toolbar}>
        <input
          type="search"
          aria-label={t('memoryFilter')}
          placeholder={t('memoryFilter')}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <Button
          size="s"
          disabled={busy || !ready}
          onClick={() => {
            setFailure('')
            setEditor({ input: blank() })
          }}
        >
          {t('memoryAdd')}
        </Button>
      </div>
      {failure && !editor && !deleting ? (
        <p role="alert" className={styles.error}>
          {failure}
        </p>
      ) : null}
      {error ? (
        <p role="alert">
          {t('memoryLoadError')}{' '}
          <Button size="s" onClick={() => void run(refreshMemories)}>
            {t('memoryRetry')}
          </Button>
        </p>
      ) : null}
      {ready && filtered.length === 0 ? <p className={styles.empty}>{t('memoryEmpty')}</p> : null}
      <div className={styles.list}>
        {filtered.map((memory) => (
          <article key={memory.id} className={styles.card}>
            <div className={styles.cardHeading}>
              <strong>{memory.title}</strong>
              <span className={styles.badge}>
                {t(memory.kind === 'core' ? 'memoryCore' : 'memoryRag')}
              </span>
              {!memory.enabled ? <span className={styles.hint}>{t('memoryDisabled')}</span> : null}
            </div>
            <p className={styles.preview}>{memory.content}</p>
            <div className={styles.actions}>
              <span className={styles.hint}>
                {t(memory.source === 'assistant' ? 'memoryActorAi' : 'memoryActorUser')} ·{' '}
                {new Date(memory.updatedAt).toLocaleDateString()}
              </span>
              <Button
                variant="outlined"
                size="s"
                disabled={busy}
                onClick={() => {
                  setFailure('')
                  setEditor({ input: { ...memory }, original: memory })
                }}
              >
                {t('memoryEdit')}
              </Button>
              <Button
                variant="dangerOutlined"
                size="s"
                disabled={busy}
                onClick={() => {
                  setFailure('')
                  setDeleting(memory)
                }}
              >
                {t('delete')}
              </Button>
            </div>
          </article>
        ))}
      </div>
      <details className={styles.details}>
        <summary>{t('memoryEmbedding')}</summary>
        <p className={styles.hint}>{t('memoryEmbeddingHint')}</p>
        <label className={styles.field}>
          {t('memoryEmbeddingUrl')}
          <input
            type="url"
            placeholder="https://example.com/v1"
            value={embedding.embeddingUrl}
            onChange={(event) => setEmbedding({ ...embedding, embeddingUrl: event.target.value })}
          />
        </label>
        <label className={styles.field}>
          {t('memoryEmbeddingModel')}
          <input
            value={embedding.embeddingModel}
            onChange={(event) => setEmbedding({ ...embedding, embeddingModel: event.target.value })}
          />
        </label>
        <label className={styles.field}>
          {t('memoryEmbeddingKey')}
          <input
            type="password"
            autoComplete="off"
            value={embedding.embeddingApiKey}
            onChange={(event) =>
              setEmbedding({ ...embedding, embeddingApiKey: event.target.value })
            }
          />
        </label>
        <label className={styles.field}>
          {t('memoryTopK')}
          <input
            type="number"
            min={1}
            max={12}
            value={settings.topK}
            onChange={(event) => {
              const topK = Number(event.target.value)
              void run(async () => setMemorySettings({ topK }))
            }}
          />
        </label>
        <Button
          variant="outlined"
          size="s"
          disabled={busy}
          onClick={() =>
            void run(async () => {
              const next = {
                ...embedding,
                embeddingUrl: embedding.embeddingUrl.trim(),
                embeddingModel: embedding.embeddingModel.trim(),
              }
              if (Boolean(next.embeddingUrl) !== Boolean(next.embeddingModel)) {
                throw new Error('Provide both embedding URL and model, or leave both empty.')
              }
              if (next.embeddingUrl) {
                embeddingEndpoint(next.embeddingUrl)
              }
              setMemorySettings(next)
              showToast({ key: 'memorySaved' })
            })
          }
        >
          {t('memoryEmbeddingSave')}
        </Button>
      </details>
      <details className={styles.details}>
        <summary>
          {t('memoryHistory')} ({changes.length})
        </summary>
        {changes.slice(0, 40).map((change) => {
          const canUndo = change.after
            ? records.some(
                (item) => item.id === change.after?.id && item.revision === change.after.revision
              )
            : !records.some(
                (item) =>
                  item.title === change.before?.title && item.content === change.before.content
              )
          return (
            <div key={change.id} className={styles.change}>
              <div>
                <strong>
                  {t(operationLabel[change.operation])} · {change.title}
                </strong>
                <div className={styles.hint}>
                  {t(change.actor === 'assistant' ? 'memoryActorAi' : 'memoryActorUser')} ·{' '}
                  {new Date(change.createdAt).toLocaleString()}
                </div>
              </div>
              <Button
                variant="outlined"
                size="s"
                disabled={busy || !canUndo}
                onClick={() =>
                  void run(async () => {
                    await undoMemoryChange(change)
                  })
                }
              >
                {t('memoryUndo')}
              </Button>
            </div>
          )
        })}
      </details>
      <div className={styles.backup}>
        <Button
          variant="outlined"
          size="s"
          disabled={busy || !ready}
          onClick={() => void run(exportMemories)}
        >
          {t('memoryExport')}
        </Button>
        <Button
          variant="outlined"
          size="s"
          disabled={busy || !ready}
          onClick={() => fileInput.current?.click()}
        >
          {t('memoryImport')}
        </Button>
        <input
          ref={fileInput}
          hidden
          type="file"
          accept="application/json,.json"
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) {
              void run(async () => {
                const count = await importMemories(file)
                showToast({ key: 'memoryImportDone', params: { count } })
              })
            }
          }}
        />
      </div>
      <p className={styles.hint}>{t('memoryImportHint')}</p>
      <p className={styles.hint}>{t('memoryLocal')}</p>
      {editor ? (
        <Modal
          title={t(editor.original ? 'memoryEdit' : 'memoryAdd')}
          className={styles.editor}
          closable={!busy}
          onClose={() => {
            if (!busy) {
              setEditor(undefined)
            }
          }}
        >
          <form onSubmit={save} className={styles.form}>
            <label className={styles.field}>
              {t('memoryName')}
              <input
                required
                maxLength={120}
                autoFocus
                value={editor.input.title}
                onChange={(event) =>
                  setEditor({ ...editor, input: { ...editor.input, title: event.target.value } })
                }
              />
            </label>
            <label className={styles.field}>
              {t('memoryKind')}
              <select
                value={editor.input.kind}
                onChange={(event) =>
                  setEditor({
                    ...editor,
                    input: { ...editor.input, kind: event.target.value as MemoryInput['kind'] },
                  })
                }
              >
                <option value="rag">{t('memoryRag')}</option>
                <option value="core">{t('memoryCore')}</option>
              </select>
            </label>
            <label className={styles.field}>
              {t('memoryContent')}
              <textarea
                required
                maxLength={4000}
                rows={8}
                value={editor.input.content}
                onChange={(event) =>
                  setEditor({ ...editor, input: { ...editor.input, content: event.target.value } })
                }
              />
            </label>
            <label className={styles.toggle}>
              <input
                type="checkbox"
                checked={editor.input.enabled}
                onChange={(event) =>
                  setEditor({
                    ...editor,
                    input: { ...editor.input, enabled: event.target.checked },
                  })
                }
              />
              {t('memoryActive')}
            </label>
            {failure ? (
              <p role="alert" className={styles.error}>
                {failure}
              </p>
            ) : null}
            <div className={styles.actions}>
              <Button variant="outlined" disabled={busy} onClick={() => setEditor(undefined)}>
                {t('cancel')}
              </Button>
              <Button type="submit" disabled={busy}>
                {t('memorySave')}
              </Button>
            </div>
          </form>
        </Modal>
      ) : null}
      {deleting ? (
        <Modal
          title={t('memoryDeleteTitle')}
          onClose={() => {
            if (!busy) {
              setDeleting(undefined)
            }
          }}
          footer={
            <>
              <Button variant="outlined" disabled={busy} onClick={() => setDeleting(undefined)}>
                {t('cancel')}
              </Button>
              <Button
                variant="danger"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    await mutateMemory(
                      'delete',
                      undefined,
                      { id: deleting.id, revision: deleting.revision },
                      { actor: 'user' }
                    )
                    setDeleting(undefined)
                  })
                }
              >
                {t('delete')}
              </Button>
            </>
          }
        >
          <p>{deleting.title}</p>
          <p>{t('memoryDeleteHint')}</p>
          {failure ? (
            <p role="alert" className={styles.error}>
              {failure}
            </p>
          ) : null}
        </Modal>
      ) : null}
    </div>
  )
}
