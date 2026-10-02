package android.os;

// 仅用于编译；运行时必须解析系统实现，不能将此模块打入 APK。
public final class ServiceManager {
    public static IBinder getService(String name) {
        throw new UnsupportedOperationException("编译期占位类");
    }
}
