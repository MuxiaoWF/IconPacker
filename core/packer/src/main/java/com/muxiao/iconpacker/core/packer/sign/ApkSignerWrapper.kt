package com.muxiao.iconpacker.core.packer.sign

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException

/**
 * apksig 签名封装（v1 + v2 + v3 三方案同签）。
 *
 * 依赖：apksig-android（Google 官方 apksig 的 Android 移植版，
 * `com.github.MuntashirAkon:apksig-android`，见 libs.versions.toml）。
 *
 * 为什么三方案同签：
 * - v1（JAR）：Android 7.0 以下唯一方案，minSdk 24 起仅为兼容保留；
 * - v2：Android 7.0+ 主力，整包校验、防裁剪；
 * - v3：Android 9+ 支持轮换签名证书（key rotation），为未来升级链留空间。
 *
 * 注意：输入 APK 必须**未签名**——打包管线的 ZipRewriter 会剥离模板自带的
 * META-INF/ 调试签名条目，否则 apksig 会因既有 JAR 签名报错。
 *
 * ⚠️ 移植版 API 与 AOSP 上游的差异（已对 apksig-android 4.4.0 的 classes.jar
 * 用 javap 逐一核实，勿凭上游印象改回）：
 * 1. `SignerConfig` 是 **`ApkSigner` 的嵌套类**（`ApkSigner.SignerConfig`），
 *    不是 AOSP 那样的顶层 `com.android.apksig.SignerConfig`；
 * 2. `ApkVerifier.Builder` 的方法是 **`setMinCheckedPlatformVersion(int)`**，
 *    AOSP 上游的 `setMinCheckedApiVersion` 在移植版中不存在。
 */
class ApkSignerWrapper {

    /**
     * 签名并自校验。
     *
     * @param inputApk 未签名的 APK
     * @param outputApk 签名产物输出路径（不得与输入相同）
     * @param identity 设备端签名身份
     * @return 签名者主题摘要（如 "CN=IconPacker Device Signer"），供结果页展示
     * @throws IOException 签名失败或签名后校验不通过
     */
    fun sign(inputApk: File, outputApk: File, identity: SigningIdentity): String {
        require(inputApk != outputApk) { "输入与输出不能是同一文件" }
        require(inputApk.isFile) { "输入 APK 不存在：${inputApk.path}" }

        val signerConfig = ApkSigner.SignerConfig.Builder(
            identity.name,
            identity.privateKey,
            identity.certificates,
        ).build()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(MIN_SDK)
            .build()
        signer.sign()

        if (!outputApk.isFile) {
            throw IOException("apksig 未产出文件：${outputApk.path}")
        }
        return verify(outputApk)
    }

    /**
     * 签名后自校验：确认产物在目标平台范围内可通过校验，并返回签名者摘要
     * （供打包结果页展示"签名者：CN=..."）。
     */
    fun verify(apk: File): String {
        val result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(MIN_SDK)
            .build()
            .verify()
        if (!result.isVerified) {
            val errors = result.errors?.joinToString("; ") ?: "未知原因"
            throw IOException("签名产物校验失败：$errors")
        }
        val cert = result.signerCertificates?.firstOrNull()
        return cert?.subjectX500Principal?.toString() ?: identityFallback
    }

    companion object {
        /** 与工程 minSdk 一致：v1 兼容下限，v2/v3 由 apksig 按平台能力取舍 */
        const val MIN_SDK = 24
        private const val identityFallback = "unknown"
    }
}
