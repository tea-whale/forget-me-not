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
| 当前提交 | `990afdb fix(android): accept image/* and sniff real image format; add diagnostics panel` |
| 版本 | `package.json` → `1.1.7`，`android/build-apk.ps1` → `$VersionCode = '9'` |
| 远程 | `origin` = https://github.com/tea-whale/forget-me-not.git ，`upstream` = deepseek-ai/chat-demo |
| 线上包 | https://github.com/tea-whale/forget-me-not/releases/tag/v1.1.7 |
| 桌面 APK | `C:\Users\Windows\OneDrive\Desktop\Forget Me Not v1.1.7.apk`（桌面被 OneDrive 接管） |

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
    chat/store.ts             会话状态机，SYSTEM_PROMPT、SAFETY_CHECK、记忆注入、请求组装
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
- 文件选择链路（**当前出问题的地方**）：
  1. 页面 `<input type=file accept=".png,.jpg,...,image/*">` → `onShowFileChooser`
  2. `pickerIntent()` 用 `ACTION_OPEN_DOCUMENT`；accept 里**带 `*` 的通配类型不参与选择器过滤**
     （否则聊天框的附件按钮会变成只能选图片）
  3. `onActivityResult` → `normalizePickedUri()`：把选中项复制进 `cacheDir/picked/`，
     用 `sniffImageExt()` 读文件头判断真实格式（jpg/png/gif/webp 原样留用，其余转 JPEG），
     最后返回 `content://com.teawhale.chatdemo.picked/<文件名>`
  4. `PickedFileProvider` 提供 `openFile` / `getType` / `query`（`DISPLAY_NAME`、`SIZE`）
- 下载（导出会话、备份记忆）走 `DownloadBridge`（`@JavascriptInterface`，分块 base64）。

---

## 5. 附件链路（Web 侧，按顺序）

```
<input type=file> onChange
  → ChatInput.acceptFiles()            是否需要 API Key；空列表直接 return（静默）
  → drafts.addDraftFiles()
      → attachments.validateFiles()    扩展名（或 image/* 兜底）→ 大小 → 数量
      → 生成 DraftAttachment{id, file}，草稿里直接持有 File（预览就是 URL.createObjectURL(file)）
      → uploadAttachment()             → 内存队列，最多 3 并发
  → api/client.uploadFile()
      → 图片：resizeUploadImage(file)  用 Image 解码，长边 >4096 才缩放（解码失败会直接抛）
      → POST https://api.deepseek.com/anthropic/v1/files
        multipart/form-data，头 x-api-key + anthropic-beta: files-api-2025-04-14
      → 拿到 file_id
  → 发送时 content 里引用 file_id
```

失败会在卡片上显示「上传失败 / 可重试」，并写一条诊断日志。

---

## 6. 诊断日志（给手机端用）

`src/services/diagnostics.ts`：内存环形缓冲，最近 120 条。

- `request()` 里：非 2xx 记 `HTTP <状态码> <方法> <url> → 服务端原文`；fetch 抛错记 `网络失败 …`
- `uploadFile()` 里：记 `上传 <文件名> <MIME> <大小>KB`
- `main.tsx` 里：`window.onerror` / `unhandledrejection`

界面在 **设置 → 通用 → 诊断信息**：显示环境（版本、安卓、WebView、屏幕、Origin）+ 日志，
有「测试上传」（真传一张 8×8 PNG，验证 Files API）、「复制全部」、「清空」。
手机上装不了开发者工具，这个面板是唯一能拿到失败原因的通道。

**重要**：不是所有失败都会留下日志。下面这条路径完全不写日志：
文件在 WebView 里读不出来 → `resizeUploadImage` 的 `image.decode()` 抛错 → 请求根本没发出。
所以「卡片上显示上传失败，但诊断日志空的」，基本可以判定为**文件内容/读取有问题，而不是网络或接口问题**。

---

## 7. 已知问题与已经试过的东西

### 7.1 聊天框发不了图片（未解决）

现象：在手机上点附件按钮、选一张照片，没有正常上传；早期截图里附件卡片缩略图是坏的 + 重试图标；
诊断日志里没有任何记录（最近反馈）。

已经改动过的（都在当前代码里）：

1. `setAllowContentAccess(true)`——保留，但不是根因。
2. 新增 `PickedFileProvider` + `query()`（`DISPLAY_NAME`/`SIZE`）+ 文件名规范化，防止页面拿到空文件。
3. `normalizePickedUri` 改为先落盘再按文件头判断格式，HEIC 等转 JPEG。
4. 页面 accept 列表追加 `image/*`：WebView 会拿选择结果和 accept 比对，不匹配会**静默丢弃**，
   而安卓相册常给出 `image/heic` 这类不在扩展名白名单里的类型；`validateFiles()` 同步允许
   `image/*` 的 MIME 兜底。
5. 版本徽标、`?v=` 参数、`no-store`，排除装错包 / 旧页面缓存。

**10 秒分流（最有价值的一步）**：在同一个 App 里用**同一张相册照片**做两件事——

- 点首页头像换图（`<input accept="image/*">`，不走 Files API，只做 canvas 解码）
- 点聊天框附件按钮选图（`accept` 里是扩展名 + `image/*`，要走 Files API）

头像能换、附件不行 → 卡在 accept 过滤 / 上传请求；两个都不行 → 卡在文件读取（provider / WebView）。
这个结论直接决定往哪边走，比继续盲改代码有用。

**下一个排查者请注意**：先确认手机上跑的是哪个版本（设置 → 通用 底部「版本 x.y.z」），
这一步能排掉一半可能。然后两条路：

- A. 有电脑能连 USB：`adb logcat -s ChatDemo` 看 `Cannot normalize picked file` /
  `Cannot transcode picked image` 这类 Java 日志，以及选完文件后 WebView 有没有读到。
- B. 彻底绕开 WebView 的 file chooser：给页面加一个 `@JavascriptInterface`，
  附件按钮点下去走原生选文件，Java 直接把字节读出来（base64 或写进一个可 fetch 的
  自定义 scheme），再 `evaluateJavascript` 造一个 `File` 塞给页面。
  这条路能一次性甩掉 accept 过滤、content:// 权限、provider 三个变量，建议优先考虑。

### 7.2 联网搜索不可用（未解决）

搜索是**同一个 `/anthropic/v1/messages` 请求**里的 server tool：
`store.ts` 的 `serverTools(search)` 会追加 `{ type: 'deepseek_web_search' }`。
所以它不涉及安卓壳子，也不涉及任何新接口。

排查建议：拿同一个 API Key 在桌面浏览器打开 `dist/` 的构建（或线上 Pages/本地 `node` 静态服务），
打开「智能搜索」问一个需要搜索的问题：

- 桌面也一样不搜索 → 是 Key / 账号能力或上游限流的问题，跟打包无关；
- 桌面正常、手机上不正常 → 再看手机上的诊断日志（会有 `HTTP`/`网络失败` 记录）和模型选择。

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
