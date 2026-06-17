# 视频音频提取器 (Video Audio Extractor)

一款基于 Java 开发的 Android 应用，用于从视频文件中提取音频，并支持多种输出格式的选择。

## 功能特点

- **5种音频格式**：支持提取为 MP3、AAC、WAV、FLAC、M4A 格式
- **自定义质量**：可调节比特率（128/192/256/320 kbps）和采样率（22050/44100/48000 Hz）
- **多种视频输入**：支持 MP4、AVI、MOV、MKV、WMV、FLV、WEBM、3GP 等格式
- **实时进度**：提取过程中显示实时进度条
- **本地处理**：所有处理在设备本地完成，文件不上传服务器
- **播放/分享/保存**：提取完成后可直接播放、分享或保存到 Download 目录

## 界面设计

界面参照 [apadog.com/video-converter](https://apadog.com/video-converter.html) 的设计风格，包含以下区域：

1. **视频选择区域**：点击选择视频文件，显示缩略图、时长、分辨率、大小等信息
2. **格式选择区域**：以卡片形式展示 5 种音频格式供选择
3. **质量设置区域**：下拉选择比特率和采样率
4. **提取处理区域**：开始提取按钮 + 进度条 + 完成状态
5. **功能特点区域**：展示应用的核心功能亮点

## 技术架构

### 核心依赖

| 库 | 版本 | 用途 |
|---|---|---|
| FFmpeg Kit Full | 6.0-2 | 音频提取核心引擎 |
| Smart Exception Java | 0.2.1 | FFmpeg Kit 异常处理 |
| AndroidX AppCompat | 1.6.1 | 向后兼容 |
| Material Components | 1.11.0 | Material Design UI |
| ConstraintLayout | 2.1.4 | 布局管理 |
| CardView | 1.0.0 | 卡片容器 |

### 项目结构

```
app/src/main/
├── java/com/videoaudio/extractor/
│   ├── MainActivity.java        # 主界面，管理UI交互和业务流程
│   ├── AudioExtractor.java      # FFmpeg 音频提取封装
│   └── FileUtils.java          # 文件操作工具类
├── res/
│   ├── layout/
│   │   └── activity_main.xml    # 主界面布局
│   ├── drawable/                # 矢量图标和背景
│   ├── values/
│   │   ├── colors.xml           # 颜色定义
│   │   ├── strings.xml          # 字符串资源
│   │   └── styles.xml           # 主题和样式
│   └── xml/
│       └── file_paths.xml       # FileProvider 路径配置
└── AndroidManifest.xml
```

### FFmpeg 命令说明

各格式对应的 FFmpeg 命令：

| 格式 | 编码器 | 命令参数 |
|---|---|---|
| MP3 | libmp3lame | `-vn -c:a libmp3lame -b:a [bitrate] -ar [sampleRate]` |
| AAC | aac | `-vn -c:a aac -b:a [bitrate] -ar [sampleRate]` |
| WAV | pcm_s16le | `-vn -c:a pcm_s16le -ar [sampleRate]` |
| FLAC | flac | `-vn -c:a flac -ar [sampleRate] -compression_level 5` |
| M4A | aac | `-vn -c:a aac -b:a [bitrate] -ar [sampleRate]` |

## 环境要求

- Android Studio 2023.1+
- Gradle 8.5
- Android SDK 34 (compileSdk)
- minSdk 24 (Android 7.0)
- Java 8+

## 构建与运行

1. 使用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成（会自动下载 FFmpeg Kit AAR，约 30MB）
3. 连接 Android 设备或启动模拟器
4. 点击 Run 运行

## 许可证

- 应用代码：MIT License
- FFmpeg Kit：LGPL 3.0
