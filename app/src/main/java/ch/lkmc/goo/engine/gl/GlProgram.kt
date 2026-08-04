package ch.lkmc.goo.engine.gl

import android.opengl.GLES30
import android.util.Log

/**
 * A compiled+linked GL program with uniform lookup. Thin by intent: the
 * renderer stays readable and the GL error surface stays in one place.
 * Construct and use on the GL thread only.
 */
class GlProgram(vertexSource: String, fragmentSource: String) {

    val id: Int

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES30.glCreateProgram().also { program ->
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            // Delete before the status check so a link failure can't leak
            // the shader objects; linked (or failed) programs keep their
            // own copies and GL defers actual deletion while attached.
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                "program link failed: $log"
            }
        }
    }

    fun use() = GLES30.glUseProgram(id)

    fun uniform(name: String): Int = GLES30.glGetUniformLocation(id, name)

    fun delete() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            Log.e(TAG, "shader compile failed:\n$source")
            "shader compile failed: $log"
        }
        return shader
    }

    private companion object {
        const val TAG = "GlProgram"
    }
}
