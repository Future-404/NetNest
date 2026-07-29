# NetNest UI 重构与开发日志 (Development Log)

> **重构日期**：2026-07-30
> **核心风格**：磨砂玻璃风 (Glassmorphism Style)
> **项目路径**：`/www/NetNest`

---

## 📖 1. 项目重构目标

根据用户需求，对 NetNest Android PWA 壳应用进行整体 UI 重构，将原有的基础 Material Design 界面全面升级为现代、轻质感、极具视觉冲击力的**磨砂玻璃风 (Glassmorphism)** 视觉体系。

---

## 🎨 2. 设计系统与 Theme Tokens 架构

### 2.1 配色与主题 (`Color.kt`, `Theme.kt`)
- **深色模式 (Obsidian Glass)**：
  - 基础背景：黑绀色 (`#0B0F19`)
  - 透光容器：`#331E293B` (Slate 800) / `#2D0F172A` (Slate 900)
  - 霓虹点缀：青色 (`#38BDF8`)、靛蓝 (`#818CF8`)、翡翠绿 (`#34D399`)
- **浅色模式 (Glacier Glass)**：
  - 基础背景：冰川灰 (`#F1F5F9`)
  - 柔和白透光卡片与高对比度文字
- **沉浸式适配**：禁用默认动态配色，支持全屏 Edge-to-Edge 透明状态栏与导航栏。

### 2.2 Glass修饰符 (`Glassmorphism.kt`)
- **`Modifier.glassmorphic(...)`**：
  - 双层线性渐变透光底色
  - 顶部高光边框 (Specular Highlight Border: `0x55FFFFFF`)
  - 柔和 3D 环境与 Spot 阴影 (`shadow`)
- **`Modifier.glassmorphicCard(...)`**：
  - 适用于设置卡片与列表的微玻璃底板

---

## ✨ 3. 核心功能与 UI 模块改造

### 3.1 主页桌面 (`HomeScreen.kt`)
1. **Glass 顶栏**：
   - 顶部区域加入“NetNest PRO”霓虹徽章与应用数量统计。
2. **Squircle 连续曲率 Glass 图标**：
   - 桌面图标升级为 `22.dp` Squircle 连续曲率玻璃卡片。
   - 配合长按拖拽时的 `1.10f` 缩放动画与 Haptic 反馈。
3. **系统应用卡片化 (删除 FAB)**：
   - 移除右下角 FloatingActionButton。
   - 将 **“添加应用”** 升级为与 **“设置”** 平级的桌面系统应用卡片 (`AddAppGridItem`)，支持网格拖拽排序。
4. **系统应用自定义图标与半透明默认样式**：
   - 长按“设置”或“添加应用”图标弹出 Glass 悬浮菜单，支持选择本地图片自定义图标，以及一键恢复默认。
   - 默认图标去除原实色青色色块，采用纯净半透明玻璃底座与 `onSurface` 高对比度图标。

### 3.2 抽屉与设置页 (`PwaSwitcherOverlay.kt`, `SettingsScreen.kt`)
- **PWA 切换抽屉**：
  - 侧边抽屉重构为 `120.dp` 宽度的毛玻璃面板，结合 `0.35f` 黑色半透明遮罩。
- **全局设置页**：
  - 将外观、应用、网络等设置分块升级为 `glassmorphicCard` 容器。

---

## 🐛 4. 关键 Bug 修复

### 4.1 PWA 删除后图标残留 Bug 修复
- **现象**：在主页删除 PWA 应用后，桌面图标不会立即消失，重启应用后才消失。
- **根因分析**：
  - Room 原有的 `@Delete` 已按主键 `id` 删除记录，数据库删除并不是图标残留的根因。
  - 主页额外维护了用于拖拽排序的 `displayedItems` 本地列表；删除请求发出后，该列表没有立即同步，因此数据库 Flow 更新到达前仍会短暂显示旧图标。
- **解决方案**：
  1. 在 `PwaDao.kt` 中保留语义明确的按主键删除方法：
     ```kotlin
     @Query("DELETE FROM pwas WHERE id = :id")
     suspend fun deleteById(id: Long)
     ```
  2. 在 `MainViewModel.kt` 中调用 `pwaDao.deleteById(pwa.id)`。
  3. 在 `HomeScreen.kt` 删除确认按钮中即时过滤 `displayedItems`，等待 Room Flow 与本地状态收敛。

---

## 🛠️ 5. 构建与验证

使用以下命令进行编译测试，全量增量构建均无警告通过：
```bash
./gradlew :app:assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`

---

> **日志记录完毕，NetNest UI 重构与修复开发工作已全部结束。**
