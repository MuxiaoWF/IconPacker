package com.muxiao.iconpacker.ui.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.core.packer.engine.IconProcessor
import com.muxiao.iconpacker.core.packer.engine.IconStyle
import com.muxiao.iconpacker.data.PackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 图标编辑状态（FR-05） */
data class IconEditorUiState(
    val loading: Boolean = true,
    val iconName: String = "",
    val style: IconStyle = IconStyle(),
    /** 合成结果预览（最终会写进图标包的样子） */
    val preview: ImageBitmap? = null,
    /** 完整源图，供裁剪视图交互使用；为空表示尚未解码完成 */
    val source: ImageBitmap? = null,
    val saving: Boolean = false,
    /** 当前是否已有编辑产物（保存将覆盖） */
    val hasEdit: Boolean = false,
    val message: String? = null,
    val finished: Boolean = false,
)

/**
 * 单图标编辑器 VM。
 *
 * 编辑始终从**原图**出发（非破坏性，FR-05 验收 3）；
 * 预览与保存走同一条合成路径（IconProcessor），保证所见即所得。
 */
class IconEditorViewModel(
    private val repository: PackRepository,
    private val packId: Long,
    private val slot: Int,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IconEditorUiState())
    val uiState: StateFlow<IconEditorUiState> = _uiState.asStateFlow()

    private val styleFlow = MutableStateFlow(IconStyle())
    private val processor = IconProcessor()
    private var originalPath: String? = null

    /**
     * 解码后的源图常驻内存，供裁剪视图与预览复用。
     *
     * 拖动裁剪框时按帧重算，若每帧都从文件重解码 4K JPEG 必然掉帧；
     * 长边已由 [IconProcessor.decodeForEditing] 压到 1080，约 3MB，可以放心常驻。
 */
    private var sourceBitmap: Bitmap? = null

    init {
        viewModelScope.launch {
            val icon = repository.getIcon(packId, slot)
            if (icon == null) {
                _uiState.update { it.copy(loading = false, message = "图标不存在或已删除") }
                return@launch
            }
            originalPath = icon.sourcePath
            _uiState.update {
                it.copy(
                    loading = false,
                    iconName = icon.originalName,
                    hasEdit = icon.editedPath != null,
                )
            }
            val decoded = withContext(Dispatchers.IO) {
                runCatching { processor.decodeForEditing(icon.sourcePath) }.getOrNull()
            }
            if (decoded != null) {
                sourceBitmap = decoded
                _uiState.update { it.copy(source = decoded.asImageBitmap()) }
            }
            renderPreview(styleFlow.value)
        }
        // collectLatest：连续拖动滑块/裁剪框时自动取消上一次预览渲染
        viewModelScope.launch {
            styleFlow.collectLatest { style ->
                _uiState.update { it.copy(style = style) }
                renderPreview(style)
            }
        }
    }

    fun updateStyle(style: IconStyle) {
        styleFlow.value = style
    }

    fun save() {
        val path = originalPath ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            runCatching { repository.saveIconEdit(packId, slot, styleFlow.value) }
                .onSuccess {
                    _uiState.update { it.copy(saving = false, hasEdit = true, message = "已保存", finished = true) }
                }
                .onFailure {
                    _uiState.update { it.copy(saving = false, message = "保存失败：${it.message}") }
                }
        }
    }

    fun reset() {
        viewModelScope.launch {
            repository.clearIconEdit(packId, slot)
            _uiState.update { it.copy(hasEdit = false, message = "已还原为原图") }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun renderPreview(style: IconStyle) {
        val source = sourceBitmap
        val bitmap = if (source != null) {
            withContext(Dispatchers.Default) { runCatching { processor.previewFrom(source, style) }.getOrNull() }
        } else {
            val path = originalPath ?: return
            withContext(Dispatchers.IO) { runCatching { processor.generatePreview(path, style) }.getOrNull() }
        } ?: return
        _uiState.update { it.copy(preview = bitmap.asImageBitmap()) }
    }

    // 不在此处 recycle 源图：uiState.source 仍以 ImageBitmap 包裹着同一个 Bitmap，
    // VM 销毁与界面退出之间可能存在一帧的重绘窗口，回收会导致 "Canvas: trying to use
    // a recycled bitmap" 崩溃。交给 GC 回收即可（API 26+ 位图已计入 Java 堆）。

    companion object {
        fun factory(packId: Long, slot: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { IconEditorViewModel(ServiceLocator.repository, packId, slot) }
        }
    }
}
