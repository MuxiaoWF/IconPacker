package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.sign.SigningIdentity
import com.muxiao.iconpacker.core.packer.sign.SigningKeyProvider
import com.muxiao.iconpacker.core.packer.sign.SigningSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PackRequest 的签名方案组合校验（v7：方案可开关，但至少一种） */
class PackRequestSchemesTest {

    private val provider = object : SigningKeyProvider {
        override fun getOrCreate(): SigningIdentity = error("测试桩不应被调用")
    }

    private fun request(schemes: SigningSchemes): PackRequest =
        PackRequest(
            packName = "测试包",
            versionCode = 2,
            mappings = listOf(IconMapping("ComponentInfo{com.example.pkg/.MainActivity}", 1)),
            icons = listOf(SlotIcon(1, "icon.png")),
            templateApkPath = "template.apk",
            outputPath = "out.apk",
            signingKeyProvider = provider,
            signingSchemes = schemes,
        )

    @Test
    fun 默认三方案同签() {
        val request = request(SigningSchemes())
        assertTrue(request.signingSchemes.v1 && request.signingSchemes.v2 && request.signingSchemes.v3)
    }

    @Test
    fun 单一方案也可构建() {
        request(SigningSchemes(v1 = false, v2 = true, v3 = false))
        request(SigningSchemes(v1 = true, v2 = false, v3 = false))
        request(SigningSchemes(v1 = false, v2 = false, v3 = true))
    }

    @Test
    fun 全部方案关闭直接拒绝() {
        try {
            request(SigningSchemes(v1 = false, v2 = false, v3 = false))
        } catch (e: IllegalArgumentException) {
            assertEquals("至少需要启用一种签名方案", e.message)
            return
        }
        throw AssertionError("全关方案应被 init 校验拒绝")
    }
}
