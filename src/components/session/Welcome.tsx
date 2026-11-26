import { useIsMobile } from '../../app/viewport.js'
import { useTranslate } from '../../i18n/index.js'
import { AssistantAvatar } from '../avatar/AssistantAvatar.js'
import { ChatInput } from '../chatInput/ChatInput.js'
import { MobileHeader } from './MobileHeader.js'
import styles from './Welcome.module.css'

/** 新对话：助手头像 + 问候语 + 输入框。桌面端居中；移动端输入框吸底 */
export const Welcome = () => {
  const t = useTranslate()
  const isMobile = useIsMobile()
  return (
    <div className={styles.container}>
      {isMobile ? <MobileHeader /> : null}
      <div className={styles.welcome}>
        {/* 头像和问候语作为一组，移动端整组居中，避免头像孤零零挂在顶部 */}
        <div className={styles.hero}>
          <div className={styles.avatar}>
            <AssistantAvatar size={isMobile ? 64 : 76} editable />
            <span className={styles.avatarHint}>{t('avatarSetCaption')}</span>
          </div>
          <div className={styles.greeting}>{t('welcomeMessageChat250808')}</div>
        </div>
        <ChatInput sessionId={null} />
        {isMobile ? <div className={styles.caveat}>{t('contentCaveat')}</div> : null}
      </div>
    </div>
  )
}
