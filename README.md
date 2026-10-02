# 5G Switch

极简原生 Kotlin 应用：切换当前默认数据 SIM 的 USER 5G 配置，提供 **5G** 快捷设置磁贴。需要 Root、Android 12+；1.0.3 已在目标 ColorOS 17 设备验证读取与切换可用，1.0.4 优化资源释放并改为 Release 交付。

## 安装与构建

在 [Actions](https://github.com/Arc10p/5G-switchy/actions) 打开对应分支最新成功的 **Build APK**，下载 **5GSwitch-release** Artifact，解压安装 `app-release.apk`。首次打开时在 Root 管理器中授权。控制中心 → 编辑磁贴 → 添加 **5G**；锁屏切换需先解锁。Debug Artifact 留作同一次构建的对照。

用户电脑无需 Android Studio、Android SDK 或 Gradle。推送、PR 自动构建；工作流合入默认分支后可手动触发。CI 使用 JDK 17、SDK 35、Build Tools 34.0.0 和完整 Gradle 8.9 Wrapper，执行 Debug 编译、43 项单元测试、Debug/Release lint 及 Release 压缩校验，并上传依赖与 R8 裁剪报告。

默认产物：`app/build/outputs/apk/release/app-release.apk`。Release 开启代码/资源压缩、禁用调试并删除日常调试日志，使用同次构建的测试密钥签名以便安装；正式发布应改用 Actions Secrets 中的固定私钥。Debug 产物为 `app/build/outputs/apk/debug/app-debug.apk`。开发机自行构建需 JDK 17 和 SDK，可用 `ANDROID_HOME`，不依赖 `local.properties`。

## 5G 控制

参考 LuckyTool 的最小 5G 链：**UI / TileService → 应用 AIDL → libsu RootService → ServiceManager("phone") → 系统 ITelephony**。依赖 libsu core/service 6.0.0，以及 LuckyTool 同版本的独立 HiddenApiBypass 6.1 库；不需要安装 LSPosed 或 Xposed 框架。两个 hidden API 类型通过 `compileOnly` 声明，不打入 APK。不使用 shell 电话命令、Compose、数据库或常驻后台服务。操作结束后复用连接最多约 1 秒；即使界面或磁贴继续可见，空闲后也解绑，确认旧 root 进程退出再允许重绑并关闭 su；控制线程空闲 10 秒后回收。关闭所有客户端时立即申请空闲清理，尚有操作或启动未确认则继续等待安全屏障。

动态读取默认数据 subscriptionId，调用 `getAllowedNetworkTypesForReason(subId, ALLOWED_NETWORK_TYPES_REASON_USER)` 取得完整 `Long`。开启只 `mask or NETWORK_TYPE_BITMASK_NR`，关闭只 `mask and NETWORK_TYPE_BITMASK_NR.inv()`；通过 `setAllowedNetworkTypesForReason()` 写回，保留所有非 NR 位，包括 LTE_CA 及未知高位。

操作在单线程执行，界面和磁贴共享防重入门闩。RootService 启动时只开放 ITelephony 类的隐藏接口访问，不以普通反射查找 setter 作为刷新门槛。写入时精确适配 `(int,int,long)` 和设备实际观察到的 `(int,int,long,String)` 两种 boolean 方法，四参数版本传本应用的 `packageName`，不硬编码 Binder 事务编号；字符串的厂商语义尚未由系统源码确认，仍需真机验证。不走会归一化 LTE_CA 位的 `TelephonyManager` 写入封装，完整目标掩码直接传给 Binder。检查实际 boolean 返回与完整读回值；刷新仅读取，默认卡变化时中止或报告错误。链接错误转换为可见诊断；真实调用找不到方法时显示系统接口签名及豁免状态。日志记录 root PID、实际签名、传入包身份与断连事件，标签 **5GSwitch**。写入时 Binder 死亡不会自动重放 toggle，需重新检测实际状态；磁贴失败显示不可用，可打开应用查看诊断。

启动使用 libsu 独立 `su` 会话并通过 `/system/bin/id -u` 验证 UID 0，不自动退回普通 `sh`。启动失败保留异常原因，下一次重试重新创建；只有实际 UID 非 0 才显示 Root 不可用。已验证 Root 后的服务连接错误单独报告。

## 已知问题与真机验收

状态表示 **USER 配置允许 NR**，实际 5G 连接仍取决于运营商、信号、调制解调器和其他 reason。ColorOS 设置页可能不同步显示本应用修改；Root 授权或服务启动超过 60 秒会超时。普通应用进程不调用 hidden API，平台 Binder 的运行兼容仍需 ColorOS 17 真机确认。

请验证 Root 授权/拒绝、完整 mask 开关前后仅 NR 位变化、双卡默认数据切换、无 SIM、连续点击、锁屏切换、磁贴重新展开、服务断开后重试，以及安装 Release 后 RootService 能否启动。Debug 签名可能随 CI runner 变化，覆盖安装失败时需先卸载旧版。未移植 LuckyTool 的其他 Hook 功能。

## 内存验收

保持快捷面板展开，等首次读取完成后至少 5 秒，检查 Scene 中 root 进程是否退出；再点击磁贴，确认重新启动、切换和再次释放。分别比较同一次 CI 的 Release/Debug、相同面板状态与测量口径。关闭面板后再观察空闲进程，不用切换瞬间的峰值代替静置占用。

应用中的 **内存诊断** 按需采样当前应用 PSS、RSS、私有页及 Java/Native 堆，列出当前 Root 连接与历史 Root 活跃采样，可复制结果。诊断不会创建 RootService，也不定时轮询。PSS 分摊共享页，RSS 包含系统共享映射，不能将所有 RSS 直接相加当成应用私有内存；APK 缩小也不等于 RAM 同比例下降。Android SDK/framework 不整套打入 APK，裁剪报告用于检查实际运行依赖。
