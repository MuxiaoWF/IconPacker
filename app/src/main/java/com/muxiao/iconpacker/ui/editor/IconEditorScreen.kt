package com.muxiao.iconpacker.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 单图标编辑页（FR-05）：裁剪 / 外形 / 背景 / 边框 / 内容缩放，实时预览，保存或还原。
 *
 * 验收对应：预览与实际打包结果一致（同一条合成路径）、编辑非破坏性可还原（原图永不修改）。
 *
 * 设计原则：**背景与边框默认关闭，绝不自动添加**。用户不主动开，
 * 产物就是「裁剪成正方形 + 铺满」的干净图标，不会再多出一圈底板。
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.iconName.ifBlank { "编辑图标" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val style = state.style

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ---- 结果预览（与打包同路径合成，所见即所得）----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val preview = state.preview
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

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 裁剪 ----
            SectionTitle("裁剪")
            Text(
                text = "拖动移动取景框，双指缩放；框内区域即最终图标（正方形）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val source = state.source
            if (source != null) {
                CropSurface(
                    source = source,
                    crop = style.crop,
                    onCropChange = { viewModel.updateStyle(style.copy(crop = it)) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(8.dp))
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

            Spacer(modifier = Modifier.height(20.dp))

            // ---- 外形（独立裁剪蒙版，不依赖背景色）----
            SectionTitle("外形")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconShape.entries.forEach { shape ->
                    FilterChip(
                        selected = style.shape == shape,
                        onClick = { viewModel.updateStyle(style.copy(shape = shape)) },
                        label = { Text(shape.label()) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

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
                Spacer(modifier = Modifier.height(8.dp))
                ColorRow(
                    colors = IconStyle.PRESET_COLORS.map { it.first },
                    selected = style.backgroundColor,
                    onSelect = { viewModel.updateStyle(style.copy(backgroundColor = it)) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                Spacer(modifier = Modifier.height(8.dp))
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
                Spacer(modifier = Modifier.height(6.dp))
                ColorRow(
                    colors = IconStyle.PRESET_BORDER_COLORS.map { it.first },
                    selected = style.borderColor,
                    onSelect = { viewModel.updateStyle(style.copy(borderColor = it)) },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- 内容缩放 ----
            SectionTitle(
                "内容缩放 ${(style.contentScale * 100).roundToInt()}%" +
                    if (style.contentScale >= 0.999f) "（铺满）" else "",
            )
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

            Spacer(modifier = Modifier.height(16.dp))

            if (state.hasEdit) {
                Text(
                    text = "当前图标已保存过编辑产物，再次保存将覆盖；还原可恢复原图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::reset) { Text("还原为原图") }
                Spacer(modifier = Modifier.height(8.dp))
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
}

/** 裁剪视图的边长 */
private val CROP_VIEWPORT = 240.dp

/** 边框默认粗细（占图标边长比例） */
private const val DEFAULT_BORDER_WIDTH = 0.04f

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
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
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ColorRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colors.forEach { color ->
            val isSelected = selected == color
            val transparent = color == android.graphics.Color.TRANSPARENT
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (transparent) Color.White else Color(color))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (transparent) {
                    Text("无", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

/**
 * 交互式裁剪视图：固定正方形取景框，源图在其下方平移缩放。
 *
 * 整个视口就是裁切区——所见即所得，不需要额外的框选矩形。
 * 手势与 [CropSpec] 的换算见 [applyCropGesture]。
 */
@Composable
private fun CropSurface(
    source: ImageBitmap,
    crop: CropSpec,
    onCropChange: (CropSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewportPx = with(LocalDensity.current) { CROP_VIEWPORT.toPx() }
    val srcW = source.width
    val srcH = source.height

    // 手势回调里必须读到最新的 crop / 回调，但 pointerInput 的 key 不能带上 crop，
    // 否则每帧重组都会重启手势检测器，拖动会断。
    val latestCrop by rememberUpdatedState(crop)
    val latestOnChange by rememberUpdatedState(onCropChange)

    // 由 CropSpec 反推「源图 → 视口」的绘制变换
    val normalized = crop.normalized()
    val side = (min(srcW, srcH) / normalized.zoom).coerceAtLeast(1f)
    val rangeX = srcW - side
    val rangeY = srcH - side
    val left = rangeX / 2f + normalized.offsetX * rangeX / 2f
    val top = rangeY / 2f + normalized.offsetY * rangeY / 2f
    val scale = viewportPx / side

    Box(
        modifier = modifier
            .size(CROP_VIEWPORT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
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

private fun IconShape.label(): String = when (this) {
    IconShape.ORIGINAL -> "原图"
    IconShape.CIRCLE -> "圆形"
    IconShape.ROUNDED_RECT -> "圆角"
    IconShape.SQUIRCLE -> "Squircle"
}
