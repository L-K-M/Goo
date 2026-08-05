package ch.lkmc.goo.engine.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView

/**
 * The editor's canvas: a GLES3 GLSurfaceView wired to [GlWarpRenderer].
 *
 * Render-on-demand only — after every engine command the caller invokes
 * [requestRender]; continuous rendering would burn battery drawing an
 * unchanged warp. The EGL context is kept across pauses where the driver
 * allows; where it doesn't, the renderer rebuilds from the retained bitmap
 * and stroke snapshot (GPU state is a cache).
 *
 * Touch input is handled in Compose (pointerInput on the wrapping
 * AndroidView), not here — gestures need the same FitTransform the UI
 * layer already owns.
 */
@SuppressLint("ViewConstructor") // constructed from Compose, never inflated
class WarpSurfaceView(
    context: Context,
    val renderer: GlWarpRenderer,
) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Run [command] on the GL thread against the renderer, then redraw. */
    fun engine(command: GlWarpRenderer.() -> Unit) {
        queueEvent { renderer.command() }
        requestRender()
    }
}
