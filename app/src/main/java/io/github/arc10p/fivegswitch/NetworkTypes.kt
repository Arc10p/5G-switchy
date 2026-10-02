package io.github.arc10p.fivegswitch

import android.telephony.TelephonyManager

internal object NetworkTypes {
    const val NR = TelephonyManager.NETWORK_TYPE_BITMASK_NR

    fun with5G(mask: Long, enabled: Boolean): Long =
        if (enabled) mask or NR else mask and NR.inv()
}
