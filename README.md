# ZZZ Achievement Tracker

自用安卓原生成就追踪 App，目标站点是 `https://stardb.gg/zh-cn/zzz/achievement-tracker`。

## 已实现

- Kotlin + Jetpack Compose + Material 3。
- 跟随系统自动切换深色/亮色模式。
- 登录页，账号密码通过 Android Keystore / EncryptedSharedPreferences 加密保存在本机。
- Room 本地缓存成就列表，启动后优先展示缓存。
- DataStore 保存搜索、分类、完成状态、排序和列表位置。
- 成就列表完整展示名称、描述、分类、版本、奖励、完成状态和同步状态。
- 成就详情弹窗。
- 勾选/取消勾选先本地更新，接口失败时保留待同步状态，后续刷新会重试。

## 自动构建与更新

- `.github/workflows/release.yml`：每次推送 `main` 或手动触发（Actions → Build & Release → Run workflow）时编译签名的 release APK，并发布到 Releases，tag 为 `v1.1.<构建号>`（构建号 = `GITHUB_RUN_NUMBER`），更新说明为与上一个 tag 之间的 commit 列表。PR 只编译，APK 作为 Actions artifact 上传。
- 签名读取仓库 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。`app/build.gradle.kts` 从环境变量 `KEYSTORE_FILE` 等读取，本地没有这些变量时 release 构建使用 debug 签名。
- 版本号：CI 构建 `versionCode = 100 + 构建号`，`versionName = 1.1.<构建号>`；本地构建保持 `versionCode = 2`、`versionName = 1.1`、构建号 0。
- App 启动时每天最多自动检查一次 GitHub 最新 Release（失败静默），设置页可手动“检查更新”。下载后通过 FileProvider 调起系统安装；首次需要允许“安装未知应用”。
- 只有使用同一签名证书的 APK 才能覆盖安装；本地 debug 签名的版本需要先卸载再安装 Release 版本。

## 游戏成就扫描

设置 → 游戏成就扫描：自动遍历游戏成就页，识别每条成就的完成状态，与本地数据对比后确认同步到 stardb。

1. 开启无障碍服务「绝区零成就 · 自动扫描」（用于自动点击分类和滑动列表，并显示悬浮窗）。侧载安装的 App 在 Android 13+ 上需先到 应用信息 → ⋮ → 允许受限制的设置。
2. 点「授权屏幕录制」，然后切到游戏的成就页，点悬浮窗的「开始」。扫描期间不要触碰屏幕。
3. 完成后点悬浮窗的「查看」，在 App 里勾选要同步的差异。默认只勾选「游戏已完成、本地未完成」的项目。

实现：`scan/` 目录。MediaProjection 截屏 → ML Kit 中文 OCR（模型打包在 APK 内）→ `GameScreenParser` 按版面解析出页签、侧栏分类和成就行（右侧显示日期 = 已完成，显示 x/y = 未完成）→ `AchievementMatcher` 去掉标点后按编辑距离匹配本地成就名。开启「保存调试数据」后，每一屏的截图和识别文字会保存在 `Android/data/com.local.stardbzzz/files/scan-debug/`，TSV 格式与 `app/src/test/resources/ocr/` 下的测试数据相同。

注意：自动操作游戏界面属于游戏用户协议禁止的行为，使用风险自负。

## 已从 HAR 确认的接口

- 登录：`POST https://stardb.gg/zh-cn/login`
  - `Content-Type: application/x-www-form-urlencoded`
  - Header: `x-sveltekit-action: true`
  - Body: `username=...&password=...`
- 成就列表：`GET https://stardb.gg/zh-cn/zzz/achievement-tracker/__data.json?x-sveltekit-invalidated=01`
  - 返回 SvelteKit 的扁平 data JSON。
  - 当前通过 `SvelteKitDataParser.kt` 还原数字引用并解析 `load.series[].achievement_groups[]`。
- 勾选完成：`PUT https://stardb.gg/api/users/me/zzz/achievements/completed/{achievementId}`
- 取消完成：`DELETE https://stardb.gg/api/users/me/zzz/achievements/completed/{achievementId}`

## 仍需确认

当前 HAR 只有刷新页面的 GET 请求，没有包含勾选动作。前端 bundle 已确认同步 URL 和方法，但最好再导出一次“勾选一个成就、取消勾选一个成就”后的 HAR，用来确认状态码、Cookie 要求和失败响应格式。

## 打开方式

用 Android Studio 直接打开当前目录：

```text
C:\OneDrive New\OneDrive\Desktop\安卓app
```

首次同步 Gradle 时会下载 Android Gradle Plugin、Compose、Room、Retrofit 等依赖。当前命令行环境没有 Gradle/Android SDK，所以本地未执行编译验证。
