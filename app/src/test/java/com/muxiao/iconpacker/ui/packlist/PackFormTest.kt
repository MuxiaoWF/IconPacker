package com.muxiao.iconpacker.ui.packlist

import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.ui.components.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PackForm 校验逻辑（新建/编辑对话框共用）的 JVM 单测。
 *
 * 只测"保存按钮为什么禁用/放行"的规则本身；不触碰 Compose 渲染。
 */
class PackFormTest {

    @Test
    fun 空白表单不可保存() {
        val form = PackForm()
        assertFalse(form.isValid)
    }

    @Test
    fun 默认配置即可保存_包名留空表示自动派生() {
        val form = PackForm()
        form.name = "测试包"
        assertTrue(form.isValid)
        assertNull(form.packageNameIssue)
        assertNull(form.versionNameIssue)
        assertNull(form.versionCodeIssue)
        assertNull(form.signIssue)
        val draft = form.toDraft()
        assertNull(draft.packageName)
    }

    @Test
    fun 非法包名与坏版本号分别报错() {
        val form = PackForm()
        form.name = "测试包"
        form.packageName = "not valid pkg"
        assertTrue(form.packageNameIssue != null)
        assertFalse(form.isValid)

        form.packageName = "com.example.ok"
        form.versionCodeInput = "abc"
        assertTrue(form.versionCodeIssue != null)
        assertFalse(form.isValid)

        form.versionCodeInput = "5"
        val parsed = form.toDraft().versionCodeInput
        assertEquals("5", parsed)
        assertTrue(form.isValid)
    }

    @Test
    fun 导入密钥模式缺文件与别名不可保存() {
        val form = PackForm()
        form.name = "测试包"
        form.importedKey = true
        assertEquals(UiText(R.string.form_sign_needs_file), form.signIssue)

        form.keystorePath = "/private/keystores/ks_1.keystore"
        assertEquals(UiText(R.string.form_sign_needs_alias), form.signIssue)

        form.keystoreAlias = "key0"
        assertTrue(form.isValid)
    }

    @Test
    fun 全部签名方案关闭不可保存() {
        val form = PackForm()
        form.name = "测试包"
        form.schemeV1 = false
        form.schemeV2 = false
        form.schemeV3 = false
        assertEquals(UiText(R.string.form_sign_needs_scheme), form.signIssue)
        assertFalse(form.isValid)
    }

    @Test
    fun `导入配置经 toSignConfig 往返字段无失真`() {
        val form = PackForm()
        form.name = "测试包"
        form.importedKey = true
        form.keystorePath = " /p.keystore "
        form.keystorePassword = "pw"
        form.keystoreAlias = " key0 "
        form.keyPassword = "kp"
        form.signerName = " MYNAME "
        form.schemeV2 = false

        val config = form.toSignConfig()
        assertEquals(1, config.signMode)
        assertEquals("/p.keystore", config.keystorePath)
        assertEquals("pw", config.keystorePassword)
        assertEquals("key0", config.keystoreAlias)
        assertEquals("kp", config.keyPassword)
        assertEquals("MYNAME", config.signerName)
        assertTrue(config.schemeV1 && !config.schemeV2 && config.schemeV3)
    }

    @Test
    fun 设备模式切回默认签名身份() {
        val form = PackForm()
        form.name = "测试包"
        form.importedKey = false
        form.signerName = "X"
        val config = form.toSignConfig()
        assertEquals(0, config.signMode)
        assertEquals("", config.keystorePath)
        assertEquals("X", config.signerName)
    }

    @Test
    fun 清除密钥同时复位导入子项() {
        val form = PackForm()
        form.name = "测试包"
        form.importedKey = true
        form.keystorePassword = "pw"
        form.keystoreAlias = "key0"
        form.clearKeystore()
        assertFalse(form.importedKey)
        assertTrue(form.keystorePath.isEmpty() && form.keystorePassword.isEmpty() && form.keystoreAlias.isEmpty())
        assertTrue(form.isValid)
    }
}
