package com.muxiao.iconpacker.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新（GitHub Releases 为源）。
 *
 * 隐私口径：**只在用户点击"检查更新"时发起一次请求**，不自动轮询、不上报任何
 * 设备信息（无安装列表、无标识符，GitHub API 仅要求 User-Agent）。这是全应用
 * 唯一的网络行为，manifest 的 INTERNET 权限仅为此存在。
 *
 * 不引第三方 HTTP/JSON 库：一次 GET + org.json（平台自带）就够了，体积零增量。
 */
object UpdateChecker {

    /** 发布源仓库（GitHub Releases）；只有一个调用点，不做成设置项 */
    const val REPO = "MuxiaoWF/IconPacker"

    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        /** 发布说明原文（可能为空串） */
        val notes: String,
    )

    sealed interface UpdateResult {
        /** 远端没有比当前更新的版本 */
        data class UpToDate(val latestTag: String) : UpdateResult

        /** 发现更新的版本 */
        data class Available(val release: ReleaseInfo) : UpdateResult

        /** 网络失败/解析失败/尚无发布 */
        data class Failed(val reason: String) : UpdateResult
    }

    /**
     * 检查 [currentVersionName] 之后是否有新版本。
     *
     * 版本比较：取 `tag_name` 去掉 v/V 前缀后按 "." 逐段数字比较；无法数字化的段按 0。
     * 相等或更旧一律报"已是最新"——发布页偶尔会比本地旧（回滚发布），不误导用户。
     */
    suspend fun check(currentVersionName: String): UpdateResult = withContext(Dispatchers.IO) {
        runCatching { fetchLatest() }.fold(
            onSuccess = { release ->
                if (isNewer(release.tagName, currentVersionName)) {
                    UpdateResult.Available(release)
                } else {
                    UpdateResult.UpToDate(release.tagName)
                }
            },
            onFailure = { UpdateResult.Failed(it.message.orEmpty().ifBlank { it.javaClass.simpleName }) },
        )
    }

    private fun fetchLatest(): ReleaseInfo {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API 强制要求 User-Agent；不带会被 403
            connection.setRequestProperty("User-Agent", "IconPacker-UpdateCheck")
            val code = connection.responseCode
            if (code == 404) {
                throw IllegalStateException("仓库还没有发布任何版本")
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val json = JSONObject(connection.inputStream.buffered().use { it.readBytes().decodeToString() })
            return ReleaseInfo(
                tagName = json.optString("tag_name"),
                htmlUrl = json.optString("html_url").ifBlank { "https://github.com/$REPO/releases" },
                notes = json.optString("body", "").take(500),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** internal：单测守住版本比较口径（v 前缀/非数字段/段数不齐） */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val c = numericSegments(candidate)
        val cur = numericSegments(current)
        for (i in 0 until maxOf(c.size, cur.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numericSegments(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
