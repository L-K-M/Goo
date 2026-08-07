package ch.lkmc.goo.engine.gl

import android.opengl.GLES30

/**
 * A saved copy of a [PingPongField]'s contents: one texture, one
 * framebuffer, no ping-pong (nothing renders into it — it is filled and
 * read back by blit).
 *
 * This is the storage half of REVIEW.md G-6. `engine/core`'s
 * `ReplayCheckpoints` decides *when* a copy is worth taking and which
 * prefix of the stroke log it stands for; this class only holds pixels.
 *
 * Format matches the field exactly, because `glBlitFramebuffer` between
 * mismatched color formats is undefined for floating-point buffers.
 *
 * GL thread only.
 */
class FieldSnapshot(val width: Int, val height: Int, halfFloatRenderable: Boolean) {

    private val texture = IntArray(1)
    private val framebuffer = IntArray(1)

    /** Framebuffer to blit from and to; 0 if construction failed. */
    val fbo: Int get() = framebuffer[0]

    init {
        val internalFormat = if (halfFloatRenderable) GLES30.GL_RGBA16F else GLES30.GL_RGBA32F
        val type = if (halfFloatRenderable) GLES30.GL_HALF_FLOAT else GLES30.GL_FLOAT
        GLES30.glGenTextures(1, texture, 0)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            GLES30.GL_RGBA, type, null,
        )
        // NEAREST is enough — a snapshot is only ever blitted 1:1, never
        // sampled by a shader — but the parameters must be set for the
        // texture to be framebuffer-complete on some drivers.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture[0], 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // Unlike the field itself, a snapshot is optional: it makes undo
        // faster and nothing else. If the driver will not give us one,
        // drop it and let replay run from identity rather than taking the
        // whole engine down for a cache.
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) delete()
    }

    /** False once [delete] has run, or if the framebuffer never completed. */
    val isUsable: Boolean get() = framebuffer[0] != 0

    fun delete() {
        if (framebuffer[0] != 0) {
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
            framebuffer[0] = 0
        }
        if (texture[0] != 0) {
            GLES30.glDeleteTextures(1, texture, 0)
            texture[0] = 0
        }
    }
}
