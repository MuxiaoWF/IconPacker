package com.muxiao.iconpacker.core.packer.sign

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * 基于用户导入密钥库文件的签名密钥提供者。
 *
 * 与 [AndroidKeystoreKeyProvider] 的区别：密钥不是本设备生成的，而是用户提供
 * （如用 Android Studio 建的签名密钥、线下发布者自己的证书）——同一密钥在任何
 * 设备上产出的图标包签名一致，可互相覆盖升级，产物也天然对外共享同一身份。
 *
 * 应用的设计约束（见 docs/android-verification.md：禁用**内置密钥**）不受影响：
 * 这里加载的仍然是用户自己的密钥文件，密钥文件由用户经 SAF 选择、应用代为落盘
 * 到私有目录，不存在硬编码/随包分发的密钥。
 *
 * 安全说明：密钥库密码与别名由调用方持久化（Room，应用私有数据库）。
 * 应用私有的“数据库 + 文件”边界内明文，是导入密钥场景下可接受的折衷——
 * 没有密钥文件本身时密码无价值，密钥文件又只在应用私有目录。
 */
class ImportedKeystoreProvider(
    /** 密钥库文件路径（应用私有目录内，由 app 模块从 SAF Uri 落盘） */
    private val keystorePath: String,
    /** 打开密钥库的密码；允许为空串（部分 keystore 私钥保护用同一密码或无密码） */
    private val storePassword: String,
    /** 私钥条目别名 */
    private val alias: String,
    /** 私钥密码；空串时与密钥库密码相同（KeyStore.PasswordProtection 的常见约定） */
    private val keyPassword: String,
    /** v1 签名者名（META-INF 下的 .SF/.RSA 文件名），导入场景默认与 Android Studio 对齐 */
    private val signerName: String = DEFAULT_SIGNER_NAME,
) : SigningKeyProvider {

    /**
     * 读取签名身份。
     *
     * 密钥库格式自动探测（依次尝试）：PKCS12 是 Android Studio/keytool 的现代默认，
     * JKS 是更早的默认；BKS 仅 Android 运行时可用，一并列入兜底。
     * 打包流程对失败信息零容忍退路——找不到可用条目就直接失败，绝不静默回退到
     * 其他身份，否则“用谁的密钥签名”这件身份级事实会被悄悄改变。
     */
    override fun getOrCreate(): SigningIdentity {
        require(keystorePath.isNotBlank()) { "未指定密钥库文件" }
        require(alias.isNotBlank()) { "未指定密钥别名" }
        val file = File(keystorePath)
        require(file.isFile) { "密钥库文件不存在：$keystorePath" }

        var loadError: Exception? = null
        for (type in TRY_TYPES) {
            val keyStore = try {
                KeyStore.getInstance(type).apply {
                    file.inputStream().use { load(it, storePassword.ifBlank { null }?.toCharArray()) }
                }
            } catch (e: Exception) {
                // 密码错误 / 格式不符 / 缺算法实现：记住最后一条错误后试下一格式
                loadError = e
                null
            } ?: continue

            val entry = keyStore.getEntry(
                alias,
                KeyStore.PasswordProtection(keyPassword.ifBlank { storePassword }.toCharArray()),
            )
            return when (entry) {
                is KeyStore.PrivateKeyEntry -> entry.toIdentity()
                // 能载入却找不到别名：目标密钥库已经打开，再试其他格式只会误导
                else -> throw IllegalArgumentException(
                    "密钥库中找不到可用私钥条目（别名“$alias”）。请确认别名拼写，" +
                        "或该条目是否包含私钥（复制的是证书条目而非私钥）",
                )
            }
        }
        throw IllegalArgumentException(
            "无法打开密钥库（已尝试格式 ${TRY_TYPES.joinToString("/")}）：" +
                (loadError?.message ?: loadError?.javaClass?.simpleName ?: "未知原因"),
        )
    }

    private fun KeyStore.PrivateKeyEntry.toIdentity(): SigningIdentity {
        val chain = certificateChain?.filterIsInstance<X509Certificate>().orEmpty()
        require(chain.isNotEmpty()) {
            "密钥条目“$alias”没有关联的 X.509 证书链，无法用于 APK 签名"
        }
        return SigningIdentity(name = signerName, privateKey = privateKey, certificates = chain)
    }

    companion object {
        /** 导入密钥的默认 v1 签名者名：与 Android Studio 产出的签名条目名一致 */
        const val DEFAULT_SIGNER_NAME = "CERT"

        /** 密钥库格式探测顺序（非可信输入，逐个尝试并保留最后一条失败信息） */
        private val TRY_TYPES = listOf("PKCS12", "JKS", "BKS")
    }
}
