package main

import "crypto/rand"

// Subconjunto DMR/HBP necesario para el gateway DMO (sin vocoder).
// En modo gateway los bursts de 33 B pasan crudos entre RF y red; solo construimos
// la cabecera DMRD (20 B) del Homebrew Protocol. Port de DmrVoice.kt de OpenDMO
// (a su vez tomado de RadioVampiros, validado en aire).

const (
	hbpfVoice     = 0x0
	hbpfVoiceSync = 0x1
	hbpfDataSync  = 0x2
)

// dmrdHeader construye la cabecera HBP DMRD (20 B) sin el burst. slot 1/2, group call.
func dmrdHeader(seqNo, rfSrc, dst, peer, slot, frameType, dtypeVseq int, streamID []byte) []byte {
	bits := 0
	if slot == 2 {
		bits |= 0x80
	}
	bits |= (frameType & 0x3) << 4
	bits |= dtypeVseq & 0xF
	h := make([]byte, 20)
	copy(h, "DMRD")
	h[4] = byte(seqNo)
	h[5], h[6], h[7] = byte(rfSrc>>16), byte(rfSrc>>8), byte(rfSrc)
	h[8], h[9], h[10] = byte(dst>>16), byte(dst>>8), byte(dst)
	h[11], h[12], h[13], h[14] = byte(peer>>24), byte(peer>>16), byte(peer>>8), byte(peer)
	h[15] = byte(bits)
	copy(h[16:20], streamID)
	return h
}

func newStreamID() []byte {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return b
}
