# 5G Switch

极简原生 Kotlin 应用：切换当前默认数据 SIM 的 USER 5G 配置，提供 **5G** 快捷设置磁贴。需要 Root、Android 12+；目标为 ColorOS 17，尚待真机验证。

## 安装与构建

在 [Actions](https://github.com/Arc10p/5G-switchy/actions) 打开对应分支最新成功的 **Build APK**，下载 **5GSwitch-debug** Artifact，解压安装 `app-debug.apk`。首次打开时在 Root 管理器中授权。控制中心 → 编辑磁贴 → 添加 **5G**；锁屏切换需先解锁。

用户电脑无需 Android Studio、Android SDK 或 Gradle。推送、PR 自动构建；工作流合入默认分支后可手动触发。CI 使用 JDK 17、SDK 35、Build Tools 34.0.0 和完整 Gradle 8.9 Wrapper，执行 `./gradlew assembleDebug`、26 项单元测试、lint 及 Release 压缩校验。

Debug 产物：`app/build/outputs/apk/debug/app-debug.apk`。Release 开启代码/资源压缩，未配置签名。开发机自行构建需 JDK 17 和 SDK，可用 `ANDROID_HOME`，不依赖 `local.properties`。

## 5G 控制

参考 LuckyTool 的最小 5G 链：**UI / TileService → 应用 AIDL → libsu RootService → ServiceManager("phone") → 系统 ITelephony**。仅依赖 libsu core/service 6.0.0；两个 hidden API 类型通过 `compileOnly` 声明，不打入 APK。不使用 shell 电话命令、LSPosed、Xposed、Compose、数据库或常驻后台服务。界面或磁贴可见期间复用同一 RootService 连接，最后一个客户端退出且待执行操作全部结束后解绑；确认旧 root 进程退出后才建立新连接。

动态读取默认数据 subscriptionId，调用 `getAllowedNetworkTypesForReason(subId, ALLOWED_NETWORK_TYPES_REASON_USER)` 取得完整 `Long`。开启只 `mask or NETWORK_TYPE_BITMASK_NR`，关闭只 `mask and NETWORK_TYPE_BITMASK_NR.inv()`；通过 `setAllowedNetworkTypesForReason()` 写回，保留所有非 NR 位，包括 LTE_CA 及未知高位。

操作在单线程执行，界面和磁贴共享防重入门闩。检查 setter 的 boolean 与完整读回值，默认卡变化时中止或报告错误。兼容检查核对系统真实 setter 签名并读取，不写回原值。链接错误转换为可见诊断，日志记录 root PID 与断连事件，标签 **5GSwitch**。写入时 Binder 死亡不会自动重放 toggle，需重新检测实际状态；磁贴失败显示不可用，可打开应用查看诊断。

## 已知问题与真机验收

状态表示 **USER 配置允许 NR**，实际 5G 连接仍取决于运营商、信号、调制解调器和其他 reason。ColorOS 设置页可能不同步显示本应用修改；Root 授权或服务启动超过 60 秒会超时。普通应用进程不调用 hidden API，平台 Binder 的运行兼容仍需 ColorOS 17 真机确认。

请验证 Root 授权/拒绝、完整 mask 开关前后仅 NR 位变化、双卡默认数据切换、无 SIM、连续点击、锁屏切换、磁贴重新展开、服务断开后重试，以及安装 Release 后 RootService 能否启动。Debug 签名可能随 CI runner 变化，覆盖安装失败时需先卸载旧版。未移植 LuckyTool 的其他 Hook 功能。
