package com.android.internal.telephony;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

// 只声明 Android 12+ 需要的两个方法；实际 Binder 协议由系统 Stub 提供。
public interface ITelephony extends IInterface {
    long getAllowedNetworkTypesForReason(int subId, int reason) throws RemoteException;
    boolean setAllowedNetworkTypesForReason(int subId, int reason, long mask) throws RemoteException;

    abstract class Stub extends Binder implements ITelephony {
        public static ITelephony asInterface(IBinder binder) {
            throw new UnsupportedOperationException("编译期占位类");
        }
    }
}
