package ovh.adan.opendmo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test "golden" del mapeo del [DmoBridge], derivado del comportamiento capturado con
 * radio real el 27-jun-2026 (gateway rv-dmo validado en producción):
 *
 *   RF->red:  control 0x41 (DATA|1 header) -> ft=2,dv=1 · 0x20 (burst A) -> ft=1,dv=0
 *             0x00 (voz B-F) -> ft=0,dv=1..5 · 0x42 (terminator) -> ft=2,dv=2 y fin de stream
 *   red->RF:  jitter buffer de PREBUF(4) tramas, control SIEMPRE 0x00, flush con terminator
 *             half-duplex (RF activo bloquea red) y una sola llamada de red simultánea.
 *
 * Si alguno de estos asserts rompe, el cambio contradice la captura real: revisar antes
 * de tocar DmoBridge.
 */
class DmoBridgeTest {

    private class Sent(val seq: Int, val src: Int, val dst: Int, val slot: Int,
                       val ft: Int, val dv: Int, val stream: String)

    private class FakeSink : DmrSink {
        override val connected = true
        val written = mutableListOf<Pair<Int, ByteArray>>()   // (control, burst)
        override fun sendDmr(slot: Int, control: Int, dmrData: ByteArray) {
            written.add(control to dmrData)
        }
    }

    private fun bridge(sent: MutableList<Sent>, dynamic: Boolean = true, tg: Int = 666) =
        DmoBridge(radioId = 2130035, talkgroup = tg, networkSlot = 2, dynamicTg = dynamic,
            sendDmrd = { seq, src, dst, slot, ft, dv, sid, _ ->
                sent.add(Sent(seq, src, dst, slot, ft, dv, DmrVoice.hex(sid)))
            })

    private val zeroBurst = ByteArray(33)   // LC indecodificable (dst=0) -> TG de reserva

    // ---------- RF -> red ----------

    @Test fun rfCallMapsControlBytesLikeCapturedGateway() {
        val sent = mutableListOf<Sent>()
        val b = bridge(sent)
        val controls = listOf(0x41, 0x41, 0x41, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x42)
        controls.forEach { b.onRfDmr(2, it, zeroBurst) }

        val expected = listOf(2 to 1, 2 to 1, 2 to 1, 1 to 0,
                              0 to 1, 0 to 2, 0 to 3, 0 to 4, 0 to 5, 2 to 2)
        assertEquals(expected, sent.map { it.ft to it.dv })
        assertEquals((0 until 10).toList(), sent.map { it.seq })        // seq incremental
        assertEquals(1, sent.map { it.stream }.distinct().size)         // mismo streamId
        assertTrue(sent.all { it.dst == 666 && it.src == 2130035 && it.slot == 2 })

        // el terminator cierra el stream: la siguiente cabecera abre stream nuevo
        b.onRfDmr(2, 0x41, zeroBurst)
        assertNotEquals(sent[0].stream, sent.last().stream)
        assertEquals(0, sent.last().seq)
    }

    @Test fun dynamicTgIsLatchedFromFullLcHeader() {
        val sent = mutableListOf<Sent>()
        val b = bridge(sent, dynamic = true, tg = 666)
        b.onRfDmr(2, 0x41, headerBurst(dst = 214, src = 2130035))
        b.onRfDmr(2, 0x20, zeroBurst)
        assertTrue(sent.all { it.dst == 214 })                          // TG marcado por la radio
    }

    @Test fun staticTgIgnoresLc() {
        val sent = mutableListOf<Sent>()
        val b = bridge(sent, dynamic = false, tg = 666)
        b.onRfDmr(2, 0x41, headerBurst(dst = 214, src = 2130035))
        assertEquals(666, sent.single().dst)
    }

    // ---------- red -> RF ----------

    @Test fun jitterBufferHoldsPrebufAndFlushesOnTerminator() {
        val b = bridge(mutableListOf())
        val sink = FakeSink()
        b.modem = sink
        b.onNetDmrd(2, 1, 1234567, 666, 42, zeroBurst)                  // header
        repeat(3) { b.onNetDmrd(if (it == 0) 1 else 0, it, 1234567, 666, 42, zeroBurst) }
        assertEquals(0, sink.written.size)                              // 4 tramas: aún prebuffer
        b.onNetDmrd(0, 4, 1234567, 666, 42, zeroBurst)
        assertEquals(1, sink.written.size)                              // 5ª: empieza a drenar
        b.onNetDmrd(2, 2, 1234567, 666, 42, zeroBurst)                  // terminator
        assertEquals(6, sink.written.size)                              // flush total
        assertTrue(sink.written.all { it.first == 0x00 })               // control siempre 0 hacia la radio
    }

    @Test fun halfDuplexBlocksNetWhileRfActive() {
        val b = bridge(mutableListOf())
        val sink = FakeSink()
        b.modem = sink
        b.onRfDmr(2, 0x41, zeroBurst)                                   // RF activo (1 s)
        repeat(8) { b.onNetDmrd(if (it == 0) 2 else 0, if (it == 0) 1 else it, 1, 666, 7, zeroBurst) }
        assertEquals(0, sink.written.size)
    }

    @Test fun secondSimultaneousNetStreamIsIgnored() {
        val b = bridge(mutableListOf())
        val sink = FakeSink()
        b.modem = sink
        repeat(6) { b.onNetDmrd(if (it == 0) 2 else 0, if (it == 0) 1 else it, 1, 666, 100, zeroBurst) }
        val drained = sink.written.size
        b.onNetDmrd(2, 1, 2, 666, 200, zeroBurst)                       // otra llamada a la vez
        assertEquals(drained, sink.written.size)                        // la 1ª gana, la 2ª se descarta
    }

    // ---------- DmrLc ----------

    @Test fun decodeHeaderLcRejectsGarbage() {
        assertNull(DmrLc.decodeHeaderLc(zeroBurst))
        assertNull(DmrLc.decodeHeaderLc(ByteArray(10)))
    }

    @Test fun decodeHeaderLcReadsDstAndSrc() {
        val lc = DmrLc.decodeHeaderLc(headerBurst(dst = 214, src = 2130035))!!
        assertEquals(214, lc.dst)
        assertEquals(2130035, lc.src)
        assertTrue(lc.isGroup)
    }

    // ---------- helper: construye un VOICE LC HEADER sintético ----------

    /** Coloca los 72 bits del LC (flco=0/group) en las posiciones on-air que lee DmrLc. */
    private fun headerBurst(dst: Int, src: Int): ByteArray {
        val lc = intArrayOf(0, 0, 0,
            (dst ushr 16) and 0xFF, (dst ushr 8) and 0xFF, dst and 0xFF,
            (src ushr 16) and 0xFF, (src ushr 8) and 0xFF, src and 0xFF)
        val info = IntArray(196)
        for (i in 0 until 9) for (bit in 0 until 8) {
            info[LC_BITS[i * 8 + bit]] = (lc[i] ushr (7 - bit)) and 1
        }
        val bits = IntArray(264)
        for (i in 0 until 98) bits[i] = info[i]
        for (i in 0 until 98) bits[166 + i] = info[98 + i]
        val out = ByteArray(33)
        for (i in 0 until 264) if (bits[i] == 1) {
            out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        }
        return out
    }

    companion object {
        // misma tabla que DmrLc (bptc.decode_full_lc de dmr_utils3)
        private val LC_BITS = intArrayOf(
            136, 121, 106, 91, 76, 61, 46, 31,
            152, 137, 122, 107, 92, 77, 62, 47, 32, 17, 2,
            123, 108, 93, 78, 63, 48, 33, 18, 3, 184, 169,
            94, 79, 64, 49, 34, 19, 4, 185, 170, 155, 140,
            65, 50, 35, 20, 5, 186, 171, 156, 141, 126, 111,
            36, 21, 6, 187, 172, 157, 142, 127, 112, 97, 82,
            7, 188, 173, 158, 143, 128, 113, 98, 83
        )
    }
}
