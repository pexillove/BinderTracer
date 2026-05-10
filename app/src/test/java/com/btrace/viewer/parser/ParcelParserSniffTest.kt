package com.btrace.viewer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * spec § 13.2 + § 9.4:验证启发式参数嗅探能识别 Parcel 里的常见 token(String / IBinder / int)。
 *
 * ParcelParser.sniffArgumentTypes 不依赖 Android runtime(纯 ByteBuffer),可直接 JVM 测试。
 */
class ParcelParserSniffTest {

    private val parser = ParcelParser()

    // 构造符合 Parcel layout 的测试字节流:
    // 12 byte header + int nameLen + UTF-16 token (含 NUL) + 4 字节对齐 + args
    private fun buildParcel(interfaceToken: String, argsBytes: ByteArray): ByteArray {
        val header = ByteArray(12) { 0 }
        val tokenChars = interfaceToken.length
        val tokenUtf16 = ByteBuffer.allocate((tokenChars + 1) * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (c in interfaceToken) putShort(c.code.toShort())
            putShort(0) // NUL
        }.array()
        val tokenPadded = tokenUtf16.size.let { s -> ByteArray(((s + 3) and 0x3.inv()) - s) }
        val buf = ByteArray(12 + 4 + tokenUtf16.size + tokenPadded.size + argsBytes.size)
        var pos = 0
        System.arraycopy(header, 0, buf, pos, 12); pos += 12
        ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(tokenChars); pos += 4
        System.arraycopy(tokenUtf16, 0, buf, pos, tokenUtf16.size); pos += tokenUtf16.size
        pos += tokenPadded.size
        System.arraycopy(argsBytes, 0, buf, pos, argsBytes.size)
        return buf
    }

    /** 一个 writeString(s) 产生的字节序列:int length + UTF-16 + NUL + 4 字节对齐。 */
    private fun encodeString(s: String): ByteArray {
        val chars = s.length
        val bytes = (chars + 1) * 2
        val padded = (bytes + 3) and 0x3.inv()
        val buf = ByteArray(4 + padded)
        ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(chars)
        val sb = ByteBuffer.wrap(buf, 4, bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (c in s) sb.putShort(c.code.toShort())
        sb.putShort(0)
        return buf
    }

    /** Parcel.writeInt(value) 产生的 4 字节。 */
    private fun encodeInt(v: Int): ByteArray {
        val buf = ByteArray(4)
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
        return buf
    }

    /** flat_binder_object 前 4 字节是 type magic;后 20 字节填 0 足矣(sniff 只看前 4)。 */
    private fun encodeBinderToken(): ByteArray {
        val buf = ByteArray(24)
        // BINDER_TYPE_BINDER = 'sb*' + 0x85,LE int32 = 0x852a6273
        ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x852a6273.toInt())
        return buf
    }

    @Test
    fun emptyOrShortData_returnsEmpty() {
        assertEquals(emptyList<String>(), parser.sniffArgumentTypes(ByteArray(0)))
        assertEquals(emptyList<String>(), parser.sniffArgumentTypes(ByteArray(10)))
    }

    @Test
    fun noArguments_returnsEmpty() {
        val parcel = buildParcel("android.os.IServiceManager", ByteArray(0))
        assertEquals(emptyList<String>(), parser.sniffArgumentTypes(parcel))
    }

    @Test
    fun singleStringArgument_isIdentified() {
        val parcel = buildParcel("android.os.IServiceManager", encodeString("window"))
        val types = parser.sniffArgumentTypes(parcel)
        assertEquals(listOf("String"), types)
    }

    @Test
    fun singleIntArgument_isIdentified() {
        val parcel = buildParcel("android.app.IActivityManager", encodeInt(10086))
        val types = parser.sniffArgumentTypes(parcel)
        assertEquals(listOf("int"), types)
    }

    @Test
    fun ibinderTokenArgument_isIdentified() {
        val parcel = buildParcel("android.os.IServiceManager", encodeBinderToken())
        val types = parser.sniffArgumentTypes(parcel)
        assertEquals(listOf("IBinder"), types)
    }

    @Test
    fun mixedArguments_areIdentifiedInOrder() {
        val args = encodeString("com.foo.app") + encodeInt(10086) + encodeBinderToken()
        val parcel = buildParcel("android.app.IActivityManager", args)
        assertEquals(listOf("String", "int", "IBinder"), parser.sniffArgumentTypes(parcel))
    }

    @Test
    fun nullMarker_isIdentifiedAsQuestion() {
        // -1 作为 length 是 null String 常见 marker
        val parcel = buildParcel("android.os.IFoo", encodeInt(-1))
        val types = parser.sniffArgumentTypes(parcel)
        assertEquals(listOf("?"), types)
    }

    @Test
    fun maxArgsClamp_stopsAtLimit() {
        // 塞 20 个 int,sniff 只取前 6 个(默认 maxArgs=6)
        val args = (1..20).fold(ByteArray(0)) { acc, i -> acc + encodeInt(i) }
        val parcel = buildParcel("android.os.IFoo", args)
        val types = parser.sniffArgumentTypes(parcel)
        assertEquals(6, types.size)
        assertTrue(types.all { it == "int" })
    }
}
