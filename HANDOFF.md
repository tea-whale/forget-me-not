# 交接说明（给接手的开发者 / 另一个 AI）

这个仓库是 `deepseek-ai/chat-demo`（DeepSeek 官方 API demo，React + rspack + TypeScript 静态站）
的 fork，加了三块东西：**记忆功能**、**每条回复的 token / 缓存用量显示**、**助手头像**，
外面套了一个手写的 Android WebView 壳子（不用 Gradle，直接 aapt2 + javac + d8）。

---

## 1. 路径与仓库

| 项目 | 值 |
| --- | --- |
| 会话工作区 | `D:\Testdsh` |
| 仓库根目录 | `D:\Testdsh\chat-demo`（在 Workspace 内，可直接读写） |
| 分支 | `feat/memory-rag-usage`（与 `main` 同步） |
| 当前提交 | `990afdb fix(android): accept image/* and sniff real image format; add diagnostics panel` + 本地未提交改动（1.1.9） |
| 版本 | `package.json` → `1.1.9`，`android/build-apk.ps1` → `$VersionCode = '11'` |
| 远程 | `origin` = https://github.com/tea-whale/forget-me-not.git ，`upstream` = deepseek-ai/chat-demo |
| 线上包 | https://github.com/tea-whale/forget-me-not/releases/tag/v1.1.7 |
| 桌面 APK | `C:\Users\Windows\OneDrive\Desktop\Forget Me Not v1.1.9.apk`（桌面被 OneDrive 接管；1.1.8/1.1.9 尚未发布） |

不在工作区里、但构建要用到的：

- JDK 17：`C:\Users\Windows\.jdks\ms-17.0.19`
- Android SDK：`D:\Android\Sdk`（build-tools 35.0.0、platform android-35）
- 签名用 debug keystore：`D:\Android\debug.keystore`（别名 `androiddebugkey`，密码 `android`）
- GitHub 凭据：Windows 凭据管理器里的 `git credential fill`（推送 / 发 Release 时用）

---

## 2. 构建

```powershell
cd D:\Testdsh\chat-demo
node scripts\build.mjs          # 产物 dist\，并把版本号写进 dist\index.html
pwsh -File android\build-apk.ps1  # 产物 android\build\ForgetMeNot-<version>.apk
```

注意事项：

- `scripts\build.mjs` 调的是 `node_modules/@rspack/cli`，不需要联网。
- `android/build-apk.ps1` 版本号从 `package.json` 读；**改版本要同时手动把 `$VersionCode` 加 1**，
  否则安卓覆盖安装会失败。
- `android/assets/`、`android/build/`、`dist/`、`*.apk` 都在 `.gitignore` 里，不入库。
- Windows 上 aapt2 会把 `-A` 加进去的资源写成反斜杠路径，设备上读不到。脚本因此在链接后
  用 .NET `ZipArchive` 手动写入 `assets/app/...`（正斜杠）+ `classes.dex`，并校验条目名。
- 沙箱环境里启动 `git`/`javac`/`aapt2` 会被拦，需要放宽权限再跑。

---

## 3. 目录地图

```
src/
  main.tsx                     入口：setupTheme + installDiagnostics
  index.html                   标题 Forget Me Not；版本 meta 由 rspack 注入
  app/                         App 外壳、主题、viewport（<768px 走移动端布局）
  components/
    avatar/AssistantAvatar.tsx  圆形头像，editable 时可点击换图（canvas 缩到 256px 存 data URL）
    chatInput/ChatInput.tsx     输入框 + 附件按钮 + `<input type=file accept={ACCEPT}>`
    chatInput/DraftAttachments.tsx 附件卡片（上传中 / 失败 / 重试）
    files/useFileView.ts        草稿用 File 直接建 blob: 预览；已发送的用 IndexedDB 原件
    messages/AssistantMessage.tsx + ReplyMetrics.tsx  AI 气泡 + token/缓存用量
    session/Welcome.tsx         新对话页：头像 + 问候语 + 输入框
    settings/SettingsDialog.tsx 设置弹窗（通用 / 记忆 / 数据），含诊断信息面板
  services/
    attachments.ts            附件校验、上传队列、失败文案
    api/client.ts             fetch 封装（request / uploadFile / 流式）、失败写诊断日志
    api/types.ts              Anthropic 兼容协议类型（含 deepseek_web_search 等 server tool）
    chat/store.ts             会话状态机、SAFETY_CHECK、记忆注入、请求组装
    chat/capabilities.ts      系统提示词与 server tool 声明（搜索开关同步）
    nativeFiles.ts            安卓原生选文件：取同源字节 → 构造 File → 交给附件校验/上传
    chat/db.ts / drafts.ts    IndexedDB 附件与会话存储、草稿
    memory/*                  长期记忆（IndexedDB `chat-demo-memory` v1）、检索、工具调用
    diagnostics.ts            内存诊断日志（最近 120 条）
android/
  AndroidManifest.xml          provider: com.teawhale.chatdemo.picked
  src/com/teawhale/chatdemo/MainActivity.java        WebView 宿主（约 700 行）
  src/com/teawhale/chatdemo/PickedFileProvider.java  把选中的文件以 content:// 提供给页面
  build-apk.ps1
tools/brand-assets.py          生成图标与默认头像
```

数据兼容：聊天库 `chat-demo` v11、记忆库 `chat-demo-memory` v1；包名 `com.teawhale.chatdemo`、
签名不变的情况下覆盖安装会保留历史。卸载或换签名 = 数据全丢。

---

## 4. 安卓壳子的关键行为

- 页面从 `https://appassets.androidplatform.net/index.html` 提供，由 `shouldInterceptRequest`
  直接从 APK 的 `assets/app/` 读；用 https origin 是为了 IndexedDB / fetch / CORS 表现和浏览器一致。
- 入口 URL 带 `?v=<versionName>`，`index.html` 响应头 `Cache-Control: no-store`，避免旧页面被 WebView 缓存。
- `WebSettings`：`setAllowFileAccess(false)`、`setAllowContentAccess(true)`、DOM storage 开。
- 文件选择链路（1.1.8 起聊天框改走原生读取）：
  1. 聊天框附件按钮 → `NativeFiles.pick(requestId)`（`@JavascriptInterface`）；桌面浏览器仍用 `<input type=file>`
  2. Java 起 `ACTION_OPEN_DOCUMENT`（`*/*`，可多选）→ `receiveNativeFiles()` 在单线程池里读字节：
     上限 64 MB，按文件头判断图片；图片统一经 `normalizePickedImage()` 重编码
     （无透明→JPEG q92，有透明→PNG，长边 >4096 先降采样），保证 WebView 一定解得开
  3. 文件放 `cacheDir/picked/`，用随机 token 登记，回传
     `https://appassets.androidplatform.net/_picked/<token>`
  4. `nativeFiles.ts` fetch 该地址（`shouldInterceptRequest` 校验 token 后返回文件，读一次即删），
     校验字节数一致再构造 `File`，之后完全走 Web 校验和 Files API 上传
  5. 头像等 `<input accept="image/*">` 仍走旧链路：`onShowFileChooser` → `normalizePickedUri()`
     → `PickedFileProvider`（保留，未删）
- 下载（导出会话、备份记忆）走 `DownloadBridge`（`@JavascriptInterface`，分块 base64）。

---

## 5. 附件链路（Web 侧，按顺序）

```
聊天框按钮
  → 安卓：NativeFiles.pick → 原生内容 → 同源 /_picked/<token> → File
    桌面：继续 <input type=file> onChange
  → ChatInput.acceptFiles()            是否需要 API Key；空列表直接 return（静默）
  → drafts.addDraftFiles()
      → attachments.validateFiles()    扩展名（或 image/* 兜底）→ 大小 → 数量
      → 生成 DraftAttachment{id, file}，草稿里直接持有 File（预览就是 URL.createObjectURL(file)）
      → uploadAttachment()             → 内存队列，最多 3 并发
  → api/client.uploadFile()
      → 图片：resizeUploadImage(file)  用 Image 解码，长边 >4096 才缩放；
        解码失败不再中止，改为原样上传（记 `本机无法解码，改为原样上传 …`）
      → POST https://api.deepseek.com/anthropic/v1/files
        multipart/form-data，头 x-api-key + anthropic-beta: files-api-2025-04-14
      → 拿到 file_id
  → 发送时 content 里引用 file_id
```

失败会在卡片上显示「上传失败 / 可重试」，并写一条诊断日志。1.1.8 起 `uploadFile()` 会先记
`准备上传 …`；1.1.9 起原生读取会记 `原生文件已读取 … 头部 <前 12 字节>`，
解码失败记 `本机无法解码，改为原样上传 …`，读取失败记 `选择附件失败 …`/`读取附件失败 …`，
所以「日志为空」这条歧义路径已经补上。

---

## 6. 诊断日志（给手机端用）

`src/services/diagnostics.ts`：内存环形缓冲，最近 120 条。

- `request()` 里：非 2xx 记 `HTTP <状态码> <方法> <url> → 服务端原文`；fetch 抛错记 `网络失败 …`
- `uploadFile()` 里：记 `上传 <文件名> <MIME> <大小>KB`
- `main.tsx` 里：`window.onerror` / `unhandledrejection`

界面在 **设置 → 通用 → 诊断信息**：显示环境（版本、安卓、WebView、屏幕、Origin）+ 日志，
有「测试上传」（真传一张 8×8 PNG，验证 Files API）、「复制全部」、「清空」。
手机上装不了开发者工具，这个面板是唯一能拿到失败原因的通道。

**重要**：1.1.7 及以前有一条路径完全不写日志——文件在 WebView 里读不出来 →
`resizeUploadImage` 的 `image.decode()` 抛错 → 请求根本没发出。1.1.8 已给这一层补上日志
（`准备上传 …` / `读取或处理附件失败 …` / `原生文件已读取 …` / `选择附件失败 …`），
所以现在「卡片显示上传失败且日志为空」基本不应再出现；真遇到请先确认版本号。

---

## 7. 已知问题与已经试过的东西

### 7.1 聊天框发不了图片（1.1.9 已改原生链路 + 统一重编码，待真机复测）

现象（1.1.7 及以前）：在手机上点附件按钮、选一张照片，没有正常上传；附件卡片缩略图坏 + 重试图标；
诊断日志里没有任何记录。

1.1.7 及以前试过但没根治的：`setAllowContentAccess(true)`、`PickedFileProvider` 的
`query()`/文件名规范化、`normalizePickedUri` 落盘 + 文件头嗅探 + HEIC→JPEG、accept 追加
`image/*`、版本徽标与 `?v=` 破缓存。结论：只要还依赖 WebView 的 file chooser +
`content://`，就会受 accept 过滤、URI 权限、provider 三重变量影响，失败还可能完全静默。

**1.1.8（原生读取）**：聊天框附件按钮不再触发 `<input type=file>`，改成
`NativeFiles.pick(requestId)` → Java `ACTION_OPEN_DOCUMENT` → 单线程池读字节 →
`https://appassets.androidplatform.net/_picked/<token>` → 页面 fetch 成 `File`。
一次性甩掉 accept 过滤、content:// 权限、provider 三个变量；头像那条 `<input>` 链路原样保留。

**1.1.9（真机复测后的补丁）**：1.1.8 实测已经能读到原生文件（日志
`原生文件已读取 … 497547B`），但页面 `image.decode()` 报
`The source image cannot be decoded`，即「系统读得到字节、WebView 解不了码」，仍拿不到图。
所以：

- Java 侧 `normalizePickedImage()` 不再只对 HEIC 转码，**所有图片都经 BitmapFactory 重编码**
  （无透明 → JPEG q92，有透明 → PNG，长边 >4096 先降采样），页面拿到的必定是标准格式；
  系统也解不出时才退回原字节。
- Web 侧 `uploadFile()` 在解码失败时改为**原样上传**（只记 `本机无法解码，改为原样上传 …`），
  不再让本地解码失败阻断整个上传。
- 诊断日志追加文件头 12 字节（`头部 ff d8 ff e0 …`），便于判断「后缀是 jpg、内容不是」。

验证状态：类型检查、oxlint、rspack 构建、APK 编译签名均通过；**1.1.9 尚未在真机上复测**。
真机若仍失败，日志应能明确区分：原生读取失败（`选择附件失败 …` / `读取附件失败 …`）、
原生读取成功（`原生文件已读取 … 头部 …`）、解码失败（`本机无法解码，改为原样上传 …`）、
接口失败（`HTTP …` / `网络失败 …`）。

### 7.2 联网搜索不可用（1.1.8 已补提示词，待真机确认）

搜索是**同一个 `/anthropic/v1/messages` 请求**里的 server tool，工具名与请求格式对照官方文档
（https://api-docs.deepseek.com/guides/web_tools ）是正确的，安卓壳子与本功能无关。

1.1.7 的问题在于：工具虽然声明了，但 `system` 只有一句身份说明，模型没有被要求联网，
于是倾向凭训练知识回答（看起来就是「不会搜索」）。

1.1.8：`chat/capabilities.ts` 把「系统提示词 + 工具声明」放在一处，按每轮实际搜索开关生成：
开启时明确要求碰到「明确要搜索/查最新/核实」时必须用工具、引用真实来源、不编造链接，
并要求如实说明工具失败；关闭时明确告知不能搜索、提示用户开启，避免模型假装已搜。
`systems` 文案与 `deepseek_web_search` 声明永远同一份快照，续写/记忆工具回合不会漂移。

注意：`tool_choice` 不能强制点名 `deepseek_web_search`（会 400），搜索与否始终由模型决定；
提示词只能提高触发率，无法 100% 保证每次都搜。

排查建议（真机仍不搜时）：同一个 API Key 在桌面浏览器问一个需要搜索的问题，
桌面也不搜 → Key/账号能力或上游限流，与打包无关；桌面正常、手机不正常 → 看诊断日志里的
`HTTP`/`网络失败` 记录。

---

## 8. 发布流程（会用到的）

```powershell
# 1. 提交并推送（github.com 直连被墙，需要本地 CONNECT 代理把 github.com 指到可达边缘 IP）
git -c http.proxy=http://127.0.0.1:8899 push origin feat/memory-rag-usage
git -c http.proxy=http://127.0.0.1:8899 push origin feat/memory-rag-usage:main

# 2. 发 Release（api.github.com 可直连；资源走 uploads.github.com）
pwsh -File D:\Testdsh\_work\release-1.1.7.ps1
```

代理脚本：`D:\Testdsh\_work\github-proxy.mjs`（把 `github.com` 映射到可达 IP，端口 8899）。
当前可达的 GitHub 边缘 IP 会变，用 `Test-NetConnection <ip> -Port 443` 试出来再改。

---

## 9. 别动的东西

- 包名 `com.teawhale.chatdemo` 和签名（换一个就等于所有用户数据清零）。
- IndexedDB 库名 / 版本号（`chat-demo` v11、`chat-demo-memory` v1），以及照片上传所用的
  `files-api-2025-04-14` beta 头。
- `LICENSE` / `NOTICE`：上游是 MIT，fork 必须保留版权与许可声明。
- `SAFETY_CHECK = { level: 'low' }`（用户明确要求下调的；若要公开分发，建议改回 `high`）。
