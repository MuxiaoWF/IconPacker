package com.muxiao.iconpacker.ui.mapping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.core.packer.engine.IconShape
import com.muxiao.iconpacker.core.packer.engine.IconStyle
import com.muxiao.iconpacker.data.IconAssetEntity
import com.muxiao.iconpacker.ui.IconImage
import com.muxiao.iconpacker.ui.components.ColorSwatch
import com.muxiao.iconpacker.ui.components.ColorSwatchRow
import com.muxiao.iconpacker.ui.components.SectionHeader
import com.muxiao.iconpacker.ui.components.ScrollableDialogContent
import com.muxiao.iconpacker.ui.components.ToggleInfoRow
import com.muxiao.iconpacker.ui.components.colorSwatchLabelRes
import com.muxiao.iconpacker.ui.components.iconShapeLabel
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 图标库 Tab：网格展示全部已导入图标，每个图标标注"供哪个 App 使用"（FR-04 反查）。
 *
 * 数据来源：MappingUiState.iconTargets（槽位 → 应用列表），由 VM 从映射表派生——
 * 图标与应用的对应关系本就存在于映射表，此处只做展示，不引入冗余绑定。
 *
 * **目标未安装也算"被用上"**：预置映射同样是映射，只是要等应用装上来
 * 才在桌面生效。把它们排除在外，导入的图标包会大半显示"未使用"，与事实相反
 * （见 `IconUsageIndex`）。这类图标用第二档颜色标注，与"真·未使用"区分开。
 */
@Composable
fun IconLibraryTab(
    icons: List<IconAssetEntity>,
    iconTargets: Map<Int, List<AppRow>>,
    onIconClick: (IconAssetEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(icons.size, key = { index -> icons[index].id }) { index ->
            val icon = icons[index]
            val targets = iconTargets[icon.slot].orEmpty()
            val anyInstalled = targets.any { it.installed }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 图标整体落在固定网格单元中居中：大图不出界，小图不贴边
                IconImage(
                    path = icon.editedPath ?: icon.sourcePath,
                    contentDescription = icon.originalName,
                    modifier = Modifier
                        .size(IconSize.grid)
                        .clickable { onIconClick(icon) },
                )
                Text(
                    text = if (targets.isEmpty()) {
                        stringResource(R.string.mapping_unused_label)
                    } else {
                        stringResource(R.string.mapping_targets_count, targets.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        targets.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                        // 目标都还没装：映射已在，只是要等应用装上来（不是"未使用"）
                        !anyInstalled -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/**
 * 图标详情：使用该图标的应用列表（名称 + 包名）+ 映射编辑入口。
 *
 * 这里是**从图标出发反向编辑映射**的地方（FR-04）：映射工作台的"应用"页
 * 只能从已安装应用出发，而图标库里躺着的大多是目标未安装的预置映射
 * （ZIP 导入的必然结果），那些映射此前在界面上既看不见、也改不了。
 * 现在每条目标都可点开改包名，另有"添加映射"给这张图指定任意包名。
 *
 * @param onAddTarget 给这张图标新增一条映射（输入包名）
 * @param onEditTarget 改写某条映射的目标包名；槽位不变，仍是这张图标
 * @param onRemoveTarget 仅移除这条映射，图标本身保留
 */
@Composable
fun IconDetailDialog(
    icon: IconAssetEntity,
    targets: List<AppRow>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddTarget: () -> Unit,
    onEditTarget: (AppRow) -> Unit,
    onRemoveTarget: (AppRow) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(icon.originalName) },
        text = {
            ScrollableDialogContent {
                Text(
                    text = if (targets.isEmpty()) {
                        stringResource(R.string.mapping_detail_no_targets)
                    } else {
                        stringResource(R.string.mapping_detail_header, targets.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                targets.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditTarget(app) }
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // 未安装的目标取不到可读名称，直接显示包名——重复两行没有意义
                            Text(
                                text = if (app.installed) app.label else app.packageName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (app.installed) {
                                    app.packageName
                                } else {
                                    stringResource(
                                        R.string.mapping_target_pending_install,
                                        app.packageName,
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEditTarget(app) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.cd_edit_target),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { onRemoveTarget(app) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.cd_remove_target),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                TextButton(onClick = onAddTarget) {
                    Text(stringResource(R.string.mapping_add_target_by_name))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.mapping_edit_icon)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.common_delete_icon), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/**
 * 批量统一形状对话框（FR-05）：外形 + 背景色，从原图合成。
 *
 * 背景色默认**透明**——历史默认值是品牌蓝，用户直接点"应用"就会给每张图标
 * 套上一圈蓝色底板，看起来像"莫名其妙的蓝边框"。现在不主动加任何底板。
 *
 * @param onApply (外形, 背景色, 是否连同已单独编辑的图标一起重做)
 */
@Composable
fun ShapeBatchDialog(
    onApply: (IconShape, Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var shape by remember { mutableStateOf(IconShape.ROUNDED_RECT) }
    var color by remember { mutableIntStateOf(android.graphics.Color.TRANSPARENT) }
    var overwriteEdited by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mapping_unify_shape)) },
        text = {
            ScrollableDialogContent(contentSpacing = Spacing.md) {
                Text(
                    text = stringResource(R.string.shape_batch_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionHeader(stringResource(R.string.shape_batch_shape))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconShape.entries.filter { it != IconShape.ORIGINAL }.forEach { option ->
                        FilterChip(
                            selected = shape == option,
                            onClick = { shape = option },
                            label = { Text(option.iconShapeLabel()) },
                        )
                    }
                }
                SectionHeader(stringResource(R.string.shape_batch_bg))
                Text(
                    text = if (color == android.graphics.Color.TRANSPARENT) {
                        stringResource(R.string.shape_batch_bg_none)
                    } else {
                        stringResource(R.string.shape_batch_bg_filled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ColorSwatchRow(
                    entries = IconStyle.PRESET_COLORS.map { (preset, _) ->
                        ColorSwatch(preset, stringResource(colorSwatchLabelRes(preset)))
                    },
                    selected = color,
                    onSelect = { color = it },
                )

                ToggleInfoRow(
                    title = stringResource(R.string.shape_batch_overwrite),
                    subtitle = stringResource(R.string.shape_batch_overwrite_hint),
                    leading = {
                        Checkbox(checked = overwriteEdited, onCheckedChange = { overwriteEdited = it })
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(shape, color, overwriteEdited) }) {
                Text(stringResource(R.string.common_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
