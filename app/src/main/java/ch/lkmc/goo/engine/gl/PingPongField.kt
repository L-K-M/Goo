package ch.lkmc.goo.engine.gl

import android.opengl.GLES30

/**
 * The displacement field on the GPU: two float textures with framebuffers,
 * swapped every stamp (the stamp shader reads the previous field while
 * writing the next — GL forbids sampling the texture being rendered).
 *
 * Format: RG16F when renderable (EXT_color_buffer_half_float — effectively
 * universal on GLES3 hardware), else RG32F via EXT_color_buffer_float.
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
    private val writeFramebuffer: Int get() = framebuffers[1 - readIndex]

    init {
        val internalFormat = if (halfFloatRenderable) GLES30.GL_RG16F else GLES30.GL_RG32F
        val type = if (halfFloatRenderable) GLES30.GL_HALF_FLOAT else GLES30.GL_FLOAT
        GLES30.glGenTextures(2, textures, 0)
        GLES30.glGenFramebuffers(2, framebuffers, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                GLES30.GL_RG, type, null,
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
        for (fb in framebuffers) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Bind the write framebuffer + viewport, run [draw] (which samples
     * [readTexture]), then swap so the result becomes the new read side.
     */
    inline fun renderPass(draw: (readTexture: Int) -> Unit) {
        bindWrite()
        draw(readTexture)
        swap()
    }

    fun bindWrite() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, writeFramebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun swap() {
        readIndex = 1 - readIndex
    }

    fun delete() {
        GLES30.glDeleteFramebuffers(2, framebuffers, 0)
        GLES30.glDeleteTextures(2, textures, 0)
    }

    companion object {
        /** True when RG16F is color-renderable on this context. */
        fun hasHalfFloat(extensions: String): Boolean =
            "GL_EXT_color_buffer_half_float" in extensions

        /**
         * True when the RG32F fallback is fully usable: renderable
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
