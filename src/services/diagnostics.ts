import { create } from 'zustand'

/**
 * 临时诊断日志：只保留最近若干条，存在内存里。
 *
 * 用来定位「手机上跑起来和电脑不一样」的问题（附件上传失败、脚本报错）。
 * 用户在 设置 → 通用 → 诊断信息 里能看到并复制，不需要开发者工具。
 */
const MAX_LINES = 120

type DiagnosticsState = { lines: string[] }

export const useDiagnostics = create<DiagnosticsState>(() => ({ lines: [] }))

const pad = (value: number) => String(value).padStart(2, '0')

const stamp = () => {
  const now = new Date()
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

/** 截断过长的内容，避免一条日志占满面板 */
const shorten = (text: string, limit = 400) =>
  text.length > limit ? `${text.slice(0, limit)}…` : text

export const diag = (message: string) => {
  const line = `${stamp()} ${shorten(message)}`
  useDiagnostics.setState((state) => ({ lines: [...state.lines, line].slice(-MAX_LINES) }))
}

export const clearDiagnostics = () => useDiagnostics.setState({ lines: [] })

/** 环境信息：确认装的是哪个包、WebView 版本、语言与屏幕 */
export const diagnosticsEnvironment = () => {
  const version =
    document.querySelector('meta[name="application-version"]')?.getAttribute('content') ?? '?'
  const ua = navigator.userAgent
  const webview = /(Chrome\/[\d.]+)/.exec(ua)?.[1] ?? '?'
  const android = /Android ([\d.]+)/.exec(ua)?.[1] ?? '?'
  return [
    `版本 ${version}`,
    `Android ${android} · ${webview}`,
    `屏幕 ${window.innerWidth}x${window.innerHeight} @${window.devicePixelRatio}x`,
    `语言 ${navigator.language}`,
    `Origin ${location.origin}`,
  ].join('\n')
}

/** 收集未捕获的异常；同一条错误只记一次，避免面板被刷屏 */
export const installDiagnostics = () => {
  window.addEventListener('error', (event) => {
    const target = event.target
    if (target instanceof HTMLImageElement || target instanceof HTMLScriptElement) {
      diag(`资源加载失败 ${target instanceof HTMLImageElement ? target.src.slice(0, 120) : ''}`)
      return
    }
    diag(`脚本错误 ${event.message}`)
  })
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    diag(`未处理的异常 ${reason instanceof Error ? `${reason.name}: ${reason.message}` : String(reason)}`)
  })
}
