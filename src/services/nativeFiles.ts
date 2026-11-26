import { diag } from './diagnostics.js'

const RESULT_EVENT = 'native-files-picked'
const PICKED_PATH = '/_picked/'
const MAX_BYTES = 64 * 1024 * 1024

type NativeFile = { url: string; name: string; type: string; size: number }
type PickResult = { id: string; files?: NativeFile[]; error?: string }

declare global {
  interface Window {
    NativeFiles?: { pick: (requestId: string) => void }
  }
}

/** 浏览器继续使用 input；新版安卓由原生读取文件，避免 WebView 的 content:// 空读。 */
export const pickNativeFiles = (): Promise<File[]> | null => {
  const bridge = window.NativeFiles
  if (!bridge || window.location.origin !== 'https://appassets.androidplatform.net') {
    return null
  }
  const id = crypto.randomUUID()
  return new Promise<File[]>((resolve, reject) => {
    const onResult = (event: Event) => {
      const result = (event as CustomEvent<PickResult>).detail
      if (!result || result.id !== id) {
        return
      }
      window.removeEventListener(RESULT_EVENT, onResult)
      void (async () => {
        if (result.error) {
          throw new Error(result.error)
        }
        if (!Array.isArray(result.files) || result.files.length > 50) {
          throw new Error('Invalid native file list')
        }
        const files: File[] = []
        for (const descriptor of result.files) {
          const url = new URL(descriptor.url, window.location.origin)
          if (
            url.origin !== window.location.origin ||
            !url.pathname.startsWith(PICKED_PATH) ||
            !descriptor.name ||
            !Number.isSafeInteger(descriptor.size) ||
            descriptor.size <= 0 ||
            descriptor.size > MAX_BYTES
          ) {
            throw new Error('Invalid native file descriptor')
          }
          const response = await fetch(url.href, { cache: 'no-store' })
          if (!response.ok) {
            throw new Error(`Cannot read picked file (HTTP ${response.status})`)
          }
          const blob = await response.blob()
          if (blob.size !== descriptor.size) {
            throw new Error(`Incomplete picked file: ${blob.size}/${descriptor.size} bytes`)
          }
          // 构造由 JS 持有字节的 File，草稿预览和上传都不再依赖 content:// 权限。
          const file = new File([blob], descriptor.name, { type: descriptor.type || blob.type })
          files.push(file)
          // 记录前 12 字节，便于判断「后缀是 jpg、内容其实不是」这类情况
          const head = new Uint8Array(await blob.slice(0, 12).arrayBuffer())
          const hex = Array.from(head, (byte) => byte.toString(16).padStart(2, '0')).join(' ')
          diag(`原生文件已读取 ${file.name} ${file.type || '未知类型'} ${file.size}B 头部 ${hex}`)
        }
        return files
      })().then(resolve, reject)
    }
    window.addEventListener(RESULT_EVENT, onResult)
    try {
      diag('打开原生附件选择器')
      bridge.pick(id)
    } catch (error) {
      window.removeEventListener(RESULT_EVENT, onResult)
      reject(error)
    }
  })
}
