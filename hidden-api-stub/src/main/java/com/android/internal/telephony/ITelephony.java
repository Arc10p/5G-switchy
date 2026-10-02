package com.android.internal.telephony;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

// 只声明读取方法；写入精确解析系统的三参数或四参数方法，协议由系统 Stub 提供。
public interface ITelephony extends IInterface {
    long getAllowedNetworkTypesForReason(int subId, int reason) throws RemoteException;

    abstract class Stub extends Binder implements ITelephony {
        public static ITelephony asInterface(IBinder binder) {
            throw new UnsupportedOperationException("编译期占位类");
        }
    }
}
