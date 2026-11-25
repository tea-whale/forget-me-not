# Forget Me Not

非官方修改版，基于 [DeepSeek chat-demo](https://github.com/deepseek-ai/chat-demo)（MIT，© 2026 DeepSeek）。在原 demo 基础上加了三个功能：

- **记忆**：基础记忆每轮注入，其余按当前问题检索；AI 可自己增删改记忆，也支持手动管理。
- **用量显示**：每条回复下方显示 token 消耗与缓存命中情况。
- **助手头像**：可手动上传，首页和每条回复同步显示。

## 安装

安卓安装包见 [Releases](https://github.com/tea-whale/forget-me-not/releases)。首次打开在设置里填入自己的 DeepSeek API Key。

## 本地运行

需要 Node.js 22.12+ 与 pnpm：

```sh
pnpm install
pnpm dev      # 开发
pnpm build    # 静态产物 dist/
```

安卓打包（另需 JDK 17+、Android SDK platform 35 与 build-tools 35.0.0）：

```sh
pnpm build
pwsh android/build-apk.ps1
```

## 说明

与 DeepSeek 无隶属关系，未获其背书。API Key 与聊天记录只存在本机；记忆、头像、聊天记录均可在设置里导出。

许可与来源见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。
