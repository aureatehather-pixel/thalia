package com.thalia.app.secret

import java.io.IOException

private const val FORMAT: Byte = 1

/** The on-disk shape of a [SealedSecret]: a format byte, the nonce length, the nonce, then the ciphertext. */
internal fun encodeSealed(sealed: SealedSecret): ByteArray {
    require(sealed.iv.size in 1..255)
    return byteArrayOf(FORMAT, sealed.iv.size.toByte()) + sealed.iv + sealed.ciphertext
}

internal fun decodeSealed(bytes: ByteArray): SealedSecret {
    if (bytes.size < 2 || bytes[0] != FORMAT) throw IOException("unknown sealed file format")
    val ivLength = bytes[1].toInt() and 0xFF
    if (ivLength == 0 || bytes.size <= 2 + ivLength) throw IOException("truncated sealed file")
    return SealedSecret(bytes.copyOfRange(2, 2 + ivLength), bytes.copyOfRange(2 + ivLength, bytes.size))
}
