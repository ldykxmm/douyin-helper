# Dou+ — 抖音增强工具

基于 LSPosed/Xposed 框架的 Android 模块，为抖音提供无水印保存和增强功能。

## 功能

### 无水印保存
- **视频保存** — 自动去除水印，支持 H264/H265 多码率
- **图集保存** — 保存高清原图，无压缩损失
- **实况照片** — 同时保存图片 (HEIC) 和视频 (MOV) 部分
- **评论区媒体** — 保存评论中的图片和实况照片

### 增强操作
- **分享面板保存** — 在抖音分享弹窗注入「保存到相册」按钮，无需跳转
- **评论区长按保存** — 评论区实况照片长按直接保存到本地

### 下载管理
- **画质选择** — 最高画质 / 中等画质 / 省流量三档
- **保存目录自定义** — 可配置文件保存的子目录
- **通知提示** — 下载进度通知 + 完成 Toast 提示

## 系统要求

- Android 9.0+ (API 28)
- LSPosed 框架 (推荐 1.8+ 或 2.0.x)
- 抖音正式版 (`com.ss.android.ugc.aweme`)

## 安装

1. 从 [Releases](https://github.com/ldykxmm/dou-plus/releases) 下载最新 APK
2. 安装 APK（需允许未知来源）
3. 打开 LSPosed 管理器 → 模块列表 → 启用 **Dou+**
4. 勾选作用域：**抖音**
5. 强制停止抖音或重启手机
6. 打开抖音，模块自动生效

## 去水印原理

抖音带水印视频 URL 包含 `/playwm/` 路径段，替换为 `/play/` 即可获取无水印版本。

模块通过多层 Hook 策略拦截：
- **Model 层** — Hook Aweme 数据模型，直接修改 URL
- **下载层** — Hook 下载管理器，替换下载 URL
- **分享层** — Hook 分享 Intent，提取无水印 URL
- **面板层** — 注入保存按钮，直接调用下载

## 项目结构

```
app/src/main/java/com/xposed/douyinhelper/
├── MainHook.kt                  # 模块入口
├── hook/
│   ├── BaseHook.kt              # Hook 基础接口
│   ├── FeedHook.kt              # Feed 流视频 Hook
│   ├── ShareHook.kt             # 分享拦截 Hook
│   ├── DownloadHook.kt          # 下载流程 Hook
│   ├── DownloadDialogHook.kt    # 保存弹窗 Hook
│   ├── CommentHook.kt           # 评论区 Hook
│   ├── LivePhotoHook.kt         # 实况照片 Hook
│   └── SharePanelHook.kt        # 分享面板保存按钮
├── ui/
│   ├── SettingsActivity.kt      # 设置页面
│   └── DouSettings.kt  # 设置管理
└── util/
    ├── UrlParser.kt             # URL 解析/去水印
    ├── MediaDownloader.kt       # 媒体下载器
    ├── MediaCache.kt            # 媒体缓存
    ├── ClassFinder.kt           # 类查找工具
    ├── HookUtils.kt             # Hook 工具
    ├── ContextHelper.kt         # Context 获取
    └── NotificationHelper.kt    # 通知管理
```

## 免责声明

本模块仅供学习交流使用。请尊重创作者版权，勿将保存内容用于商业用途或未经授权的传播。

## License

MIT License
