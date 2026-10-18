package com.muxiao.iconpacker.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.core.packer.engine.CropSpec
import com.muxiao.iconpacker.core.packer.engine.IconShape
import com.muxiao.iconpacker.core.packer.engine.IconStyle
import com.muxiao.iconpacker.ui.components.AppChip
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.ColorSwatch
import com.muxiao.iconpacker.ui.components.ColorSwatchRow
import com.muxiao.iconpacker.ui.components.SectionCard
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.components.colorSwatchLabelRes
import com.muxiao.iconpacker.ui.components.iconShapeLabel
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.LocalAppPalette
import com.muxiao.iconpacker.ui.theme.Spacing
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** 边框默认粗细（占图标边长比例） */
private const val DEFAULT_BORDER_WIDTH = 0.04f

/**
 * 单图标编辑页（FR-05）：裁剪 / 外形 / 背景 / 边框 / 内容缩放，实时预览，保存或还原。
 *
 * 验收对应：预览与实际打包结果一致（同一条合成路径）、编辑非破坏性可还原（原图永不修改）。
 *
 * 设计原则：**背景与边框默认关闭，绝不自动添加**。用户不主动开，
 * 产物就是「裁剪成正方形 + 铺满」的干净图标，不会再多出一圈底板。
 *
 * 布局：保存（及还原）置于 bottomBar——长表单滚到底不必再回来找保存；
 * 内容区垂直滚动，裁剪取景框宽度自适应（小屏不溢出，大屏不收窄）。
 */
@Composable
fun IconEditorScreen(
    target: IconEditorTarget,
    onBack: () -> Unit,
    /** 保存成功后的回调（在 [onBack] 之前触发）；用于把结果带回调用方 */
    onSaved: (() -> Unit)? = null,
    /** 覆盖顶栏标题；为空则用图标自身的名称 */
    titleOverride: String? = null,
    viewModel: IconEditorViewModel = viewModel(factory = IconEditorViewModel.factory(target)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 系统返回键与顶栏返回同义。图标包图标是在表单之上以浮层方式编辑的，
    // 不接管返回键就会把整个页面退掉（连带丢掉用户填了一半的表单）
    BackHandler(onBack = onBack)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it.asString(context))
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.finished) {
        if (state.finished) {
            onSaved?.invoke()
            onBack()
        }
    }

    AppScaffold(
        title = titleOverride ?: state.iconName.ifBlank { stringResource(R.string.editor_title) },
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        bottomBar = { if (!state.loading) EditorBottomBar(state, viewModel) },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            EditorBody(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** 编辑区主体：预览 → 裁剪 → 外形 → 背景 → 边框 → 内容缩放 → 编辑提示 */
@Composable
private fun EditorBody(
    state: IconEditorUiState,
    viewModel: IconEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val style = state.style
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Layout.cardGap),
    ) {
        // ---- 结果预览（与打包同路径合成，所见即所得）----
        SectionCard(contentPadding = PaddingValues(Spacing.md)) {
            PreviewPanel(preview = state.preview)
            Spacer(modifier = Modifier.size(Spacing.sm))
            Text(
                text = stringResource(R.string.editor_preview_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 裁剪 ----
        SectionCard(
            title = stringResource(R.string.editor_section_crop),
            description = stringResource(R.string.editor_crop_hint),
        ) {
            val source = state.source
            if (source != null) {
                CropSurface(
                    source = source,
                    crop = style.crop,
                    onCropChange = { viewModel.updateStyle(style.copy(crop = it)) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.editor_zoom_label,
                            // 固定 US 小数点格式：本地化范围仅 zh/en，且这是缩放倍率的数值标注
                            String.format(Locale.US, "%.1f", style.crop.zoom),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { viewModel.updateStyle(style.copy(crop = CropSpec.DEFAULT)) },
                        enabled = !style.crop.isDefault,
                    ) { Text(stringResource(R.string.editor_crop_reset)) }
                }
            } else {
                Text(
                    text = stringResource(R.string.editor_source_loading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- 外形（独立裁剪蒙版，不依赖背景色）----
        SectionCard(title = stringResource(R.string.shape_batch_shape)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IconShape.entries.forEach { shape ->
                    AppChip(
                        selected = style.shape == shape,
                        label = shape.iconShapeLabel(),
                        onClick = { viewModel.updateStyle(style.copy(shape = shape)) },
                    )
                }
            }
        }

        // ---- 背景色（默认关闭：不开就不画底板，不会出现"多余的边框"）----
        SectionCard(
            title = stringResource(R.string.shape_batch_bg),
            description = if (style.hasBackground) {
                stringResource(R.string.editor_background_on)
            } else {
                stringResource(R.string.editor_background_off)
            },
            trailing = {
                Switch(
                    checked = style.hasBackground,
                    onCheckedChange = { on ->
                        val color = if (on) {
                            IconStyle.PRESET_COLORS
                                .first { it.first != android.graphics.Color.TRANSPARENT }.first
                        } else {
                            android.graphics.Color.TRANSPARENT
                        }
                        viewModel.updateStyle(
                            style.copy(
                                backgroundColor = color,
                                // 铺满状态下补上底板会把内容顶到边缘，自动收到安全区
                                contentScale = if (on && style.contentScale >= 0.999f) {
                                    IconStyle.SAFE_SCALE
                                } else {
                                    style.contentScale
                                },
                            ),
                        )
                    },
                )
            },
        ) {
            if (style.hasBackground) {
                ColorSwatchRow(
                    entries = IconStyle.PRESET_COLORS.map { (preset, _) ->
                        ColorSwatch(preset, stringResource(colorSwatchLabelRes(preset)))
                    },
                    selected = style.backgroundColor,
                    onSelect = { viewModel.updateStyle(style.copy(backgroundColor = it)) },
                )
            }
        }

        // ---- 边框（默认关闭）----
        SectionCard(
            title = stringResource(R.string.editor_group_border),
            description = if (style.hasBorder) {
                stringResource(R.string.editor_border_on)
            } else {
                stringResource(R.string.editor_border_off)
            },
            trailing = {
                Switch(
                    checked = style.hasBorder,
                    onCheckedChange = { on ->
                        viewModel.updateStyle(
                            style.copy(
                                borderWidth = if (on) DEFAULT_BORDER_WIDTH else 0f,
                                contentScale = if (on && style.contentScale >= 0.999f) {
                                    IconStyle.SAFE_SCALE
                                } else {
                                    style.contentScale
                                },
                            ),
                        )
                    },
                )
            },
        ) {
            if (style.hasBorder) {
                Text(
                    text = stringResource(R.string.editor_border_width, (style.borderWidth * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = style.borderWidth,
                    onValueChange = { viewModel.updateStyle(style.copy(borderWidth = it)) },
                    valueRange = 0.01f..0.12f,
                )
                Spacer(modifier = Modifier.size(Spacing.xs))
                Text(
                    text = stringResource(R.string.editor_border_color),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ColorSwatchRow(
                    entries = IconStyle.PRESET_BORDER_COLORS.map { (preset, _) ->
                        ColorSwatch(preset, stringResource(colorSwatchLabelRes(preset)))
                    },
                    selected = style.borderColor,
                    onSelect = { viewModel.updateStyle(style.copy(borderColor = it)) },
                )
            }
        }

        // ---- 内容缩放：百分比放在卡片尾部，滑杆独占一行，数值与滑块不抢位置 ----
        SectionCard(
            title = stringResource(R.string.editor_group_scale),
            trailing = {
                Text(
                    text = "${(style.contentScale * 100).roundToInt()}%" +
                        if (style.contentScale >= 0.999f) stringResource(R.string.editor_scale_fill) else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        ) {
            Slider(
                value = style.contentScale,
                onValueChange = { viewModel.updateStyle(style.copy(contentScale = it)) },
                valueRange = 0.3f..1f,
            )
            if (!style.hasBackground && !style.hasBorder && style.contentScale < 0.999f) {
                Text(
                    text = stringResource(R.string.editor_scale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.hasEdit) {
            Text(
                text = stringResource(R.string.editor_overwrite_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
        Spacer(modifier = Modifier.size(Spacing.sm))
    }
}

/**
 * 预览面板：棋盘格底 + 居中方形预览。
 *
 * 棋盘格是"透明"的通用语汇——图标本身可能带透明通道，用纯色底会让人
 * 误以为成品有一块灰底板。容器 148dp、预览框 116dp、图内再留 8dp，
 * 三层递减保证预览既醒目又不会顶满卡片（首屏要给裁剪留出空间）。
 */
@Composable
private fun PreviewPanel(preview: ImageBitmap?, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.cardMuted),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(116.dp)
                .clip(MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Checkerboard(modifier = Modifier.fillMaxSize())
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = stringResource(R.string.cd_editor_preview),
                    modifier = Modifier.fillMaxSize().padding(Spacing.sm),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = stringResource(R.string.editor_preview_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 透明底棋盘格：10dp 方格，用卡片色与次级容器色交替，明暗两套主题下都保持低对比 */
@Composable
private fun Checkerboard(modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Canvas(modifier = modifier) {
        val cell = 10.dp.toPx()
        var row = 0
        var y = 0f
        while (y < size.height) {
            var col = 0
            var x = 0f
            while (x < size.width) {
                drawRect(
                    color = if ((row + col) % 2 == 0) palette.card else palette.cardMuted,
                    topLeft = Offset(x, y),
                    size = Size(minOf(cell, size.width - x), minOf(cell, size.height - y)),
                )
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

/**
 * 底部操作条：主操作"保存"常驻，有编辑历史时并列"还原为原图"。
 *
 * 两者同一行、同一高度（48dp）：旧版上下两行各自铺满，两个按钮等宽等高，
 * 用户要读完整行文字才知道哪个是主操作。现在主按钮占更大权重，副按钮描边。
 */
@Composable
private fun EditorBottomBar(
    state: IconEditorUiState,
    viewModel: IconEditorViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.hasEdit) {
            OutlinedButton(
                onClick = viewModel::reset,
                modifier = Modifier.weight(1f).heightIn(min = Layout.touchTarget),
            ) { Text(stringResource(R.string.editor_restore)) }
        }
        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier
                .weight(if (state.hasEdit) 1.2f else 1f)
                .heightIn(min = Layout.touchTarget),
        ) {
            Text(if (state.saving) stringResource(R.string.editor_saving) else stringResource(R.string.common_save))
        }
    }
}

/**
 * 交互式裁剪视图：正方形取景框，源图在其下方平移缩放。
 *
 * 整个视口就是裁切区——所见即所得，不需要额外的框选矩形。
 * 视口宽度取卡片内容宽度的 72%（aspectRatio 锁正方形）：既够手指拖拽，
 * 也不至于把整张卡片撑到一屏只放得下它一个。
 * 手势回调里必须读到最新的 crop / 回调，但 pointerInput 的 key 不能带上 crop，
 * 否则每帧重组都会重启手势检测器，拖动会断。
 */
@Composable
private fun CropSurface(
    source: ImageBitmap,
    crop: CropSpec,
    onCropChange: (CropSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val srcW = source.width
    val srcH = source.height

    val latestCrop by rememberUpdatedState(crop)
    val latestOnChange by rememberUpdatedState(onCropChange)

    // 由 CropSpec 反推「源图 → 视口」的绘制变换
    val normalized = crop.normalized()
    val side = (min(srcW, srcH) / normalized.zoom).coerceAtLeast(1f)
    val rangeX = srcW - side
    val rangeY = srcH - side
    val left = rangeX / 2f + normalized.offsetX * rangeX / 2f
    val top = rangeY / 2f + normalized.offsetY * rangeY / 2f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(0.72f)
            .aspectRatio(1f),
    ) {
        val viewportPx = with(LocalDensity.current) { maxWidth.toPx() }
        val scale = viewportPx / side
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .pointerInput(srcW, srcH, viewportPx) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        latestOnChange(
                            latestCrop.afterGesture(pan.x, pan.y, zoomChange, srcW, srcH, viewportPx),
                        )
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = source,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset((-left * scale).roundToInt(), (-top * scale).roundToInt()),
                    dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
