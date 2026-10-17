package com.muxiao.iconpacker.core.packer.sign

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * 基于 Android Keystore 的签名密钥提供者（默认且唯一的实现）。
 *
 * 安全属性：
 * - 私钥由系统 Keystore 生成，**不可导出**，硬件支持时走 StrongBox/TEE；
 * - 每个设备独立身份，产物签名主体是用户本人，天然防伪造；
 * - 密钥随应用卸载一并清除（Keystore 条目属应用数据）——重装应用将生成新身份，
 *   此前已安装的图标包将无法被新身份覆盖更新（可卸载旧包后重装，图标重新应用）。
 *
 * 兼容性说明：Keystore 私钥仅可配合 "AndroidKeyStore" Provider 使用。apksig 内部
 * 通过 JCA `Signature.getInstance` 获取签名引擎，Android 7+ 的
 * AndroidKeyStoreBCWorkaround Provider 可透明承接 Keystore 密钥；若首个真机构建
 * 时出现 Provider 异常，退路是在本包内增加基于 BouncyCastle 的 [SigningKeyProvider]
 * 实现（JCA 生成可导出 PKCS#8 密钥 + 运行时自签证书，存应用私有目录），
 * 引擎侧无需改动（依赖接口）。
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
    private val subject: String = DEFAULT_SUBJECT,
    /**
     * v1 签名者名（META-INF 下 .SF/.RSA 文件名）。可按项目覆盖——例如用户希望
     * 与自己其他产物的签名条目名对齐；默认仍是历史值 [SIGNER_NAME]。
     */
    private val signerName: String = SIGNER_NAME,
) : SigningKeyProvider {

    /**
     * 获取签名身份；不存在则创建。
     *
     * 全程加锁：打包是长任务，用户可能在两个项目上连点两次打包，
     * 两个协程同时进入本方法会各自调 generateKeyPair——第二个必然因
     * "别名已存在"失败，报出误导性的 "Failed to generate key pair"。
     */
    override fun getOrCreate(): SigningIdentity = synchronized(lock) {
        (newKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry)
            ?.toIdentity()
            ?: run {
                generateKeyPairWithFallback()
                (newKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry)
                    ?.toIdentity()
                    ?: error("密钥生成后无法读取 PrivateKeyEntry（alias=$alias）")
            }
    }

    private val lock = Any()

    private fun newKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * 按 StrongBox → 普通 Keystore 顺序尝试生成。
     *
     * 关键：[KeyPairGenerator.generateKeyPair] 必须和 initialize 一起放进 try。
     * 早期版本只包了 initialize，但 `setIsStrongBoxBacked(true)` 本身几乎不会抛——
     * 真正的硬件交互发生在 generateKeyPair()，StrongBox 不支持 RSA-2048 或硬件
     * 忙时异常在那时才抛出，于是完全没走回退。这是 "Failed to generate key pair"
     * 的主要来源。
     */
    private fun generateKeyPairWithFallback() {
        var lastError: Exception? = null
        for (strongBox in STRONGBOX_FALLBACK_ORDER) {
            try {
                // 每次重建 generator：部分实现在初始化失败后不允许重新 initialize
                val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
                generator.initialize(buildSpec(strongBox))
                generator.generateKeyPair()
                return
            } catch (e: Exception) {
                lastError = e
                // 并发生成时别名可能已由另一条路径建好，此时不算失败
                if (newKeyStore().containsAlias(alias)) return
            }
        }
        throw IllegalStateException(
            "设备 Keystore 生成签名密钥失败（已依次尝试 StrongBox 与普通 Keystore）：" +
                    (lastError?.message ?: "未知原因"),
            lastError,
        )
    }

    private fun buildSpec(strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_SIZE_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=$subject"))
            // 证书三件套显式给值：多数实现有默认值，但部分厂商 ROM 的 Keystore
            // 在缺项时直接拒绝生成（同样是 "Failed to generate key pair" 的来源）。
            // NotBefore 往前一天——设备时钟偏早时，用"当前时间"起算会让自签证书
            // 处于"尚未生效"状态。
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - CERT_SKEW_MS))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + CERT_VALIDITY_MS))
            .apply {
                // setIsStrongBoxBacked 为 API 28 引入；minSdk 24 上调用会 NoSuchMethodError
                if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

    private fun KeyStore.PrivateKeyEntry.toIdentity(): SigningIdentity {
        val chain = certificateChain?.filterIsInstance<X509Certificate>() ?: emptyList()
        require(chain.isNotEmpty()) { "证书链为空（alias=$alias）" }
        return SigningIdentity(name = signerName, privateKey = privateKey, certificates = chain)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "RSA"
        private const val KEY_SIZE_BITS = 2048
        /** v1 签名者名默认值（META-INF 下 .SF/.RSA 文件名）；可按项目覆盖 */
        const val SIGNER_NAME = "ICONPACKER"
        const val DEFAULT_ALIAS = "iconpacker_signing_key_v1"
        const val DEFAULT_SUBJECT = "IconPacker Device Signer"

        /** StrongBox 优先，失败后回退普通 Keystore */
        private val STRONGBOX_FALLBACK_ORDER = listOf(true, false)

        /** 证书生效时间往前留的余量（1 天），规避设备时钟偏差 */
        private const val CERT_SKEW_MS = 24L * 60 * 60 * 1000

        /** 证书有效期（30 年）——自签身份无 CA 续期，给足即可 */
        private const val CERT_VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000
    }
}
