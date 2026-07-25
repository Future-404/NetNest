# NetNest 数据持久化分析报告

为了评估 NetNest 应用是否会像普通浏览器那样在某些情况下（如清理缓存、重启等）丢失网页数据，我们对克隆下来的代码库进行了深入的架构与代码分析。

---

## 📊 数据持久化整体架构

在 NetNest 中，数据持久化主要分为两个维度：**应用级元数据持久化** 与 **网页级数据持久化**。

```mermaid
graph TD
    A[NetNest 数据存储] --> B[应用级数据 (本地 SQLite)]
    A --> C[网页级数据 (WebView 内核管理)]
    
    B --> B1[PWA 列表/配置]
    B --> B2[用户脚本 UserScripts]
    B --> B3[脚本专属存储 ScriptStorage]
    
    C --> C1[LocalStorage / SessionStorage]
    C --> C2[IndexedDB / Web SQL]
    C --> C3[Cookies (本地磁盘持久化)]
```

---

## 1. 应用级元数据持久化 (App-Level Persistence)

NetNest 在应用层通过 Android 的 **Room Database**（底座为 SQLite 数据库）来管理本地数据。这部分数据被永久存储在 Android 系统的 `/data/data/com.pwa.shell/databases/` 目录下。

### 核心组件及文件关联
* **数据库实例**：[AppDatabase.kt](file:///www/NetNest/app/src/main/java/com/pwa/shell/data/local/AppDatabase.kt) 定义了 `PwaEntity`（PWA 实体数据）、`UserScriptEntity`（用户脚本定义）和 `ScriptStorageEntity`（脚本本地 KV 存储）。
* **用户脚本专属 KV 存储**：[ScriptStorageDao.kt](file:///www/NetNest/app/src/main/java/com/pwa/shell/data/local/ScriptStorageDao.kt) 提供类似于 Tampermonkey 的 `GM_setValue` 和 `GM_getValue` 的持久化支持，数据最终存入 SQLite。
* **本地图片缓存**：PWA 的高分辨率图标在自动提取后会被下载并保存为本地文件。其路径记录在 `PwaEntity.iconPath` 中。

> [!NOTE]  
> **应用级数据稳定性**：应用级的数据（包括添加的网站列表、导入的油猴脚本、脚本通过 `GM_setValue` 存储的值）是**完全永久存储**的。除非用户主动卸载应用，或在系统设置中执行“清除所有数据（Clear Data）”，否则绝不会丢失。

---

## 2. 网页级数据持久化 (Webpage-Level Persistence)

网页数据（如用户登录状态 Token、Cookies、网站的本地偏好设置 LocalStorage、本地数据库 IndexedDB 等）由 Android 的 WebView（即 Chromium 内核）统一管理。

NetNest 在配置 WebView时，通过 [configureSettings](file:///www/NetNest/app/src/main/java/com/pwa/shell/ui/PwaWebViewScreen.kt#L515-L540) 方法显式开启了所有核心的 Web 存储功能：

```kotlin
// 摘自 PwaWebViewScreen.kt -> configureSettings
javaScriptEnabled = true
domStorageEnabled = true    // 启用 LocalStorage 和 SessionStorage
databaseEnabled = true       // 启用 Web SQL / HTML5 Database 支持
cacheMode = WebSettings.LOAD_DEFAULT // 启用标准的 HTTP 协议缓存
```

### Cookies 持久化机制
WebView 默认在内存中管理 Cookie，如果应用崩溃或被系统后台强杀，部分未落盘的 Cookie 可能会丢失。为了解决这个问题，[PwaWebViewScreen.kt](file:///www/NetNest/app/src/main/java/com/pwa/shell/ui/PwaWebViewScreen.kt) 实施了以下保障措施：
1. **允许 Cookie 写入**：
   ```kotlin
   CookieManager.getInstance().setAcceptCookie(true)
   CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
   ```
2. **页面加载完成后强制落盘**：在 `WebViewClient` 的 `onPageFinished` 中强制执行 Cookie 刷盘，以确保登录状态即使在断电或闪退时也能保存：
   ```kotlin
   override fun onPageFinished(view: WebView?, url: String?) {
       super.onPageFinished(view, url)
       CookieManager.getInstance().flush() // 立即同步内存 Cookie 到本地磁盘
   }
   ```

> [!TIP]  
> **网页级数据稳定性**：开启了 `domStorageEnabled`、`databaseEnabled` 并执行了 `CookieManager.getInstance().flush()` 之后，网页数据（包括登录状态、LocalStorage 缓存）是**会被持久化到磁盘**的。它**不会像无痕浏览器那样在关闭应用时清空**，其持久化表现与手机 Chrome / Safari 浏览器一致。

---

## 3. ⚠️ 关键特性与潜在的隐患（与标准浏览器的差异）

虽然 NetNest 具有良好的持久化行为，但在架构上存在以下与标准浏览器不同的特性：

### ① 删除 PWA 网站时“无法彻底清除网页缓存”
在 [MainViewModel.kt](file:///www/NetNest/app/src/main/java/com/pwa/shell/ui/MainViewModel.kt) 的 [deletePwa](file:///www/NetNest/app/src/main/java/com/pwa/shell/ui/MainViewModel.kt#L85-L96) 方法中：
```kotlin
fun deletePwa(pwa: PwaEntity) {
    viewModelScope.launch {
        // 仅删除了下载的本地图标文件
        if (pwa.iconPath.isNotEmpty()) {
            val file = File(pwa.iconPath)
            if (file.exists()) { file.delete() }
        }
        pwaDao.delete(pwa) // 仅删除了 SQLite 中的 PWA 列表记录
    }
}
```
**隐患分析**：
NetNest 在删除某个 PWA 网站时，**没有**调用 WebView 相关的清理接口（如 `WebStorage.getInstance().deleteAllData()` 或特定域名的 Cookie 清除）。
这意味着，如果你在 NetNest 中删除了一个 PWA 网站（例如 `https://example.com`），虽然它在主页图标列表中消失了，但它在 WebView 内部留下的 **Cookies、LocalStorage、IndexedDB 依然残留在手机本地**。如果以后重新添加该网址，系统依然会读到旧的数据，你可能会发现自己依旧是“已登录”状态。

### ② 没有独立的容器隔离（Profile Isolation）
* **现状**：NetNest 的所有 PWA 都是运行在同一个应用的同一个默认 WebView 存储 Profile 下。
* **隔离级别**：主要依赖 Chromium 引擎标准的**同源策略（Same-Origin Policy, SOP）**。即 `https://a.com` 的前端 JavaScript 无法访问 `https://b.com` 的 LocalStorage。
* **局限性**：由于没有做物理容器隔离（不同 Profile），它们共享同一个 Cookie 管理器和存储池。

---

## 📌 总结与结论

| 数据类型 | 存储位置 | 持久化情况 | 是否会丢失？ |
| :--- | :--- | :--- | :--- |
| **主页网站列表 / 配置** | Room SQLite 数据库 | 永久本地保存 | ❌ **不会丢失**（除非卸载或清除应用数据） |
| **油猴脚本内容 & 脚本数据** | Room SQLite 数据库 | 永久本地保存 | ❌ **不会丢失** |
| **网页 Cookies / 登录状态** | WebView 系统存储 (CookieManager) | 自动落盘持久化 | ❌ **不会丢失**（除非手动去系统清理应用缓存） |
| **LocalStorage / IndexedDB** | WebView 系统存储 | 磁盘级持久化 | ❌ **不会丢失** |

**最终结论**：
**NetNest 不会像无痕浏览器一样自动丢失网页数据**。它开启了完整的 DOM 存储和 Cookie 同步刷盘机制，网页数据和登录状态的持久性**与手机系统浏览器（Chrome 等）完全一致**。

但是它在“彻底删除网站”时，并不会顺便抹除该域名底下的 Cookie 与 LocalStorage，这会导致残留数据一直占用手机空间（除非去 Android 系统设置里清理整个应用的存储数据）。
