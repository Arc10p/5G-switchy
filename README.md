# 5G Switch

一个用于 Root Android 设备的极简 5G 开关应用，支持在应用内或快捷设置磁贴中控制当前默认数据 SIM 的 5G 配置。

## 功能

- 一键开启或关闭 5G。
- 提供 **5G** 快捷设置磁贴。
- 自动识别当前默认数据 SIM，支持双卡切换。
- 保留其他网络类型配置，操作后读取系统状态确认结果。
- 展示 Root 状态、操作结果和内存诊断。

## 使用要求

- Android 12 或以上。
- 已取得 Root，并允许应用使用 Root 权限。
- 面向 ColorOS 17 开发，其他系统的兼容性以实际使用为准。

## 安装与使用

1. 打开 [GitHub Actions](https://github.com/Arc10p/5G-switchy/actions)，选择最新成功的 **Build APK**，下载 **5GSwitch-release**。
2. 解压并安装 `app-release.apk`，首次使用时授予 Root 权限。
3. 在应用内点击按钮切换，或在控制中心编辑磁贴并添加 **5G**。

显示的状态表示系统配置是否允许 5G，实际连接仍取决于运营商、信号和系统限制。若更新时提示签名不一致，需卸载旧版后安装。

## 构建

项目使用 Kotlin 和原生 Android View。GitHub Actions 自动构建并提供 Debug、Release 安装包，本地构建需要 JDK 17 和 Android SDK 35。

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```
