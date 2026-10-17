package com.muxiao.iconpacker.core.packer.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ImportedKeystoreProvider 的错误路径与真实密钥库读取测试。
 *
 * "真实密钥库"用例通过 keytool（构建 JVM 自带）生成 PKCS12，生成不了的
 * 环境（如无 CLI 的精简 JDK）跳过——错误路径用例不依赖它，仍然全量执行。
 */
class ImportedKeystoreProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun provider(keystorePath: String) = ImportedKeystoreProvider(
        keystorePath = keystorePath,
        storePassword = "store-pass",
        alias = "testkey",
        keyPassword = "",
    )

    @Test
    fun `文件不存在时给出可定位的错误`() {
        val path = File(tmp.root, "missing.keystore").absolutePath
        try {
            provider(path).getOrCreate()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("密钥库文件不存在"))
            return
        }
        throw AssertionError("缺失文件应失败")
    }

    @Test
    fun `空白别名在打开文件前即被拦截`() {
        val path = File(tmp.root, "any.keystore").apply { writeText("x") }.absolutePath
        try {
            ImportedKeystoreProvider(path, "p", alias = "", keyPassword = "").getOrCreate()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未指定密钥别名"))
            return
        }
        throw AssertionError("空白别名应失败")
    }

    @Test
    fun `损坏的密钥库文件在试遍格式后合并报错`() {
        val path = File(tmp.root, "broken.keystore").apply { writeText("not a keystore") }.absolutePath
        try {
            provider(path).getOrCreate()
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: ""
            assertTrue("报错应列出尝试过的格式：$message", message.contains("PKCS12"))
            assertTrue("报错应包含底层原因：$message", message.contains("无法打开密钥库"))
            return
        }
        throw AssertionError("坏文件应失败")
    }

    @Test
    fun `keytool 生成的 PKCS12 能解析出签名身份`() {
        val runner = keytoolExecutable()
        assumeTrue("无 keytool 环境跳过真实密钥库用例", runner != null)
        runner!!

        val keystore = File(tmp.root, "real.pkcs12")
        val password = "store-pass"
        val aliases = "testkey"
        runner.run(
            "-genkeypair",
            "-keystore", keystore.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
            "-alias", aliases,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "30",
            "-dname", "CN=Test Signer",
        )

        val identity = ImportedKeystoreProvider(
            keystorePath = keystore.absolutePath,
            storePassword = password,
            alias = aliases,
            keyPassword = password,
        ).getOrCreate()

        assertEquals("CERT", identity.name)
        assertTrue(identity.certificates.isNotEmpty())
        assertTrue(identity.certificates[0].subjectX500Principal.name.contains("Test Signer"))
    }

    /** 构建自身 JVM 的 keytool 可执行文件；不可得返回 null */
    private fun keytoolExecutable(): KeytoolRunner? {
        val javaHome = File(System.getProperty("java.home"))
        val candidates = listOf("keytool.exe", "keytool").map { File(javaHome, "bin/$it") }
        val tool = candidates.firstOrNull { it.isFile } ?: return null
        return KeytoolRunner(tool)
    }

    private class KeytoolRunner(private val executable: File) {
        fun run(vararg args: String) {
            val process = ProcessBuilder(executable.absolutePath, *args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString()
            val finished = process.waitFor()
            assertTrue("keytool 生成失败（exit=$finished）：$output", finished == 0)
        }
    }
}
