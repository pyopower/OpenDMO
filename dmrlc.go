package main

// Decodificador de Full Link Control de una cabecera/terminador de voz DMR.
//
// Port FIEL de `dmr_utils3.bptc` (N0MJS) vía DmrLc.kt de OpenDMO. Solo el DECODE
// necesario para el modo DMO dinámico: sacar el TG (dst) y el origen (src) que marca
// la radio, sin transcodificar nada.
//
// Un burst DMR son 264 bits (33 bytes). En un VOICE LC HEADER, los 196 bits de
// información llevan el Full LC codificado en BPTC(196,96): info[0:98] en bits 0..97
// e info[98:196] en bits 166..263; en medio van slot-type y sync. Los índices de
// lcBits YA incorporan el deinterleave (verificado contra el encode de dmr_utils3).
// No hace falta FEC: la señal local por RF de mano apenas trae errores.

// Posiciones de los 72 bits del LC en los bits on-air (bptc.decode_full_lc).
var lcBits = [72]int{
	136, 121, 106, 91, 76, 61, 46, 31,
	152, 137, 122, 107, 92, 77, 62, 47, 32, 17, 2,
	123, 108, 93, 78, 63, 48, 33, 18, 3, 184, 169,
	94, 79, 64, 49, 34, 19, 4, 185, 170, 155, 140,
	65, 50, 35, 20, 5, 186, 171, 156, 141, 126, 111,
	36, 21, 6, 187, 172, 157, 142, 127, 112, 97, 82,
	7, 188, 173, 158, 143, 128, 113, 98, 83,
}

// Lc es el Full LC decodificado. FLCO 0 = group call (TG), 3 = unit-to-unit (privada).
type Lc struct {
	Flco, Dst, Src int
}

func (l Lc) IsGroup() bool { return l.Flco == 0 }

// decodeHeaderLc decodifica el Full LC de un burst de cabecera de voz (33 B).
// Devuelve nil si el burst es corto o el LC es claramente inválido (dst/src a cero).
func decodeHeaderLc(burst []byte) *Lc {
	if len(burst) < 33 {
		return nil
	}

	// 33 bytes -> 264 bits (big-endian, MSB primero, como bitarray de dmr_utils3).
	var bits [264]int
	for i := 0; i < 33; i++ {
		v := int(burst[i])
		for b := 0; b < 8; b++ {
			bits[i*8+b] = (v >> (7 - b)) & 1
		}
	}

	// info196 (interleaved, "on-air") = bits[0:98] + bits[166:264].
	var info [196]int
	for i := 0; i < 98; i++ {
		info[i] = bits[i]
		info[98+i] = bits[166+i]
	}

	// 72 bits del LC -> 9 bytes. lcBits se indexa DIRECTO sobre info on-air.
	var lc [9]int
	for i := 0; i < 9; i++ {
		v := 0
		for b := 0; b < 8; b++ {
			v = v<<1 | info[lcBits[i*8+b]]
		}
		lc[i] = v
	}

	flco := lc[0] & 0x3F
	dst := lc[3]<<16 | lc[4]<<8 | lc[5]
	src := lc[6]<<16 | lc[7]<<8 | lc[8]
	if dst == 0 || src == 0 {
		return nil
	}
	return &Lc{Flco: flco, Dst: dst, Src: src}
}
