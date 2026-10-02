package io.github.arc10p.fivegswitch;

// 应用与 Root 进程之间的最小接口；完整掩码以 long 传输。
interface IFiveGService {
    int getRootUid();
    boolean checkCompatibility(int subId);
    long getUserMask(int subId);
    boolean setUserMask(int subId, long mask);
}
