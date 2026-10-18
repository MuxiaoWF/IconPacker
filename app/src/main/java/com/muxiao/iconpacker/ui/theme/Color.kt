package com.muxiao.iconpacker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 品牌色：与模板应用图标（#1565C0）保持一致。
 *
 * 界面配色由品牌蓝全量派生（见 [LightColors] / [DarkColors]），
 * 不启用动态取色——用户在做图标包，界面底色不干扰预览里的图标色彩。
 */
val BrandBlue = Color(0xFF1565C0)
val BrandBlueDark = Color(0xFFA8C8FF)

/**
 * 应用启动图标的底色（design/README.md：GameBoy 墨绿 #1E2B1A）。
 *
 * 引导页的品牌标需要一块"和图标的背景同色"的底板才能贴住前景 PNG；
 * 不能直接用 `R.mipmap.ic_launcher`——它是自适应图标 XML，
 * painterResource 只接受 VectorDrawable 与位图，运行期会抛
 * IllegalArgumentException。
 */
val BrandInk = Color(0xFF1E2B1A)

/**
 * 语义色板：Material 3 的 colorScheme 表达不了"页面底色 / 卡片底色 / 分隔线"
 * 这类版式语义，且同一角色在浅色与深色下的相对明度关系是相反的
 * （浅色：卡片比页面亮；深色：卡片比页面亮——方向一致，但取值必须分主题给）。
 *
 * 因此这里单独定义一层组件令牌，经 [LocalAppPalette] 注入，供卡片、分隔线、
 * 状态徽标直接取用；避免各页面用 `surfaceContainerHighest` 之类的
 * 层级猜测导致"整屏灰块"。
 */
@Immutable
data class AppPalette(
    /** 页面底色 */
    val page: Color,
    /** 内容卡片底色 */
    val card: Color,
    /** 次级容器（输入框内嵌块、未选中块） */
    val cardMuted: Color,
    /** 分隔线 / 细描边 */
    val divider: Color,
    /** 缩略图占位底色 */
    val thumbnail: Color,
    /** 成功语义（已是最新、已映射） */
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** 警告语义（未设置图标、待补全） */
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

/** 浅色：浅灰页面 + 纯白卡片，靠"卡片更亮"建立层级 */
internal val LightPalette = AppPalette(
    page = Color(0xFFF3F5F8),
    card = Color(0xFFFFFFFF),
    cardMuted = Color(0xFFF1F3F7),
    divider = Color(0xFFE2E6ED),
    thumbnail = Color(0xFFEDF0F5),
    success = Color(0xFF1B7F4B),
    successContainer = Color(0xFFDCF3E4),
    onSuccessContainer = Color(0xFF06301A),
    warning = Color(0xFF8A5A00),
    // 警告底色只做"浅色提示"，不做整块高饱和色——Build 页一屏可能出现两三张
    warningContainer = Color(0xFFFFF1DB),
    onWarningContainer = Color(0xFF2B1A00),
)

/** 深色：深灰页面 + 略亮卡片，层级方向与浅色一致（卡片始终"浮起"） */
internal val DarkPalette = AppPalette(
    page = Color(0xFF0E1116),
    card = Color(0xFF181C22),
    cardMuted = Color(0xFF1F242B),
    divider = Color(0xFF2A3038),
    thumbnail = Color(0xFF23282F),
    success = Color(0xFF7BD3A0),
    successContainer = Color(0xFF12341F),
    onSuccessContainer = Color(0xFFC6F0D4),
    warning = Color(0xFFFFC46B),
    warningContainer = Color(0xFF3D2A00),
    onWarningContainer = Color(0xFFFFE0A3),
)

val LocalAppPalette = staticCompositionLocalOf { LightPalette }

internal val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE8FB),
    onPrimaryContainer = Color(0xFF0B2C57),
    secondary = Color(0xFF00796B),
    onSecondary = Color(0xFFFFFFFF),
    // 选中态的容器色（FilterChip 等 M3 组件默认取 secondaryContainer）：
    // 取品牌蓝的浅阶而非青绿，避免"蓝色主色 + 绿色选中"的双主题观感
    secondaryContainer = Color(0xFFDCE6F7),
    onSecondaryContainer = Color(0xFF10294A),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = LightPalette.warningContainer,
    onTertiaryContainer = LightPalette.onWarningContainer,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF410E0B),
    background = LightPalette.page,
    onBackground = Color(0xFF171A1F),
    surface = LightPalette.page,
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF5A6069),
    outline = Color(0xFFC2C8D2),
    outlineVariant = Color(0xFFE2E6ED),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = BrandBlueDark,
    surfaceDim = Color(0xFFDDE1E7),
    surfaceBright = Color(0xFFFDFEFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFF3F5F8),
    surfaceContainerHigh = Color(0xFFEDF0F4),
    surfaceContainerHighest = Color(0xFFE7EAEF),
)

internal val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF002F5C),
    primaryContainer = Color(0xFF1F4C86),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1E324B),
    onSecondaryContainer = Color(0xFFD8E6FF),
    tertiary = DarkPalette.warning,
    onTertiary = Color(0xFF3D2A00),
    tertiaryContainer = DarkPalette.warningContainer,
    onTertiaryContainer = DarkPalette.onWarningContainer,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkPalette.page,
    onBackground = Color(0xFFE6E9EE),
    surface = DarkPalette.page,
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF2A3038),
    onSurfaceVariant = Color(0xFFA8B0BA),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF2A3038),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E9EE),
    inverseOnSurface = Color(0xFF22262C),
    inversePrimary = BrandBlue,
    surfaceDim = Color(0xFF0B0E13),
    surfaceBright = Color(0xFF323841),
    surfaceContainerLowest = Color(0xFF0A0D11),
    surfaceContainerLow = DarkPalette.card,
    surfaceContainer = Color(0xFF1C2128),
    surfaceContainerHigh = Color(0xFF242A31),
    surfaceContainerHighest = Color(0xFF2C333B),
)
