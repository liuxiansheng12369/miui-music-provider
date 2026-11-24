# miui-music-provider

小米音乐（澎湃 OS 自带 `com.miui.player`）的「词幕 / Lyricon」歌词源，从聚合版拆出的独立出包模块。

## 取词方式（聚合式）

- Hook `MediaSession.setMetadata`，取 `METADATA_KEY_MEDIA_ID`（小米音乐给的是 QQ 音乐风格 songmid）
- songmid 走 QQ 音乐歌词接口，拿原文 / 翻译 / 罗马音
- songmid 取不到时，用「歌名 + 歌手」搜 musicid，再 QrcDownloader 兜底
- 中文歌名从 `ARTIST` 的「歌名-歌手」里拆

## 与聚合版的差别（本次修正点）

| 项 | 聚合版 app | 本独立模块 |
|---|---|---|
| 宿主 | `liuxiansheng.lyricon.provider`（20 个源打一包） | `io.github.proify.lyricon.miuiprovider`（只含小米音乐） |
| SystemUI 重启重连 | 依赖 `CentralBootReconnect` + `KeepSessionAlive` + `B1ConfigProvider` | 自带 `MiuiBootReconnect`，按播放状态判断，不再依赖 B1 配置与 su |
| 源图标 | `Constants.ICON` 为空，词幕商店显示空白 | 补了小米音乐风格 SVG（橙底白音符） |
| 打包 | 混在聚合包里，无法单独安装/上架 | 独立 applicationId，可单独出包 |

## 构建

依赖整工程的 `share:*` 子模块（extensions-android / lrckit / qrckit），需放在 LyricProvider 完整源码工程下，并在 `settings.gradle.kts` 加一行：

```kotlin
include(":miui-music")
```

```bash
./gradlew :miui-music:assembleDebug
# 产物 build/all-apks/debug/miui-music-1.0.0-debug.apk
```

## 使用

1. LSPosed 启用本模块，作用域勾「小米音乐」
2. 词幕的歌词源列表里选「小米音乐」
3. 日志 tag `MiuiMusicProvider` 看取词过程

## 版本

1.0.0 / versionCode 1（2026-11-24）
