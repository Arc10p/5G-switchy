# 5G Switch

极简原生 Kotlin 应用：控制当前默认数据 SIM 的 USER 5G 配置，并提供 **5G** 快捷设置磁贴。需要 Root、Android 12+；目标是 ColorOS 17，尚待真机验证。不依赖 LSPosed、AndroidX、Compose 或常驻服务。

## 安装

在 [Actions](https://github.com/Arc10p/5G-switchy/actions) 打开对应分支最新成功的 **Build APK**，下载 **5GSwitch-debug** Artifact，解压并安装 `app-debug.apk`。首次打开应用时在 Root 管理器中授权。展开控制中心 → 编辑磁贴 → 添加 **5G**。锁屏点击需先解锁。

用户电脑无需 Android Studio、Android SDK 或 Gradle。推送代码、PR 均自动构建；工作流合入默认分支后也可手动触发。CI 明确安装 JDK 17、SDK 35 / Build Tools 34.0.0，并使用随项目提交的 Gradle 8.9 Wrapper。

标准构建：`./gradlew assembleDebug`。产物：`app/build/outputs/apk/debug/app-debug.apk`。开发机自行构建需 JDK 17 和 SDK，但不需要 `local.properties`，可通过 `ANDROID_HOME` 指定 SDK。CI 同时执行 `testDebugUnitTest lintDebug`。Release 开启代码与资源压缩，第一版不配置签名。

## 控制方式与边界

通过 `su -c` 执行 `id -u`、`cmd phone help`，确认设备支持 AOSP 参数格式后执行：

```sh
cmd phone get-allowed-network-types-for-users -s SLOT_ID
cmd phone set-allowed-network-types-for-users -s SLOT_ID BINARY_MASK
content query --uri content://telephony/siminfo --projection _id:sim_id:allowed_network_types_for_reasons --where '_id=SUB_ID'
```

`SLOT_ID` 由当前默认数据 subscriptionId 动态映射，不能把 subId 直接传给 `-s`。`cmd phone` 的名称输出有损（包括 LTE_CA 别名及隐藏位），因此额外只读查询该订阅的原始 USER 位掩码，并交叉校验订阅 ID、槽位和可见网络配置。基于完整 mask 仅增加或移除 `NETWORK_TYPE_BITMASK_NR`，以二进制字符串写入；完整保留独立 LTE_CA 位及其他未知位。操作串行、防止重复点击，写入后重新读取并校验，默认卡变化时中止或报告错误。

状态表示 **USER 配置是否允许 NR**，不保证当前已连接 5G。其他 reason、运营商、调制解调器和系统节电策略仍会限制 5G。厂商控制中心/设置页可能不会同步显示本应用的修改。

未连接 ColorOS 17 真机，不能宣称已验证兼容。只接受已核对的 AOSP help、名称输出和订阅字段格式；未知名称、参数变化或读数不一致会拒绝写入。`UNKNOWN` 仅在原始 mask 证明没有可见网络位时接受。若厂商禁止查询订阅字段、改变字段名称，或订阅尚未持久化 USER reason，也会安全中止，不能仅凭名称重建 mask。需取得实际诊断后决定是否替换读取接口。暂不加入 RootService / Binder fallback。

## 真机验收

检查 Root 授权/拒绝、`cmd phone help` 格式、开关前后非 NR 位、双卡默认数据切换、无 SIM、连续点击、磁贴再次展开、系统其他设置更改后的刷新。界面显示可复制的命令、exitCode、stdout/stderr；日志标签为 `5GSwitch`。若不兼容，请保留完整诊断与系统帮助输出，再决定是否需要最小 Binder fallback。
