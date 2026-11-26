export const diagnosticsZh = {
  diagnosticsTitle: '诊断信息',
  diagnosticsDescription: '附件上传失败时，这里会记录接口返回的原因，长按可选复制',
  diagnosticsOpen: '查看',
  diagnosticsSelfCheck: '测试上传',
  diagnosticsSelfCheckOk: '上传接口可用',
  diagnosticsSelfCheckFailed: '上传失败，原因见下方记录',
  diagnosticsNeedKey: '请先填写 API Key',
  diagnosticsCopy: '复制全部',
  diagnosticsCopied: '已复制',
  diagnosticsCopyFailed: '复制失败，请长按文本手动选择',
  diagnosticsClear: '清空',
  diagnosticsEmpty: '暂无记录',
} as const

export const diagnosticsEn: Record<keyof typeof diagnosticsZh, string> = {
  diagnosticsTitle: 'Diagnostics',
  diagnosticsDescription:
    'Upload failures are recorded here, including the reason the API returned. Long-press to select and copy.',
  diagnosticsOpen: 'View',
  diagnosticsSelfCheck: 'Test upload',
  diagnosticsSelfCheckOk: 'Upload API works',
  diagnosticsSelfCheckFailed: 'Upload failed, see the record below',
  diagnosticsNeedKey: 'Set an API key first',
  diagnosticsCopy: 'Copy all',
  diagnosticsCopied: 'Copied',
  diagnosticsCopyFailed: 'Copy failed. Long-press the text to copy it manually',
  diagnosticsClear: 'Clear',
  diagnosticsEmpty: 'No records yet',
}
