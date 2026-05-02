# DouyinHelper - 抖音无水印保存助手

一个基于 LSPosed/Xposed 框架的 Android 模块，用于无水印保存抖音视频、图片、实况照片和评论区媒体。

## ✨ 功能

- 🎬 **无水印保存视频** — 自动去除视频水印
- 🖼️ **无水印保存图集** — 保存高清原图
- 📸 **实况照片保存** — 同时保存图片(HEIC)和视频(MOV)部分
- 💬 **评论区媒体** — 保存评论中的图片和实况照片
- ⚙️ **可配置** — 丰富的设置选项

## 📋 系统要求

- Android 9.0+ (API 28)
- 已安装 LSPosed 框架 (推荐 LSPosed 1.8+)
- 抖音正式版 (com.ss.android.ugc.aweme)

## 🔧 安装

1. 下载并安装 DouyinHelper APK
2. 打开 LSPosed 管理器
3. 在模块列表中启用 DouyinHelper
4. 勾选作用域: **抖音**
5. 强制停止抖音或重启手机
6. 打开抖音，模块自动生效

## 📁 项目结构

```
douyin-helper/
├── app/src/main/
│   ├── java/com/xposed/douyinhelper/
│   │   ├── MainHook.kt              # 入口
│   │   ├── hook/
│   │   │   ├── BaseHook.kt          # Hook 基础接口
│   │   │   ├── FeedHook.kt          # Feed 流视频
│   │   │   ├── ShareHook.kt         # 分享拦截
│   │   │   ├── DownloadHook.kt      # 下载流程
│   │   │   ├── DownloadDialogHook.kt # 保存弹窗
│   │   │   ├── CommentHook.kt       # 评论区
│   │   │   └── LivePhotoHook.kt     # 实况照片
│   │   ├── util/
│   │   │   ├── UrlParser.kt         # URL 解析/去水印
│   │   │   ├── MediaDownloader.kt   # 媒体下载器
│   │   │   ├── ClassFinder.kt       # 类查找工具
│   │   │   ├── HookUtils.kt         # Hook 工具
│   │   │   ├── ContextHelper.kt     # Context 获取
│   │   │   └── NotificationHelper.kt # 通知
│   │   └── ui/
│   │       ├── SettingsActivity.kt  # 设置页面
│   │       └── DouyinHelperSettings.kt # 设置管理
│   ├── assets/xposed_init           # Xposed 入口声明
│   └── res/
│       ├── xml/prefs.xml            # 偏好设置 XML
│       ├── values/strings.xml       # 字符串
│       └── values/arrays.xml        # 作用域配置
└── build.gradle.kts
```

## 🔍 去水印原理

抖音的带水印视频 URL 包含 `/playwm/` 路径段，将其替换为 `/play/` 即可获得无水印版本。

本模块通过多种 Hook 策略拦截视频 URL:
1. **Model 层拦截** — Hook Aweme 数据模型，直接修改 URL
2. **下载层拦截** — Hook 下载管理器，替换下载 URL
3. **分享层拦截** — Hook 分享 Intent，提取无水印 URL
4. **OkHttp 层拦截** — 拦截网络请求，替换视频 URL

## ⚠️ 免责声明

本模块仅供学习交流使用。请尊重创作者的版权，不要将保存的内容用于商业用途或未经授权的传播。

## 📄 License

MIT License
