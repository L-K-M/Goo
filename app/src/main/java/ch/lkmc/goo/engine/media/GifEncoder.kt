package ch.lkmc.goo.engine.media

import java.io.OutputStream

/**
 * A streaming GIF89a writer: header once, then one palettised frame at a
 * time, then the trailer. Pure JVM (an [OutputStream] and ARGB int
 * arrays), so the whole format lives under `testDebugUnitTest` — see
 * `GifEncoderTest`, which decodes what this writes.
 *
 * Deliberately per-frame palettes (a local colour table each) rather than
 * one global table: a GOOvie's colours travel as the goo moves, and a
 * frame-local median cut costs 768 bytes against a visibly better frame.
 *
 * Looping is the Netscape 2.0 application extension with a repeat count
 * of 0 — the de-facto "loop forever" every viewer honours. Written once,
 * right after the screen descriptor, because that is where decoders look
 * for it; without it a GIF plays through exactly once.
 *
 * Frames stream straight out: nothing is buffered across calls, so a
 * 200-frame movie costs one frame of memory, not two hundred.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val loop: Boolean,
) {

    private var headerWritten = false
    private var finished = false

    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "gif size out of range" }
    }

    /**
     * Append one frame, shown for [delayCentis] hundredths of a second.
     * [argb] is row-major, top row first, `width * height` long; alpha is
     * ignored (GIF has no alpha, only a transparent index, which an opaque
     * photo warp never needs).
     */
    fun addFrame(argb: IntArray, delayCentis: Int) {
        check(!finished) { "encoder already finished" }
        require(argb.size == width * height) { "frame size mismatch" }
        require(delayCentis in 0..0xFFFF) { "delay out of range" }
        if (!headerWritten) {
            writeHeader()
            headerWritten = true
        }

        val palette = GifPalette.from(argb)
        val indices = palette.index(argb)
        val bits = palette.bits()

        // Graphic control extension: the per-frame delay lives here.
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        // Disposal method 1 ("leave in place"): every frame is full-size
        // and opaque, so there is nothing to restore between them.
        out.write(0x04)
        writeShort(delayCentis)
        out.write(0x00) // no transparent index
        out.write(0x00) // block terminator

        // Image descriptor: full frame, local colour table.
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.write(0x80 or (bits - 1))
        out.write(palette.tableBytes())

        // GIF's minimum LZW code size must be ≥ 2 even for a 2-colour table.
        val minCodeSize = maxOf(2, bits)
        out.write(minCodeSize)
        LzwWriter(out, minCodeSize).run {
            encode(indices)
            finish()
        }
    }

    /** Write the trailer and flush. The stream itself stays the caller's. */
    fun finish() {
        check(!finished) { "encoder already finished" }
        // A GIF with no frames is not a GIF; still emit a well-formed,
        // empty-but-parseable file rather than a zero-byte one.
        if (!headerWritten) {
            writeHeader()
            headerWritten = true
        }
        out.write(0x3B)
        out.flush()
        finished = true
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor: no global colour table (frames carry
        // their own), 8-bit colour resolution, background index 0.
        writeShort(width)
        writeShort(height)
        out.write(0x70)
        out.write(0x00)
        out.write(0x00)
        if (loop) {
            out.write(0x21)
            out.write(0xFF)
            out.write(0x0B)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(0x03)
            out.write(0x01)
            writeShort(0) // repeat count 0 = forever
            out.write(0x00)
        }
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write(value ushr 8 and 0xFF)
    }
}

/**
 * GIF's variable-width LZW, written LSB-first into 255-byte sub-blocks.
 *
 * The code width grows the moment the next free code no longer fits, and
 * the table is cleared (not frozen) at 4096 — both exactly as decoders
 * assume, which is why they are stated here rather than tuned: an encoder
 * that is one code early or late produces a file that decodes to garbage
 * only on some viewers.
 */
private class LzwWriter(private val out: OutputStream, private val minCodeSize: Int) {

    private val clearCode = 1 shl minCodeSize
    private val eoiCode = clearCode + 1

    private var codeSize = minCodeSize + 1
    private var nextCode = clearCode + 2
    private var prefix = -1

    // Open-addressed (prefix, byte) → code table. Power-of-two size with
    // linear probing; a HashMap here would box two ints per pixel.
    private val keys = IntArray(TABLE_SIZE) { EMPTY }
    private val values = IntArray(TABLE_SIZE)

    private var bitBuffer = 0
    private var bitCount = 0
    private val block = ByteArray(255)
    private var blockLength = 0

    fun encode(indices: ByteArray) {
        emit(clearCode)
        for (byte in indices) {
            val symbol = byte.toInt() and 0xFF
            if (prefix < 0) {
                prefix = symbol
                continue
            }
            val key = (prefix shl 8) or symbol
            val slot = slotOf(key)
            if (keys[slot] == key) {
                prefix = values[slot]
                continue
            }
            emit(prefix)
            if (nextCode < MAX_CODES) {
                keys[slot] = key
                values[slot] = nextCode
                nextCode++
            } else {
                // Table full: clear at the CURRENT width (the decoder is
                // still reading at it), then start over.
                emit(clearCode)
                reset()
            }
            prefix = symbol
        }
    }

    fun finish() {
        if (prefix >= 0) emit(prefix)
        emit(eoiCode)
        if (bitCount > 0) {
            writeByte(bitBuffer and 0xFF)
            bitBuffer = 0
            bitCount = 0
        }
        flushBlock()
        out.write(0x00) // end of the frame's sub-block chain
    }

    private fun slotOf(key: Int): Int {
        // Knuth multiplicative hash, then probe forward. The table is
        // twice MAX_CODES, so a free slot always exists.
        var slot = (key * -1640531527) ushr (32 - TABLE_BITS)
        while (keys[slot] != EMPTY && keys[slot] != key) {
            slot = (slot + 1) and (TABLE_SIZE - 1)
        }
        return slot
    }

    private fun reset() {
        keys.fill(EMPTY)
        codeSize = minCodeSize + 1
        nextCode = clearCode + 2
    }

    private fun emit(code: Int) {
        bitBuffer = bitBuffer or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            writeByte(bitBuffer and 0xFF)
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
        // Widen AFTER the code goes out, and against the table as it stood
        // BEFORE this round's insert. The decoder adds its entries one code
        // later than the encoder does, so this exact placement is what puts
        // the two on the same width for the same code. Off by one here and
        // the file decodes to noise on strict viewers only.
        if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_CODE_SIZE) codeSize++
    }

    private fun writeByte(value: Int) {
        block[blockLength++] = value.toByte()
        if (blockLength == block.size) flushBlock()
    }

    private fun flushBlock() {
        if (blockLength == 0) return
        out.write(blockLength)
        out.write(block, 0, blockLength)
        blockLength = 0
    }

    private companion object {
        const val MAX_CODE_SIZE = 12
        const val MAX_CODES = 1 shl MAX_CODE_SIZE
        const val TABLE_BITS = 13
        const val TABLE_SIZE = 1 shl TABLE_BITS
        const val EMPTY = -1
    }
}
