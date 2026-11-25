# chat-demo

[English](README.md) | 简体中文

> **Fork 说明。** 本仓库 fork 自
> [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo)（MIT，© 2026 DeepSeek），
> 并在其基础上做了下述修改。归属与许可细节见 [NOTICE](NOTICE) 和 [LICENSE](LICENSE)。

基于 DeepSeek API 的静态聊天示例。无需应用后端，浏览器直接请求配置的 API 服务。

点击 [在线体验](https://deepseek-ai.github.io/chat-demo/) 即可使用，也可以前往 [Releases](https://github.com/deepseek-ai/deepseek-chat-demo/releases) 下载压缩包，解压后双击 `index.html` 在本地使用，无需安装或构建。首次使用时，请在设置中填写 API Key。

## 运行

准备 Node.js 22.12+ 和 pnpm：

```sh
git clone https://github.com/deepseek-ai/chat-demo.git
cd chat-demo
pnpm install
pnpm dev
```

打开 http://localhost:8090 。

生成静态产物：

```sh
pnpm build
```

产物位于 `dist/`，可部署到静态服务器，也可直接打开 `dist/index.html`。直接打开时，浏览器和 API 服务需允许 `file://` 页面的存储与跨域请求。

## 功能

- 多会话、自动标题、重命名与删除；编辑提问、重新生成和切换消息分支。
- 流式回答、深度思考、智能搜索、链接阅读与来源引用；支持停止和符合条件的继续生成。
- 附件上传、粘贴与拖拽，语音输入和回复朗读；移动端录音时输入框首行显示实时频谱。
- Markdown、代码高亮、KaTeX 公式和 Mermaid 图表。
- 简体中文与英文、浅色与深色主题、移动端布局、位置设置、历史导入导出。

## 数据

API Key 和设置保存在浏览器 localStorage，会话及附件原件保存在 IndexedDB，本地数据按浏览器用户配置和站点源（origin）隔离。发送对话、上传附件和使用语音功能时，相应内容会提交给配置的 API 服务。

设置中的历史导入导出使用 ZIP 格式。导入会在确认并校验成功后替换全部本地会话和附件；导出包含会话与本地可用的图片原件，不包含 API Key、设置或非图片附件原件。格式与保留范围见[历史导入导出](src/services/chat/history/README.md)。

设置 → 数据管理 → 删除所有对话，会在确认后清空全部本地会话、附件原件和未发送的草稿，停止正在生成的回复并回到新对话。API Key 和其他设置保留，已上传到 API 服务的文件不受影响。

## 本 fork 的改动

在官方 demo 基础上新增：

- **记忆库。** 「基础记忆」每轮注入；其余记忆按当前问题检索，中文/英文用本地 BM25 索引。在设置 → 记忆里填写 OpenAI 兼容的 embedding 接口后启用向量 + 全文混合检索，服务不可用时自动回退全文检索。
- **AI 可编辑记忆。** 模型可调用 `memory_search` 与 `memory_edit` 自行新增、修订、删除记忆；写入带修订号校验、变更审计，并可在设置 → 记忆中撤销。
- **Token 与缓存用量。** 每条回复下方显示输入/输出/总 token 与缓存命中率，累计工具往返和续写的消耗。
- **可设置助手头像。** 点击提示词上方的圆形头像选图，本地自动缩放保存，并同步显示在每条 AI 回复旁。
- **`safety_check` 改为 `low`**（官方 demo 固定为 `high`）。
- **安卓构建。** `android/` 用 WebView 承载构建产物并打包 APK。

## 安卓构建

应用仍是静态站点，安卓工程只负责承载 `dist/`。

依赖：JDK 17+、Android SDK platform 35 与 build-tools 35.0.0（不需要 Gradle）。

```sh
pnpm build
pwsh android/build-apk.ps1
```

签名后的 APK 输出到 `android/build/`。想换成自己的启动图标和默认头像：

```sh
python tools/brand-assets.py path/to/logo.png
```

不传参数则重新生成内置图形。`android/assets/` 与 `android/build/` 是生成产物，不纳入版本管理。

## 开发

| 命令          | 用途                |
| ------------- | ------------------- |
| `pnpm dev`    | 启动开发服务器      |
| `pnpm build`  | 构建到 `dist/`      |
| `pnpm type`   | TypeScript 类型检查 |
| `pnpm lint`   | oxlint 检查         |
| `pnpm format` | oxfmt 格式化        |

[rspack.config.ts](rspack.config.ts) 默认连接 `https://api.deepseek.com`。`createConfig({ apiOrigin, devServer })` 支持自定义 API 地址和开发服务器代理。

聊天默认使用 `deepseek-flash`，通过 Anthropic 兼容的 Messages / Files API 通信；语音使用 ASR / TTS 接口。请求使用搜索、链接阅读、标题和 `latest_reminder` 等扩展，接入服务需支持相应能力。附件实际支持的格式以服务端校验为准。

```text
src/
  app/                 应用布局、主题与全局样式
  components/          聊天、设置、Markdown 等界面组件
  i18n/                中英文文案
  services/
    api/               API 请求、SSE 与协议类型
    chat/              会话、消息分支、本地存储与历史导入导出
    markdown/          Markdown 解析、代码高亮与图表
    speech/            录音与朗读
  utils/               通用工具
```

## 许可证

[MIT](LICENSE)。构建会将许可证复制到 `dist/LICENSE`，分发时请一并保留；第三方依赖遵循各自的许可证。
