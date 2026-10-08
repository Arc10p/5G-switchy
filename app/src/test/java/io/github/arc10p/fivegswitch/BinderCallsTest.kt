package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test

class BinderCallsTest {
    @Test fun `系统隐藏接口链接错误转换成可见诊断`() {
        val original = NoSuchMethodError("系统电话元数据")
        val error = assertThrows(IllegalStateException::class.java) {
            BinderCalls.call("读取系统电话元数据", {}) { throw original }
        }
        assertSame(original, error.cause)
        assertTrue(error.message!!.contains("NoSuchMethodError"))
    }
    @Test fun `致命虚拟机错误不能作为普通电话异常处理`() {
        val original = OutOfMemoryError("测试错误")
        assertSame(original, assertThrows(OutOfMemoryError::class.java) {
            BinderCalls.call("测试", {}) { throw original }
        })
    }
}
