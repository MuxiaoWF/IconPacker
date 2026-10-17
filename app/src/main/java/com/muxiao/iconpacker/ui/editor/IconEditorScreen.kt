package com.muxiao.iconpacker.ui.editor

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.core.packer.engine.CropSpec
import com.muxiao.iconpacker.core.packer.engine.IconShape
import com.muxiao.iconpacker.core.packer.engine.IconStyle
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.ColorSwatch
import com.muxiao.iconpacker.ui.components.ColorSwatchRow
import com.muxiao.iconpacker.ui.components.SectionHeader
import com.muxiao.iconpacker.ui.components.iconShapeLabel
import com.muxiao.iconpacker.ui.theme.Spacing
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconEditorScreen(
    packId: Long,
    slot: Int,
    onBack: () -> Unit,
    viewModel: IconEditorViewModel = viewModel(factory = IconEditorViewModel.factory(packId, slot)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    AppScaffold(
        title = state.iconName.ifBlank { "编辑图标" },
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
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ---- 结果预览（与打包同路径合成，所见即所得）----
        PreviewPanel(preview = state.preview, modifier = Modifier)

        // ---- 裁剪 ----
        SectionHeader("裁剪")
        Text(
            text = "拖动移动取景框，双指缩放；框内区域即最终图标（正方形）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val source = state.source
        if (source != null) {
            CropSurface(
                source = source,
                crop = style.crop,
                onCropChange = { viewModel.updateStyle(style.copy(crop = it)) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "缩放 ${"%.1f".format(style.crop.zoom)}×",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { viewModel.updateStyle(style.copy(crop = CropSpec.DEFAULT)) },
                    enabled = !style.crop.isDefault,
                ) { Text("重置裁剪") }
            }
        } else {
            Text("源图加载中…", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 外形（独立裁剪蒙版，不依赖背景色）----
        SectionHeader("外形")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconShape.entries.forEach { shape ->
                FilterChip(
                    selected = style.shape == shape,
                    onClick = { viewModel.updateStyle(style.copy(shape = shape)) },
                    label = { Text(shape.iconShapeLabel()) },
                )
            }
        }

        // ---- 背景色（默认关闭：不开就不画底板，不会出现"多余的边框"）----
        SwitchRow(
            title = "背景色",
            subtitle = if (style.hasBackground) "已开启：内容四周会填色" else "默认不加背景，只保留图标本身",
            checked = style.hasBackground,
            onCheckedChange = { on ->
                val color = if (on) {
                    IconStyle.PRESET_COLORS.first { it.first != android.graphics.Color.TRANSPARENT }.first
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
        if (style.hasBackground) {
            ColorSwatchRow(
                entries = IconStyle.PRESET_COLORS.map { (preset, label) -> ColorSwatch(preset, label) },
                selected = style.backgroundColor,
                onSelect = { viewModel.updateStyle(style.copy(backgroundColor = it)) },
            )
        }

        // ---- 边框（默认关闭）----
        SwitchRow(
            title = "边框",
            subtitle = if (style.hasBorder) "已开启：沿外形描边" else "默认不加边框",
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
        if (style.hasBorder) {
            Text(
                text = "粗细 ${(style.borderWidth * 100).roundToInt()}%（占图标边长）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = style.borderWidth,
                onValueChange = { viewModel.updateStyle(style.copy(borderWidth = it)) },
                valueRange = 0.01f..0.12f,
            )
            Text("颜色", style = MaterialTheme.typography.labelMedium)
            ColorSwatchRow(
                entries = IconStyle.PRESET_BORDER_COLORS.map { (preset, label) -> ColorSwatch(preset, label) },
                selected = style.borderColor,
                onSelect = { viewModel.updateStyle(style.copy(borderColor = it)) },
            )
        }

        // ---- 内容缩放 ----
        SectionHeader(
            "内容缩放 ${(style.contentScale * 100).roundToInt()}%" +
                if (style.contentScale >= 0.999f) "（铺满）" else "",
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Slider(
                value = style.contentScale,
                onValueChange = { viewModel.updateStyle(style.copy(contentScale = it)) },
                valueRange = 0.3f..1f,
            )
            if (!style.hasBackground && !style.hasBorder && style.contentScale < 0.999f) {
                Text(
                    text = "未开启背景/边框时缩小内容会留出透明边距。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.hasEdit) {
            Text(
                text = "当前图标已保存过编辑产物，再次保存将覆盖；还原可恢复原图。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 预览面板：固定高度上限内水平居中，预览图不随内容长度跳动 */
@Composable
private fun PreviewPanel(preview: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = "图标预览",
                modifier = Modifier.size(152.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("无法生成预览", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 底部操作条：主操作"保存"常驻，有编辑历史时并列"还原为原图" */
@Composable
private fun EditorBottomBar(
    state: IconEditorUiState,
    viewModel: IconEditorViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (state.hasEdit) {
            OutlinedButton(
                onClick = viewModel::reset,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("还原为原图") }
        }
        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.saving) "保存中…" else "保存")
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 交互式裁剪视图：正方形取景框，源图在其下方平移缩放。
 *
 * 整个视口就是裁切区——所见即所得，不需要额外的框选矩形。
 * 视口宽度取可用宽度的 85%（aspectRatio 锁正方形），不再写死 240dp。
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
            .fillMaxWidth(0.85f)
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
