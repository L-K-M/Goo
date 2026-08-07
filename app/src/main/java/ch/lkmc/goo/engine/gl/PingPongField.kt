package ch.lkmc.goo.engine.gl

import android.opengl.GLES30
import ch.lkmc.goo.engine.core.TexelRect

/**
 * The displacement field on the GPU: two float textures with framebuffers,
 * swapped every stamp (the stamp shader reads the previous field while
 * writing the next — GL forbids sampling the texture being rendered).
 *
 * Format: RGBA16F when renderable (EXT_color_buffer_half_float —
 * effectively universal on GLES3 hardware), else RGBA32F via
 * EXT_color_buffer_float. xy = displacement, z = Fusion mask, w spare.
 * Devices with neither can't run the engine; the renderer surfaces that as
 * an error state rather than crashing (REVIEW.md tracks a packed-RGBA8
 * fallback should such hardware ever show up in practice).
 *
 * GL thread only.
 */
class PingPongField(val width: Int, val height: Int, halfFloatRenderable: Boolean) {

    private val textures = IntArray(2)
    private val framebuffers = IntArray(2)
    private var readIndex = 0

    val readTexture: Int get() = textures[readIndex]
    private val readFramebuffer: Int get() = framebuffers[readIndex]
    private val writeFramebuffer: Int get() = framebuffers[1 - readIndex]

    /**
     * Where the write buffer is out of date with respect to the read
     * buffer — the invariant [renderPassIn] maintains.
     *
     * A fullscreen pass writes every texel, so it leaves nothing stale. A
     * scissored pass writes only its rect, and after the swap the buffer
     * that becomes the next write target still holds the state from
     * *before* that pass, differing exactly inside the rect. Recording
     * that rect lets the next scissored pass repair it with a small blit
     * instead of a fullscreen copy — which is the whole of REVIEW.md G-3.
     */
    private var staleRect: TexelRect = TexelRect.EMPTY

    init {
        // RGBA since Fusion: xy displacement + z mask (w spare). The same
        // extensions that made RG renderable make RGBA renderable — RGBA16F
        // is in fact the format EXT_color_buffer_half_float names first.
        val internalFormat = if (halfFloatRenderable) GLES30.GL_RGBA16F else GLES30.GL_RGBA32F
        val type = if (halfFloatRenderable) GLES30.GL_HALF_FLOAT else GLES30.GL_FLOAT
        GLES30.glGenTextures(2, textures, 0)
        GLES30.glGenFramebuffers(2, framebuffers, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                GLES30.GL_RGBA, type, null,
            )
            // LINEAR + CLAMP_TO_EDGE mirror the CPU reference's bilinear
            // clamp sampling (DisplacementField.sample).
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, textures[i], 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "field framebuffer incomplete: 0x${status.toString(16)}"
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        clear()
    }

    /** Zero both buffers: the identity warp. */
    fun clear() {
        // The scissor box would otherwise survive from a scissored stamp
        // and leave most of the field uncleared.
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        for (fb in framebuffers) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // Both buffers hold zero, so neither is stale with respect to the
        // other, whichever way the next swap goes.
        staleRect = TexelRect.EMPTY
    }

    /**
     * Bind the write framebuffer, clip [draw] to [rect], and swap so the
     * result becomes the new read side. [draw] samples the read texture.
     *
     * This is the only pass primitive: a whole-surface pass is
     * `renderPassIn(TexelRect.full(width, height), …)`, which the same
     * invariant covers, so there is no separate unscissored path to keep
     * in step.
     *
     * Correctness rests on a property of `STAMP_FRAG` rather than on
     * hope. Outside the brush disc every branch reduces to an identity
     * copy of the fragment's own texel — which is exactly what the
     * fullscreen version spent its fill rate on. Skipping those fragments
     * would normally break ping-pong, because the destination texture
     * still holds the state from two passes ago; the blit below repairs
     * precisely that difference, and nothing more.
     *
     * A rect that is empty means the stamp cannot reach the field at all
     * (an off-canvas mirror twin, say) and the pass is skipped outright —
     * including the swap, so the caller's field is left exactly as it was.
     */
    fun renderPassIn(rect: TexelRect, draw: (readTexture: Int) -> Unit) {
        if (rect.isEmpty) return
        syncWriteBuffer()
        bindWrite()
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(rect.x0, rect.y0, rect.width, rect.height)
        draw(readTexture)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        swap()
        // The buffer that just became the write target is the one this
        // pass read from: it matches the new state everywhere except
        // where we drew.
        staleRect = rect
    }

    /**
     * Copy the stale region from the read buffer into the write buffer so
     * a scissored draw can leave the rest alone.
     *
     * `GL_NEAREST` is required, not merely sufficient: ES 3.0 rejects
     * `GL_LINEAR` blits of floating-point color buffers, and the copy is
     * 1:1 anyway. Scissor must be off — a blit is clipped by it.
     */
    private fun syncWriteBuffer() {
        val stale = staleRect
        if (stale.isEmpty) return
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFramebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, writeFramebuffer)
        GLES30.glBlitFramebuffer(
            stale.x0, stale.y0, stale.x1, stale.y1,
            stale.x0, stale.y0, stale.x1, stale.y1,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
        )
        staleRect = TexelRect.EMPTY
    }

    /**
     * The viewport stays the whole field even under a scissor: the quad's
     * NDC → UV mapping is what makes `v_uv` the fragment's own texel
     * center, and shrinking the viewport to the rect would rescale it.
     * The scissor discards fragments; it does not move them.
     */
    private fun bindWrite() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, writeFramebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    private fun swap() {
        readIndex = 1 - readIndex
    }

    fun delete() {
        GLES30.glDeleteFramebuffers(2, framebuffers, 0)
        GLES30.glDeleteTextures(2, textures, 0)
    }

    companion object {
        /** True when RGBA16F is color-renderable on this context. */
        fun hasHalfFloat(extensions: String): Boolean =
            "GL_EXT_color_buffer_half_float" in extensions

        /**
         * True when the RGBA32F fallback is fully usable: renderable
         * (EXT_color_buffer_float) AND filterable — unextended ES 3.0 does
         * not filter 32F textures, and our samplers rely on LINEAR; without
         * OES_texture_float_linear the texture would be incomplete and
         * silently sample zero. (Used only when half-float is absent, which
         * real GLES3 hardware essentially never is.)
         */
        fun hasFloat(extensions: String): Boolean =
            "GL_EXT_color_buffer_float" in extensions &&
                "GL_OES_texture_float_linear" in extensions

        /** Whether any renderable float field format exists. */
        fun supported(extensions: String): Boolean =
            hasHalfFloat(extensions) || hasFloat(extensions)
    }
}
