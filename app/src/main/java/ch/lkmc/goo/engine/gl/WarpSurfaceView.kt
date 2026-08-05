package ch.lkmc.goo.engine.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

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
        // Movie export renders into a MediaCodec input surface with the
        // SAME context (no sharing protocol) — that needs a config carrying
        // EGL_RECORDABLE_ANDROID. Ask for it; fall back without.
        val chooser = RecordableConfigChooser()
        setEGLConfigChooser(chooser)
        renderer.recordableConfig = { chooser.recordable }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Run [command] on the GL thread against the renderer, then redraw. */
    fun engine(command: GlWarpRenderer.() -> Unit) {
        queueEvent { renderer.command() }
        requestRender()
    }
}

/**
 * RGBA8888 / ES3 config chooser that prefers EGL_RECORDABLE_ANDROID.
 * The flag marks configs whose surfaces MediaCodec encoders accept;
 * [recordable] reports whether we got one (movie export gates on it).
 */
private class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {

    var recordable = false
        private set

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        choose(egl, display, withRecordable = true)?.let {
            recordable = true
            return it
        }
        recordable = false
        return choose(egl, display, withRecordable = false)
            ?: error("No RGBA8888 ES3 EGL config available")
    }

    private fun choose(egl: EGL10, display: EGLDisplay, withRecordable: Boolean): EGLConfig? {
        val spec = buildList {
            addAll(
                listOf(
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 0,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                ),
            )
            if (withRecordable) {
                add(EGL_RECORDABLE_ANDROID)
                add(1)
            }
            add(EGL10.EGL_NONE)
        }.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = egl.eglChooseConfig(display, spec, configs, 1, count)
        return if (ok && count[0] > 0) configs[0] else null
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
