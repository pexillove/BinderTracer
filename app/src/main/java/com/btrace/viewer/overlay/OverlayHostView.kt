package com.btrace.viewer.overlay

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose 在 Service 里渲染必须三件套 owner(LifecycleOwner / ViewModelStoreOwner /
 * SavedStateRegistryOwner)。本类是一次性 host:把 ComposeView 装好 + ViewTree owners
 * 注好,直接当成 View 加进 WindowManager 即可。
 *
 * 用法:
 *   val host = OverlayHostView(context) { MyComposable() }
 *   windowManager.addView(host.view, params)
 *   ...
 *   windowManager.removeView(host.view)
 *   host.dispose()
 */
class OverlayHostView(
    context: Context,
    content: @Composable () -> Unit
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    val view: ComposeView

    init {
        // savedStateRegistryController.performRestore 必须在 ON_CREATE 之前
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayHostView)
            setViewTreeViewModelStoreOwner(this@OverlayHostView)
            setViewTreeSavedStateRegistryOwner(this@OverlayHostView)
            setContent { content() }
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun dispose() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
