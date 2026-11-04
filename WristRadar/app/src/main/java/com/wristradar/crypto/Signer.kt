package com.wristradar.crypto

/** Long-term identity key holder (implemented by IdentityManager; tests use an in-memory key). */
interface Signer {
    val publicKey: ByteArray
    fun sign(data: ByteArray): ByteArray
}
