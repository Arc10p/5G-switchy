# 5G Switch

极简原生 Kotlin 应用，用于 Root、Android 12+ 设备的默认数据 SIM 5G 开关，提供 **5G** 快捷设置磁贴。用户电脑无需安装 Android Studio、Android SDK 或 Gradle；GitHub Actions 构建。

## 当前实现

1.0.5 使用 **UI / TileService → 短命 su → 系统原生 service → phone Binder → ITelephony allowed network types**。不用 `cmd phone`。保留 1.0.3 已在目标 ColorOS 17 验证的电话接口语义，但新的原生客户端、元数据反射与 Parcel 输出仍需同设备验证。

应用通过当前系统框架精确检查 getter `(int,int)->long`、setter `(int,int,long)->boolean` 或 `(int,int,long,String)->boolean`，读取实际 `ITelephony$Stub.TRANSACTION_*` 字段；事务号不写死、不扫描、不猜。未知接口或事务常量缺失时停止。四参数版本发送本应用包名。

每次操作动态读取默认数据 subscriptionId，使用 USER reason 的原始 Long。开启只增加 NR 位，关闭只移除 NR 位，保留 LTE_CA 和其他位；Long 不转 Int。系统错误哨兵 `-1` 拒绝写入。按系统实际 boolean 返回及完整读回确认；切换默认卡时中止或报错。串行读改写、重复点击不排队反转、失败不重放写入。

`su -c` 中先验证实际 UID 0，再执行 `/system/bin/service call phone`；使用动态事务号和 `i32`、`i64`、必要的 `s16` 参数。原生工具退出码不能代表 Binder 成功：回复需严格恢复为字节，由设备自己的 `Parcel.readException()` 处理异常及额外头，再检查 long/boolean 载荷长度。空、截断、未知格式或额外对象都报错。

## 内存方案

1.0.4 真机报告中应用前台 PSS 37.3 MiB、RootService PSS 63.4 MiB；历史应用空闲 PSS 6.0 MiB，场景不同不能直接当成优化前后对比。RSS 含共享映射，不能等同于私有占用。

1.0.5 移除 libsu、RootService、应用 AIDL、hidden API stub 和 Root 连接生命周期代码，取消额外 app_process/ART。只保留 Kotlin 标准库和独立 HiddenApiBypass 6.1；没有 Compose、AppCompat、数据库或原生 ABI 库。只读取所需的系统方法与字段，避免枚举整个电话接口。

Root 命令执行后随即退出，无常驻 Root shell；输出上限 32 KiB、双流排空、60 秒超时。超时终止客户端，不保证厂商 Root 管理器已取消远端写入，因此结果未知且不自动重试。主界面使用软件绘制，控制线程空闲 10 秒回收；不强杀 Android 绑定中的磁贴进程。Release 开启代码/资源压缩、禁用调试。

应用 **内存诊断** 仅读取当前应用，列出 PSS、私有页、RSS、Java/Native 堆以及 Graphics、Code、Stack 等系统分类，显示最近命令的历史 PID 和操作结束间隔，不启动 su、也不轮询。最低稳态目标是只保留 Android 必须维持的应用/磁贴进程；具体 MiB 必须真机测量。

## 安装与构建

在 [Actions](https://github.com/Arc10p/5G-switchy/actions) 下载对应提交成功构建的 **5GSwitch-release**，解压安装 `app-release.apk`，首次使用授权 Root。控制中心编辑磁贴并添加 **5G**；锁屏点击先解锁。

CI 使用 JDK 17、SDK 35、Build Tools 34.0.0、Gradle 8.9 Wrapper，运行 Debug 编译、单元测试、Debug/Release lint 与 Release 构建，上传 Debug/Release APK、实际测试 XML、依赖与 R8 裁剪报告。Release 使用测试签名但非 debuggable；密钥可能随 runner 变化，签名不一致时需卸载旧版。正式发布需使用 Actions Secrets 中的固定密钥，禁止提交私钥。

## 真机验收

先确认原生客户端读取和三／四参数写入可用，开关前后完整 mask 只差 NR 位。验证默认数据卡切换、无 SIM、连续点击、锁屏、磁贴重新展开和失败后重新检测。状态表示 USER 配置允许 NR，实际连接仍受运营商、信号及其他 reason 限制。

分别在“主界面打开”和“仅快捷面板打开”的相同状态静置至少 5 秒，比较 1.0.4 与 1.0.5 的 PSS、私有页和 Graphics；确认无 Root app_process 常驻，再次切换仍可用。新架构与软件绘制的内存收益未经真机测量，不将 APK 大小作为 RAM 指标。
