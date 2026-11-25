# 早报墨水版（Zaobao E-ink Reader）

为墨水屏设备（讯飞智能办公本 Air / XF-A1）自制的早报阅读 App：调用公开接口拉取栏目、列表与正文，按电子墨水屏的阅读习惯重新排版。

- 纯 Java、零第三方依赖、零 AndroidX，框架原生 View，对弱性能 SoC 友好（APK 约 50KB）
- 顶部横滑栏目标签 + 文章列表；首页只显示免费文章，其余栏目付费/免费按时间混排
- 正文分页阅读：点左半屏上一页、右半屏下一页（或音量键）；正文图片灰度内嵌、右上角角标放大；正文最前面带封面头图
- minSdk 23 / targetSdk 34，权限仅 INTERNET + ACCESS_NETWORK_STATE

当前版本：v1.7（versionCode 8）。本仓库为源码备份，构建在本地 Gradle 工程上进行。
