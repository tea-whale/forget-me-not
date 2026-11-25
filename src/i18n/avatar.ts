export const avatarZh = {
  avatarSetAction: '设置助手头像',
  avatarSetHint: '点击设置助手头像',
  avatarSetCaption: '点击头像可更换',
  avatarSaved: '头像已更新',
  avatarFailed: '这张图片无法使用，请换一张试试',
} as const

export const avatarEn: Record<keyof typeof avatarZh, string> = {
  avatarSetAction: 'Set assistant avatar',
  avatarSetHint: 'Click to set the assistant avatar',
  avatarSetCaption: 'Click the avatar to change it',
  avatarSaved: 'Avatar updated',
  avatarFailed: 'That image cannot be used. Please try another one.',
}
