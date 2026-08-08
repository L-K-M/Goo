package ch.lkmc.goo.engine.gl

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GLSL requires a name to be declared before it is used, and this
 * project cannot run a GL context to find out.
 *
 * That gap shipped a broken `STAMP_FRAG` once: a `float guard = … cur.w`
 * was inserted seven lines ABOVE `vec4 cur = texture(...)`, which is a
 * compile error, which means no stamp program, which means every brush
 * in the app silently does nothing. The unit suite was green and the
 * contract test was green, because both only ask whether substrings are
 * PRESENT — never whether they are in a legal order.
 *
 * So: a deliberately small check for exactly that failure. It is not a
 * GLSL parser and does not try to be one.
 *
 * It looks only inside `main()`, which is both where the mistake was and
 * where nearly every edit to these shaders lands. Scanning a whole
 * source would need real scope tracking — several of these shaders
 * declare a `float t` in three different functions, and treating them as
 * one namespace reports collisions that GLSL is perfectly happy with.
 * Narrow and correct beats broad and noisy for a guard whose whole job
 * is to be believed.
 */
class GlslDeclarationOrderTest {

    private val shaders = mapOf(
        "QUAD_VERT" to GlShaders.QUAD_VERT,
        "WARP_VERT" to GlShaders.WARP_VERT,
        "STAMP_FRAG" to GlShaders.STAMP_FRAG,
        "WARP_FRAG" to GlShaders.WARP_FRAG,
    )

    /** `vec4 cur = …` — a local with an initializer. */
    private val declaration =
        Regex("""^\s*(?:vec[234]|float|int|bool|mat[234])\s+(\w+)\s*=""", RegexOption.MULTILINE)

    @Test
    fun `main never uses a local before declaring it`() {
        for ((name, source) in shaders) {
            val bad = violations(source)
            assertTrue(
                bad.isEmpty(),
                "$name's main() uses ${bad.joinToString()} before declaring it — GLSL " +
                    "will refuse to compile this, the stamp program will not exist, " +
                    "every brush will silently do nothing, and no JVM test other " +
                    "than this one can tell",
            )
        }
    }

    @Test
    fun `the guard catches the mistake it was written for`() {
        // A check that cannot fail is worse than no check, because it
        // reads as coverage. So: reproduce the exact shape of the bug
        // that shipped — the field read moved BELOW the weight that
        // needs it — and confirm the detector says so.
        val read = "    vec4 cur = texture(u_field, v_uv);\n"
        assertTrue(GlShaders.STAMP_FRAG.contains(read), "the read must be findable to move")
        val broken = GlShaders.STAMP_FRAG
            .replace(read, "")
            .replace("    vec4 next;\n", "$read    vec4 next;\n")

        assertTrue(violations(broken).contains("cur"), "the guard must flag the moved read")
        assertTrue(violations(GlShaders.STAMP_FRAG).isEmpty(), "and clear the real one")
    }

    /** Top-level locals of `main()` that are used before they exist. */
    private fun violations(source: String): List<String> {
        val body = source.blankComments().mainBody()
        return declaration.findAll(body).mapNotNull { match ->
            val local = match.groupValues[1]
            val declaredAt = match.range.first
            // Only main's TOP-LEVEL locals. Those are visible for the
            // whole function, so any earlier use is illegal. A local
            // inside a branch is not: two sibling `else if` arms here
            // each declare their own `vec2 a`, and comparing one arm's
            // use against the other's declaration would report a
            // collision GLSL is perfectly happy with.
            if (body.depthAt(declaredAt) != 0) return@mapNotNull null
            // Lookbehind for the dot: `cur.w` is a swizzle of cur, not a
            // use of a local named w — and these shaders have both.
            val used = Regex("""(?<!\.)\b${Regex.escape(local)}\b""")
                .findAll(body)
                .any { it.range.first < declaredAt }
            local.takeIf { used }
        }.toList()
    }

    /** The balanced body of `void main() { … }`, or the whole source. */
    private fun String.mainBody(): String {
        val open = indexOf('{', startIndex = indexOf("void main(").takeIf { it >= 0 } ?: return this)
        if (open < 0) return this
        var depth = 0
        for (i in open until length) {
            when (this[i]) {
                '{' -> depth++
                // open + 1: the body must NOT include its own
                // opening brace, or every declaration inside it measures
                // at depth 1 and the top-level filter skips all of them —
                // which is a check that silently passes everything.
                '}' -> if (--depth == 0) return substring(open + 1, i)
            }
        }
        return substring(open)
    }

    /** Brace nesting at [index], relative to the string's start. */
    private fun String.depthAt(index: Int): Int =
        substring(0, index).count { it == '{' } - substring(0, index).count { it == '}' }

    /**
     * Comments are blanked, or a name mentioned in the prose above its
     * own declaration reads as a use. Blanked rather than deleted so
     * every offset still lines up with the source.
     */
    private fun String.blankComments(): String =
        Regex("""//[^\n]*""").replace(this) { " ".repeat(it.value.length) }
}
