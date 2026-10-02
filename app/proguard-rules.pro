# 日常调试输出在 Release 中删除，保留警告、错误和界面中的实际故障诊断。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
