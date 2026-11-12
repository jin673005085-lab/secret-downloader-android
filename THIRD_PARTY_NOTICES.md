# 第三方组件与来源

应用代码使用 GPLv3，全文见 [LICENSE](LICENSE)。以下上游组件保留原作者的版权和许可；桌面图像由维护者提供，代码许可证不转移其图像版权。此目录记录实际依赖和已核对版本，不将可选组件一概声明为已打包。

## Android / JVM

| 组件 | 版本 | 许可与源码 |
| --- | --- | --- |
| AndroidX Media3 ExoPlayer / UI | 1.11.1 | Apache-2.0；[源码](https://github.com/androidx/media/tree/1.11.1)、[许可](licenses/Apache-2.0.txt) |
| AndroidX RecyclerView / DynamicAnimation / ExifInterface | 1.4.0 / 1.1.0 / 1.4.2 | Apache-2.0；[AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin 标准库 | 2.2.21 | Apache-2.0；[源码](https://github.com/JetBrains/kotlin/tree/v2.2.21) |
| Gradle Wrapper | 8.13 | Apache-2.0；[源码](https://github.com/gradle/gradle/tree/v8.13.0) |
| youtubedl-android library / common / ffmpeg / aria2c | 0.18.1 | 四个 Maven 模块均声明 GPL-3.0；[固定源码提交](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760)、[许可](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/LICENSE) |

AndroidX 等组件的传递依赖由 Gradle 解析；完整列表可用 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 查看。测试依赖 JUnit 4.13.2（EPL-1.0）及 org.json 20240303（[JSON License](https://github.com/stleary/JSON-java/blob/20240303/LICENSE)）不进入正式 APK。

## 下载与转换引擎

- **yt-dlp 2026.10.26**：`app/src/main/res/raw/ytdlp`，官方 Python ZIP 可执行文件；[源码提交 c91f8de](https://github.com/yt-dlp/yt-dlp/tree/c91f8de0bfb3577894abad716eaaa01659e6bd14)、[发行文件](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26)、[Unlicense](licenses/yt-dlp-Unlicense.txt)。SHA-256：`e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3`。其中包含 **yt-dlp EJS 0.8.0**，[源码与许可](https://github.com/yt-dlp/ejs/tree/0.8.0)，同为 Unlicense。
- **FFmpeg 7.1.4**：来自固定的 `ffmpeg:0.18.1` Android AAR，实际构建启用了 GPL 与 version3，含 x264 / x265 支持；[FFmpeg 对应源码](https://ffmpeg.org/releases/ffmpeg-7.1.4.tar.xz)、[GPLv3](LICENSE)、[官方版权与许可说明](https://ffmpeg.org/legal.html)。
- **aria2 1.37.0**：来自 `aria2c:0.18.1`；[上游源码](https://github.com/aria2/aria2/tree/release-1.37.0)、[GPL-2.0-or-later 许可](licenses/aria2-COPYING.txt)，Android 封装模块采用 GPL-3.0。
- **CPython 3.12.11**：来自 `library:0.18.1`；[对应源码](https://github.com/python/cpython/tree/v3.12.11)、[PSF 与历史许可全文](licenses/CPython-LICENSE.txt)。
- **QuickJS**：由 `library:0.18.1` 提供；[上游源码](https://bellard.org/quickjs/)、[MIT 许可](licenses/QuickJS-LICENSE.txt)。Android AAR 未给出可独立核实的 QuickJS 源码提交，本文不猜测版本。

Android 预构建引擎还包含其依赖库。封装、资源打包与构建步骤见上述固定提交中的 [BUILD_FFMPEG.md](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/BUILD_FFMPEG.md) 和 [BUILD_PYTHON.md](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/BUILD_PYTHON.md)，及其引用的 [Termux 构建脚本与补丁](https://github.com/termux/termux-packages)。历史文档中的旧版本不能替代上面核对的二进制版本。

本项目使用固定 Maven AAR 重建 APK；上游构建说明未固定 Termux 的完整提交与依赖树，因此不声称这些原生库可由说明逐字节复现，也不把当前 Termux HEAD 冒充成其精确构建来源。后续升级或再次分发引擎时，应保留其完整源码、补丁及相应许可。

## 实况格式参考

[Android Motion Photo 格式](https://developer.android.com/media/platform/motion-photo-format)；OPlus EXIF / MPF 字节布局参考 [oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker)。本项目的解析、封装与单元测试位于 `MotionPhotoWriter.kt` 与 `MotionPhotoWriterTest.kt`，不会上传用于比对的手机相机原片。
