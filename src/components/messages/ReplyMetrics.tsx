import { useTranslate } from '../../i18n/index.js'
import { usageTotals } from '../../services/api/usage.js'
import type { AssistantMessage } from '../../services/chat/types.js'
import { openSettings } from '../settings/settingsDialogState.js'
import styles from './ReplyMetrics.module.css'

export const ReplyMetrics = ({
  message,
  streaming,
}: {
  message: AssistantMessage
  streaming: boolean
}) => {
  const t = useTranslate()
  const usage = message.usage
  const totals = usage ? usageTotals(usage) : undefined
  const memory = message.memory
  const notices = message.memoryNotices?.filter((notice) => notice.operation !== 'search') ?? []
  const labels = {
    create: 'memoryCreated',
    update: 'memoryUpdated',
    delete: 'memoryDeleted',
    undo: 'memoryUndone',
    search: 'memorySearched',
  } as const
  if (!usage && !memory && !notices.length && streaming) {
    return null
  }
  return (
    <div className={styles.root}>
      {usage && totals ? (
        <details className={styles.usage}>
          <summary>
            <span>
              {t('usageInput')} <b>{totals.input.toLocaleString()}</b>
            </span>
            <span>
              {t('usageOutput')} <b>{usage.output_tokens.toLocaleString()}</b>
            </span>
            <span>
              {t('usageCache')} <b>{usage.cache_read_input_tokens.toLocaleString()}</b> ·{' '}
              {(totals.cacheRate * 100).toFixed(1)}%
            </span>
            <span className={styles.total}>
              {t('usageTotal')} {totals.total.toLocaleString()} tokens
            </span>
          </summary>
          <dl>
            <div>
              <dt>{t('usageCacheMiss')}</dt>
              <dd>{usage.input_tokens.toLocaleString()}</dd>
            </div>
            <div>
              <dt>{t('usageCache')}</dt>
              <dd>{usage.cache_read_input_tokens.toLocaleString()}</dd>
            </div>
            <div>
              <dt>{t('usageCacheWrite')}</dt>
              <dd>{usage.cache_creation_input_tokens.toLocaleString()}</dd>
            </div>
            {usage.output_tokens_details?.thinking_tokens !== undefined ? (
              <div>
                <dt>{t('usageThinking')}</dt>
                <dd>{usage.output_tokens_details.thinking_tokens.toLocaleString()}</dd>
              </div>
            ) : null}
            {usage.output_tokens_details?.safety_tokens !== undefined ? (
              <div>
                <dt>{t('usageSafety')}</dt>
                <dd>{usage.output_tokens_details.safety_tokens.toLocaleString()}</dd>
              </div>
            ) : null}
            {memory?.embeddingTokens !== undefined ? (
              <div>
                <dt>{t('usageEmbedding')}</dt>
                <dd>{memory.embeddingTokens.toLocaleString()}</dd>
              </div>
            ) : null}
          </dl>
          <p>{t('usageHint')}</p>
          {streaming || message.status === 'stopped' || message.status === 'error' ? (
            <p>{t('usagePartial')}</p>
          ) : null}
        </details>
      ) : !streaming ? (
        <p className={styles.missing}>{t('usageMissing')}</p>
      ) : null}
      {memory?.references.length ? (
        <details className={styles.memories}>
          <summary>{t('memoryUsed', { count: memory.references.length })}</summary>
          <ul>
            {memory.references.map((item) => (
              <li key={item.id}>
                <span>{t(item.kind === 'core' ? 'memoryCore' : 'memoryRag')}</span> {item.title}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      {memory?.retrieval === 'fallback' ? (
        <p className={styles.warning}>{t('memoryFallback')}</p>
      ) : null}
      {memory?.retrieval === 'unavailable' ? (
        <p className={styles.warning}>{t('memoryUnavailable')}</p>
      ) : null}
      {notices.length ? (
        <div className={styles.changes} role="status">
          {notices.map((notice, index) => (
            <span key={notice.id ?? index}>
              {t(labels[notice.operation])} · {notice.title}
            </span>
          ))}
        </div>
      ) : null}
      {memory || notices.length ? (
        <button className={styles.manage} type="button" onClick={() => openSettings('memory')}>
          {t('memoryManage')}
        </button>
      ) : null}
    </div>
  )
}
