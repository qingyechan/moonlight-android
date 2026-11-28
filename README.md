# Moonlight Bridge

**An Enhanced Fork of Moonlight Android for Tablet Productivity & Remote Desktop Streaming**  
**面向 Android 平板远程办公与高分屏串流的 Moonlight 增强分支版本**

[English](#english-documentation) · [简体中文](#简体中文文档)

---

> **Fork & License Compliance Statement / 分支声明与开源协议合规说明**  
> This repository is an independent, unofficial modification derived from [**moonlight-stream/moonlight-android**](https://github.com/moonlight-stream/moonlight-android) (`v12.2`), distributed under the **GNU General Public License v3.0 (GPL-3.0)** (see [LICENSE.txt](LICENSE.txt)).  
> In strict compliance with the upstream maintainers' notice in `app/build.gradle`, the application package identifier has been changed to **`com.limelight.bridge`** (`Moonlight Bridge`). It does not impersonate official releases, can coexist alongside official Moonlight (`com.limelight`), and does not submit crash telemetry to upstream Google Play accounts.
>
> 本项目是基于 [**moonlight-stream/moonlight-android**](https://github.com/moonlight-stream/moonlight-android)（`v12.2`）构建的独立第三方开源分支，严格遵循 **GNU GPL-3.0** 协议发布（详见 [LICENSE.txt](LICENSE.txt)）。  
> 为遵守上游开发者在 `app/build.gradle` 中的声明，本项目已将包名修改为独立的 **`com.limelight.bridge`**（应用名称为 **Moonlight Bridge**），可与官方版客户端（`com.limelight`）共存安装，互不冲突，亦不会干扰官方应用商店的崩溃统计体系。

---

<a id="english-documentation"></a>
## English Documentation

### Overview & Purpose

While official Moonlight Android delivers low-latency game streaming, using Android tablets with external physical keyboards for remote desktop productivity often suffers from OS-level shortcut interception (`Alt+Tab`, `Win` key combinations, `Ctrl+Space`, function keys) and missing 16:10 display presets.

**Moonlight Bridge** addresses these limitations while preserving upstream streaming performance and host compatibility with [Sunshine](https://github.com/LizardByte/Sunshine) and NVIDIA GameStream.

### Key Enhancements Over Upstream (`v12.2`)

| Module | Summary of Changes |
| :--- | :--- |
| **1. System Shortcut Bridge (`StreamKeyboardService`)** | Integrates a dedicated Android `AccessibilityService` (`canRequestFilterKeyEvents`) that intercepts hardware keyboard events **strictly while a streaming session is connected and focused**, without altering normal input behavior in other Android apps. |
| **2. Full Desktop Shortcut Passthrough** | Forwards `Alt+Tab`, `Win` / `Win+*` (`Win+R/D/E/V/L/Shift+S`, etc.), `Ctrl+*` (`Ctrl+Space`, `Ctrl+Shift+Esc`, `Ctrl+Alt+Del`), `Shift+Space`, `Esc`, `F1`–`F24`, `PrintScreen`, and lock keys (`Caps/Num/Scroll Lock`) directly to the host OS, preventing interception by Android or OEM ROMs (verified on Lenovo ZUI / Android 16). |
| **3. 16:10 Tablet Resolution Presets** | Adds native `1280x800`, `1920x1200`, and `2560x1600` presets in Display Settings to fit modern 16:10 tablets without letterbox distortion. |
| **4. Modernized Dark UI & Quick Controls** | Refreshes `PcView`, `AppView`, and `Game` with translucent dark widescreen cards, custom dark dialog styling, per-PC cover modes (default icon / custom image / auto-captured live stream preview), custom wallpaper & dimness control, UUID-keyed local PC display aliases, per-PC custom quick-launch shortcuts (`Win+R`), and a fixed top-right trigger that opens a translucent floating right sidebar (one-tap tablet clipboard UTF-8 paste to PC, live Perf HUD toggle, common shortcut keys, and stream disconnect) without resizing the video surface. |
| **5. In-App Updater (`AppUpdateManager`)** | Built-in GitHub Releases update checker that automatically probes direct and mirror routes concurrently in the background to pick the fastest available path, with cancel support and SHA-256 verification prior to launching the system package installer. |
| **6. Coexistence & Isolation** | Uses package ID `com.limelight.bridge` and a dedicated `FileProvider` authority (`update.com.limelight.bridge`), allowing seamless side-by-side installation with official Moonlight. |

### Installation & Usage

1. Download `Moonlight-KeyboardBridge-release.apk` from [Releases](../../releases) and install it on your Android device (Android 5.0+, API 21+).
2. Launch **Moonlight Bridge**, tap the **Keyboard Bridge status pill** at the top of the home screen (or at the top of **Settings**), and enable **Stream Keyboard Bridge** in Android Settings → Accessibility.
3. Pair with your Sunshine / GameStream host, choose a suitable resolution (e.g., `2560x1600`), and start streaming.
4. *(Optional)* Use the update button in the main bar or **Settings → Check for App Updates** to upgrade future builds directly in-app while retaining existing host pairings and preferences.

### Building from Source

- Install Android Studio / Android SDK and NDK (`29.0.14206865` or compatible).
- Initialize submodules: `git submodule update --init --recursive`.
- Configure `local.properties` with `sdk.dir` / `ndk.dir`.
- Build release APK via Gradle: `./gradlew assembleNonRootRelease` *(configure signing via environment/properties if publishing releases)*.

---

<a id="简体中文文档"></a>
## 简体中文文档

### 项目定位与用途

官方版 Moonlight Android 在低延迟串流方面表现优异，但在 Android 平板搭配实体键盘进行远程桌面与日常办公时，常面临系统/厂商 ROM 截获快捷键（如 `Alt+Tab`、`Win` 组合键、`Ctrl+Space`、`F1`–`F12` 等），以及缺少常见 16:10 平板分辨率预设等问题。

**Moonlight Bridge** 在完整保留官方串流内核与 [Sunshine](https://github.com/LizardByte/Sunshine) / GameStream 兼容性的基础上，针对平板外接键盘办公与高分屏场景进行了系统级适配与界面重构。

### 相对官方版（`v12.2`）的主要改动

| 模块 | 改动说明 |
| :--- | :--- |
| **1. 物理键盘系统快捷键桥接 (`StreamKeyboardService`)** | 内置独立的无障碍服务（`AccessibilityService` + `canRequestFilterKeyEvents`），**仅在串流连接成功且前台聚焦期间激活拦截**，退出串流或切换至其他应用后立即交还系统控制权，不影响日常正常使用。 |
| **2. 完整桌面级按键透传** | 支持将 `Alt+Tab`、`Win` 及 `Win+*` 组合键（如 `Win+R/D/E/V/L/Shift+S`）、`Ctrl+*`（如 `Ctrl+Space`、`Ctrl+Shift+Esc`、`Ctrl+Alt+Del`）、`Shift+Space`、`Esc`、`F1`–`F24`、`PrintScreen` 及指示灯锁定键（`Caps/Num/Scroll Lock`）完整透传至被控端 PC（已在联想 ZUI / Android 16 实机验证通过）。 |
| **3. 16:10 平板分辨率预设** | 在显示设置中新增 `1280x800`、`1920x1200` 与 `2560x1600` 分辨率选项，更好适配主流 16:10 比例平板屏幕。 |
| **4. 深色现代界面重构与快捷功能** | 重构主机列表（`PcView`）与应用列表（`AppView`）为宽屏深色半透明卡片与深色弹窗主题，支持**默认图标 / 自定义封面 / 串流屏幕实时缩略图**三种卡片展示模式、自定义背景图片与遮罩调节、仅本地生效的设备备注名（基于主机 UUID 存储）、应用列表内置的**自定义程序快捷启动**（点击确认后进入桌面并自动通过 `Win+R` 运行指定命令或终端），以及串流画面右上角固定入口展开的**半透明浮层快捷侧边栏**（不挤占画面，支持一键发送剪贴板文本至电脑、实时开关性能监控 HUD、常用系统组合键与断开串流）。 |
| **5. 应用内版本更新检查 (`AppUpdateManager`)** | 内置基于 GitHub Releases 的更新检查模块，后台自动对 GitHub 直连与加速线路并发测速择优（无需手动配置参数），支持中途取消下载与 SHA-256 完整性校验，一键拉起系统安装器覆盖升级。 |
| **6. 独立包名共存** | 采用独立包名 `com.limelight.bridge` 及独立安装权限配置，可与官方原版 Moonlight 同时安装使用。 |

### 安装与使用指南

1. 前往 [Releases](../../releases) 页面下载最新版本的 `Moonlight-KeyboardBridge-release.apk` 并安装（支持 Android 5.0 及以上系统）。
2. 打开 **Moonlight Bridge**，点击主页或「设置」顶部的**键盘桥接状态提示条**，在系统「无障碍」设置中开启 **Stream Keyboard Bridge** 权限。
3. 与电脑端的 Sunshine 或 GameStream 完成配对后，按需选择分辨率（如 `2560x1600`）即可开始串流。
4. 后续发布新版本时，可直接在主界面右上角/侧边栏或「设置 → 检查应用更新」中在线检查并覆盖升级，已配对主机与个人设置均会自动保留。

---

## Upstream & Acknowledgements / 上游项目与致谢

- Upstream project: [**moonlight-stream/moonlight-android**](https://github.com/moonlight-stream/moonlight-android) — Original authors: [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer), [Andrew Hennessy](https://github.com/yetanothername).
- Accessibility key-filtering inspiration: [`lyc8503/moonlight-android-no-keyboard-shortcut`](https://github.com/lyc8503/moonlight-android-no-keyboard-shortcut).
- License: **GNU General Public License v3.0** — see [LICENSE.txt](LICENSE.txt).
