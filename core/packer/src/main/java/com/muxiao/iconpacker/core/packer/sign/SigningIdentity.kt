package com.muxiao.iconpacker.core.packer.sign

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * 签名身份：apksig `SignerConfig` 的直接输入。
 *
 * @param name 签名者名称（决定 META-INF 下的 .SF/.RSA 文件名，固定标识即可，
 *             真正的身份由证书决定）
 * @param privateKey RSA 2048 私钥
 * @param certificates 证书链（[0] 为签名证书，自签名场景下只有一个元素）
 */
data class SigningIdentity(
    val name: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
) {
    init {
        require(certificates.isNotEmpty()) { "证书链不能为空" }
    }
}

/**
 * 签名密钥提供者。
 *
 * 设计约束（见 docs/android-verification.md）：**不使用任何内置/硬编码密钥**——内置密钥可被提取，
 * 导致他人可伪造"本应用生成"的图标包（签名即更新身份，同签名不同包的覆盖安装
 * 是真实攻击面）。密钥必须在用户设备端生成，或由用户导入自己的密钥库文件。
 *
 * 现有两类实现：
 * - [AndroidKeystoreKeyProvider]（系统 Keystore，私钥不可导出，默认）；
 * - [ImportedKeystoreProvider]（用户导入的密钥库文件，跨设备同身份）。
 */
interface SigningKeyProvider {
    /** 获取签名身份；不存在则创建。重复调用返回同一身份。 */
    fun getOrCreate(): SigningIdentity
}
