package com.muxiao.iconpacker.core.packer.zip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 条目名编码判定。
 *
 * 这个判定的价值全在"整包一致性"上：真实坏包里 141 个非 ASCII 名有 135 个不是合法 UTF-8，
 * 剩下 6 个恰好落进 UTF-8 的合法字节空间（解出来是希伯来字母一类的乱码）。逐条回退会
 * 把这 6 个名字解坏，整包判定不会。
 */
class ZipNameDecoderTest {

    private val gb18030: Charset = Charset.forName("GB18030")

    private fun raw(text: String, charset: Charset = Charsets.UTF_8, utf8Flag: Boolean = true) =
        ZipNameDecoder.Raw(text.toByteArray(charset), utf8Flag)

    @Test
    fun `gbk 包里的每个名字（含恰好是合法 utf8 的那个）都解对`() {
        val names = ZipNameDecoder.decodeAll(
            listOf(
                raw("a.png"),
                raw("123云盘.png", gb18030, utf8Flag = false),
                // D6 B1 C6 B8 同时是合法 UTF-8，逐条解码会得到 "BOSSֱƸ"
                raw("BOSS直聘.png", gb18030, utf8Flag = false),
            ),
        )
        assertEquals(listOf("a.png", "123云盘.png", "BOSS直聘.png"), names)
    }

    @Test
    fun `全部合法 utf8 时按 utf8 解`() {
        val names = ZipNameDecoder.decodeAll(
            listOf(raw("图标.png", utf8Flag = false), raw("QQ音乐.png", utf8Flag = false)),
        )
        assertEquals(listOf("图标.png", "QQ音乐.png"), names)
    }

    @Test
    fun `声明 utf8 但字节是 gbk 时回退救回来`() {
        val names = ZipNameDecoder.decodeAll(
            listOf(raw("微信.png", gb18030, utf8Flag = true)),
        )
        assertEquals(listOf("微信.png"), names)
    }

    @Test
    fun `纯 ascii 包不受影响`() {
        val names = ZipNameDecoder.decodeAll(
            listOf(raw("a.png", utf8Flag = false), raw("res/xml/appfilter.xml", utf8Flag = false)),
        )
        assertEquals(listOf("a.png", "res/xml/appfilter.xml"), names)
    }

    @Test
    fun `空输入返回空结果`() {
        assertTrue(ZipNameDecoder.decodeAll(emptyList()).isEmpty())
    }
}
