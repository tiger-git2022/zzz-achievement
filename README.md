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
