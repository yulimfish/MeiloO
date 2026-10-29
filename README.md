<div align="center">

# MeiloO

**本地优先、无在线音源的 Apple Music 风格 Android 音乐播放器**

基于 [NEORUAA/MeiloX](https://github.com/NEORUAA/MeiloX) 独立 clone 二次开发；本仓库不是 GitHub fork。

![Android](https://img.shields.io/badge/Platform-Android%2013%2B-green?logo=android)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)
![License](https://img.shields.io/badge/License-GPLv3-orange)

</div>

## 功能

- **只播放本地文件**：播放解析只接受 `file://`、绝对路径和用户授权的 `content://`，拒绝 HTTP/HTTPS 等在线音源。
- **多文件夹曲库**：通过 Android SAF 选择一个或多个目录，持久保存授权并递归扫描；支持 MP3、FLAC、WAV、OGG、Opus、M4A、AAC、ALAC、APE、DSF、DFF。
- **统一歌曲信息**：读取内嵌标题、歌手、专辑、时长、码率、采样率与封面，保留用户已选择的信息。
- **曲库组织**：歌曲 / 专辑 / 艺术家 / 设置四项底部导航；支持本地搜索、标题/歌手/专辑/时长/加入时间排序、反向排序及拖拽自定义顺序。
- **多渠道封面与信息匹配**：自动模式综合 iTunes Search API、MusicBrainz 与 Cover Art Archive，按标题、歌手、专辑和时长计算匹配度。
- **歌词**：优先使用同目录同名 LRC；需要联网时使用 LRCLIB 的精确接口与搜索接口，缓存到原有歌词系统。
- **全局与单曲来源设置**：设置页可配置自动分析同步、全局封面/歌词来源；全屏播放器的“查找歌词”页面可针对当前歌曲覆盖来源并手动选择最佳候选。
- **保留原作体验**：继续使用 Glasmio、液态玻璃、动态封面背景、迷你播放器、全屏播放器和歌词渲染。

> 网络仅用于用户允许的封面、歌曲信息、歌词和应用更新检查，不用于搜索、获取或播放音乐音频。关闭自动同步并把来源设为“内嵌与本地”后，可避免元数据网络请求。

## 信息来源与匹配策略

| 能力 | 来源 |
| --- | --- |
| 内嵌标签与封面 | Android MediaMetadataRetriever / MediaExtractor |
| 本地歌词 | 与音频同目录、同基础文件名的 `.lrc` |
| 歌曲信息、专辑封面 | [iTunes Search API](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/) |
| 歌曲信息、专辑封面 | [MusicBrainz](https://musicbrainz.org/doc/MusicBrainz_API) / [Cover Art Archive](https://coverartarchive.org/) |
| 同步与纯文本歌词 | [LRCLIB](https://lrclib.net/) |

自动匹配会规范化大小写、全半角、标点与重音符号，对标题、歌手、专辑和时长加权评分；标题完全不同时不会强行写入低置信度结果。单曲手动选择始终优先于自动策略。第三方服务的可用性、条款与内容版权由其各自运营方和权利人负责。

实现参考了用户 player 收藏列表中的 [SPlayer](https://github.com/SPlayer-Dev/SPlayer)、[kmgccc_player](https://github.com/kmgccc/kmgccc_player) 与 [mpv](https://github.com/mpv-player/mpv) 的本地曲库和元数据处理思路，没有引入其在线播放源。

## 从源码构建

要求：JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0、NDK 27.1.12297006、CMake 3.22.1。

```sh
git clone https://github.com/yulimfish/MeiloO.git
cd MeiloO
git clone https://github.com/NEORUAA/Glasmio.git ../Glasmio
git -C ../Glasmio checkout aad1605a2807cddaf5afacea131d528bc9452c9f
./gradlew -Pglasmio.source=true :app:assembleDebug
```

运行 JVM 测试：

```sh
./gradlew -Pglasmio.source=true :app:testDebugUnitTest
```

首次启动后，在“歌曲”页点击“添加音乐文件夹”。MeiloO 不使用全盘 MediaStore 扫描，也不会要求读取未选择的目录。

## 开源与隐私

MeiloO 是 MeiloX 的衍生项目，主体代码继续以 [GNU General Public License v3](LICENSE) 发布。原作、Mei、Glasmio、歌词组件及其他依赖的版权和许可证见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES)。

曲库、排序和来源设置保存在本机。歌曲标题、歌手、专辑和时长可能作为元数据查询参数发送给你启用的信息来源；不会上传音频文件。

## 免责声明

本项目仅用于学习、研究和个人使用，不隶属于 Apple、MusicBrainz、Apple iTunes 或 LRCLIB。音乐、封面和歌词版权归相应权利人所有。请遵守所在地区法律及各来源服务条款。
