# chat-demo

English | [简体中文](README.zh-CN.md)

> **Fork notice.** This repository is a fork of
> [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) (MIT, © 2026 DeepSeek),
> with the modifications listed below. See [NOTICE](NOTICE) and [LICENSE](LICENSE) for
> attribution details.

A static chat demo powered by the DeepSeek API. No application backend is required: the browser connects directly to the configured API service.

You can [try the app online](https://deepseek-ai.github.io/chat-demo/) or download the archive from [Releases](https://github.com/deepseek-ai/deepseek-chat-demo/releases), extract it, and double-click `index.html` to use it locally without installation or a build. Enter your API key in Settings before your first chat.

## Getting started

Install Node.js 22.12+ and pnpm, then run:

```sh
git clone https://github.com/deepseek-ai/chat-demo.git
cd chat-demo
pnpm install
pnpm dev
```

Open http://localhost:8090.

To build the static site:

```sh
pnpm build
```

The output is written to `dist/`. Deploy it to a static web server or open `dist/index.html` directly. Opening the file directly requires browser support for storage on `file://` pages and an API service that permits their cross-origin requests.

## Features

- Multiple conversations with automatic titles, renaming, and deletion; edit prompts, regenerate replies, and switch between message branches.
- Streaming replies, deep thinking, smart search, link reading, and source citations; stop generation or continue eligible replies.
- Upload, paste, or drag and drop attachments; use voice input and read replies aloud. A live audio spectrum appears in the first row of the input area while recording on mobile.
- Markdown, syntax highlighting, KaTeX math, and Mermaid diagrams.
- Simplified Chinese and English, light and dark themes, mobile layouts, location settings, and chat history import and export.

## Data and storage

Your API key and settings are stored in the browser's localStorage. Conversations and original attachments are stored in IndexedDB. Local data is isolated by browser profile and site origin. Sending messages, uploading attachments, and using speech features sends the relevant content to the configured API service.

History import and export in Settings use ZIP archives. After confirmation and successful validation, importing replaces all local conversations and attachments. Exports include conversations and locally available original images, but exclude API keys, settings, and original non-image attachments. See the [history import/export documentation (Chinese)](src/services/chat/history/README.md) for the archive format and preservation details.

Settings → Data → Delete all chats clears all local conversations, original attachments, and unsent drafts after confirmation. It stops ongoing replies and returns to a new chat. Your API key and settings are preserved; files already uploaded to the API service are not deleted.

## What this fork adds

On top of the upstream demo:

- **Memory library.** "Core" memories are injected into every request; other memories are
  retrieved per question with a local BM25 index for Chinese/English text. Point the app at
  an OpenAI-compatible embedding endpoint in Settings → Memory to enable hybrid
  vector + text retrieval; it falls back to text search when the service is unavailable.
- **AI-editable memories.** The model gets `memory_search` and `memory_edit` tools, so it can
  create, revise and delete memories itself. Writes use revision guards, are audited, and can
  be undone from Settings → Memory.
- **Token and cache usage.** Every reply shows input/output/total tokens and the prompt-cache
  hit rate, accumulated across tool round trips and continuations.
- **Configurable assistant avatar.** Click the circular avatar above the prompt to pick an
  image; it is resized locally and reused next to every assistant reply.
- **`safety_check` set to `low`** instead of the upstream hard-coded `high`.
- **Android build.** `android/` wraps the built bundle in a WebView and packages an APK.

## Android build

The app stays a static site; the Android project only hosts `dist/`.

Requirements: JDK 17+, Android SDK platform 35 and build-tools 35.0.0 (no Gradle needed).

```sh
pnpm build
pwsh android/build-apk.ps1      # PowerShell on Windows, macOS or Linux
```

The signed APK is written to `android/build/`. Replace the launcher icon and default avatar
with your own artwork by running:

```sh
python tools/brand-assets.py path/to/logo.png
```

Without an argument the script regenerates the built-in mark. `android/assets/` and
`android/build/` are generated output and are not tracked.

## Development

| Command       | Description                  |
| ------------- | ---------------------------- |
| `pnpm dev`    | Start the development server |
| `pnpm build`  | Build into `dist/`           |
| `pnpm type`   | Run TypeScript type checks   |
| `pnpm lint`   | Lint with oxlint             |
| `pnpm format` | Format with oxfmt            |

[rspack.config.ts](rspack.config.ts) uses `https://api.deepseek.com` by default. Use `createConfig({ apiOrigin, devServer })` to customize the API origin and development server proxy.

Chat uses `deepseek-flash` by default through Anthropic-compatible Messages / Files APIs. Speech features use ASR / TTS endpoints. Requests include extensions for search, link reading, titles, and `latest_reminder`, so the configured service must support the corresponding capabilities. Supported attachment formats are determined by server-side validation.

```text
src/
  app/                 Application layout, themes, and global styles
  components/          Chat, settings, Markdown, and other UI components
  i18n/                English and Chinese translations
  services/
    api/               API requests, SSE, and protocol types
    chat/              Conversations, message branches, storage, and history import/export
    markdown/          Markdown parsing, syntax highlighting, and diagrams
    speech/            Audio recording and read-aloud playback
  utils/               Shared utilities
```

## License

[MIT](LICENSE). The build copies the license to `dist/LICENSE`; include it when distributing the app. Third-party dependencies are subject to their own licenses.
