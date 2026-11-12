# 不能说的秘密 · Android

独立运行的 Kotlin / Android 原生 App：链接下载、本地视频库、视频转 GIF 与实况照片。最低 Android 7.0，本版仅提供 `arm64-v8a`。

本仓库只包含手机版。安装包见 [Releases](https://github.com/jin673005085-lab/secret-downloader-android/releases)。当前版本 **0.1.9**，包名 `cn.secret.downloader`。

## 功能

- 下载：粘贴链接或分享文字，支持抖音、哔哩哔哩、YouTube、X / Twitter、TikTok、Pornhub；使用原生解析、系统 WebView 和 yt-dlp。抖音循环动图 / 实况保存为实况照片，X 动图保存为 GIF。
- 转换：GIF 支持裁剪、帧率、宽度、画质、速度与循环，最长 15 秒；实况保留完整视频及声音，不限制时长，封面可选视频帧或相册图片。
- 视频库仅收录普通视频；转换作品库仅收录 GIF 与实况。支持搜索、筛选、排序、收藏、多选、全选及删除确认。
- Media3 本地播放、手势、续播、全屏与画中画；实况自动播一次、按钮重播、长按播放与松开回封面。
- 统一任务中心、进度、暂停 / 继续 / 重试 / 取消、通知与 SQLite 持久化；进程重启后未完成任务显示为已中断。
- 浅深色主题、弹簧动画及系统关闭动画时降级。正式启动不生成演示数据。

普通下载保存到 `Download/不能说的秘密`，GIF 保存到 `Pictures/不能说的秘密/GIF`，实况保存到 `Pictures/不能说的秘密`。Android 10+ 使用 MediaStore，不申请“所有文件访问权限”；Android 7–9 首次保存申请写入权限。扫描仅处理本应用指定目录中可访问、能确认类型的文件。

## 构建

需要 **JDK 17–23、Android SDK 36**，联网下载 Google Maven / Maven Central 依赖。仓库包含 Gradle 8.13 Wrapper，依赖版本固定在构建文件中，无需电脑上的下载服务。

1. 克隆仓库（Windows 建议放在纯英文路径），在 Android Studio 中打开根目录，安装 SDK 36。
2. 配置 `JAVA_HOME`、`ANDROID_HOME`，或在不入库的 `local.properties` 中设置 `sdk.dir`。
3. Windows 执行 `build-apk.cmd`；其他系统执行：

```sh
./gradlew --no-daemon testDebugUnitTest testReleaseUnitTest assembleDebug lintDebug assembleRelease lintRelease
node scripts/test-pornhub-resolver.cjs
```

标准输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

Windows 脚本将输出放到 `%GRADLE_USER_HOME%\secret-downloader-build`，未设置时使用 `%USERPROFILE%\.gradle`。部分 Windows JDK 无法从中文路径启动 Wrapper，请将整个源码目录放到纯英文路径；测试输出也可通过 `-PandroidBuildRoot=一个纯英文绝对路径` 指定。完整构建可能需要数分钟，首次依赖解压与下载更久。

**签名：** 发布包沿用维护者现有本地签名，便于原用户覆盖更新。私钥不入库；自行构建使用自己电脑的 debug keystore，通常不能覆盖安装官方 Release。请勿为解决签名冲突直接卸载而丢失应用记录，先备份需要保留的数据。

## 验证

[验收记录](ACCEPTANCE.md) 与 [手工测试清单](MANUAL_TEST_CHECKLIST.md) 包含问题清单、修复和具体边界。Debug / Release 各 103 项单元测试通过，lint 无错误；一加真机验证了导航、两库隔离、全选、播放器、真实下载、真实 GIF / 实况转换、服务状态与杀进程恢复；混淆 Release 另通过真实界面黑盒测试。

2026-11-12，维护者确认最新版生成的实况在微信好友与朋友圈均能播放。其他手机、相册和微信版本需分别验证。旧格式实况需重新生成或下载，不自动覆盖原文件。

测试素材来自受控生成视频，不包含用户手机照片、数据库、录屏或登录态。`app/src/androidTest/generate-fixtures.ps1` 可通过 FFmpeg 重新生成测试视频。在线下载测试会访问公开作品，默认单元测试不访问它们。

## 依赖与限制

应用代码按 [GPLv3](LICENSE) 发布，与使用的下载引擎许可保持一致；第三方组件与素材保留各自权利声明。

- Media3 ExoPlayer **1.11.1**；youtubedl-android / FFmpeg / aria2c **0.18.1**。下载和转换引擎随 APK 提供，首次启动在本机解压。
- `app/src/main/res/raw/ytdlp` 为 [yt-dlp 2026.10.26 官方发布文件](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26)，SHA-256：`e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3`。
- 实况采用 [Android Motion Photo](https://developer.android.com/media/platform/motion-photo-format)，同时写入 OPlus EXIF / XMP / MPF；布局参考 [oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker)。作品查看器优先使用生成时记录的动态视频片段，不猜测厂商私有格式。
- 许可证及上游源码见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

平台登录、会员、地域和反爬限制可能影响下载，需手机自身可以访问相应网络；未加入登录或 Cookies 导入。下载续传依赖服务器 Range / 引擎分片恢复；转换继续时重新编码。

当前 FFmpeg 预构建包部分内部库仍采用 4 KB ELF 对齐，16 KB 内存页设备未经实测；中低端机、长时内存曲线、TalkBack 全流程和 200% 字号仍需补充验收。可选的后台音频模式尚未加入。
