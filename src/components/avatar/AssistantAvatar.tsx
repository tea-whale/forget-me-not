import clsx from 'clsx'
import { useRef, type ChangeEvent } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { setAssistantAvatar, useSettings } from '../../services/settings.js'
import { showToast } from '../base/toastState.js'
import { DEFAULT_ASSISTANT_AVATAR } from './defaultAvatar.js'
import styles from './AssistantAvatar.module.css'

/** 头像输出边长与 data URL 上限：足够在 2 倍屏上清晰，又不会撑爆 localStorage。 */
const MAX_EDGE = 256
const MAX_LENGTH = 600_000

const readFile = (file: File): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error ?? new Error('read failed'))
    reader.readAsDataURL(file)
  })

const loadImage = (src: string): Promise<HTMLImageElement> =>
  new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('decode failed'))
    image.src = src
  })

/** 居中裁切为正方形并缩放，保证头像在任何屏幕上都清晰且体积可控。 */
const toAvatarUrl = async (file: File): Promise<string> => {
  if (!file.type.startsWith('image/')) {
    throw new Error('not an image')
  }
  if (file.size > 20 * 1024 * 1024) {
    throw new Error('image too large')
  }
  const image = await loadImage(await readFile(file))
  const side = Math.min(image.naturalWidth, image.naturalHeight)
  if (!side) {
    throw new Error('empty image')
  }
  const size = Math.min(MAX_EDGE, side)
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const context = canvas.getContext('2d')
  if (!context) {
    throw new Error('no canvas')
  }
  context.drawImage(
    image,
    (image.naturalWidth - side) / 2,
    (image.naturalHeight - side) / 2,
    side,
    side,
    0,
    0,
    size,
    size
  )
  const url = canvas.toDataURL('image/png')
  if (url.length > MAX_LENGTH) {
    throw new Error('image too large')
  }
  return url
}

export const AssistantAvatar = ({
  size = 32,
  editable = false,
  className,
}: {
  size?: number
  editable?: boolean
  className?: string
}) => {
  const t = useTranslate()
  const avatar = useSettings((state) => state.assistantAvatar)
  const input = useRef<HTMLInputElement>(null)
  const box = { width: size, height: size }
  const onFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) {
      return
    }
    try {
      setAssistantAvatar(await toAvatarUrl(file))
      showToast({ key: 'avatarSaved' })
    } catch {
      showToast({ key: 'avatarFailed', type: 'error' })
    }
  }
  const image = (
    <img
      className={styles.image}
      src={avatar ?? DEFAULT_ASSISTANT_AVATAR}
      alt=""
      draggable={false}
    />
  )
  if (!editable) {
    return (
      <span className={clsx(styles.avatar, className)} style={box}>
        {image}
      </span>
    )
  }
  return (
    <>
      <button
        type="button"
        className={clsx(styles.avatar, styles.editable, className)}
        style={box}
        onClick={() => input.current?.click()}
        aria-label={t('avatarSetAction')}
        title={t('avatarSetHint')}
      >
        {image}
        <span className={styles.badge} aria-hidden="true">
          +
        </span>
      </button>
      <input
        ref={input}
        className={styles.input}
        type="file"
        accept="image/*"
        tabIndex={-1}
        onChange={(event) => void onFileChange(event)}
      />
    </>
  )
}
