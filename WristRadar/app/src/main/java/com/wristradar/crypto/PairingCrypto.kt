package com.wristradar.crypto

/**
 * First-contact pairing: ephemeral X25519 + hash commitment + Short Authentication String,
 * with an ML-KEM-768 encapsulation mixed into the long-term pairing secret (hybrid PQ).
 *
 *   A (initiator)                                       B (responder)
 *   PAIR_COMMIT  c = H("WR-COMMIT"|ePubA|idA|nA)         ->
 *                                                       <- PAIR_RESPONSE ePubB, idB, nB, kemPkB
 *   PAIR_REVEAL  ePubA, idA, nA, kemCt = Encaps(kemPkB) ->   (B checks c, decapsulates)
 *   th  = H(c|ePubB|idB|nB|ePubA|idA|nA)                 (committed values only)
 *   SAS = HKDF(X25519, th, "wr sas") -> 6 digits on both watches; users compare
 *   th2 = H("WR-PAIR-PQ"|th|kemPkB|kemCt)
 *   PAIR_CONFIRM Ed25519 sig over (role|th2)            <->
 *   psk = HKDF(X25519 | ML-KEM secret, th2, "wr psk v2")
 *
 * The SAS deliberately covers ONLY committed values. The KEM ciphertext is chosen after the
 * commitment, so letting it influence the SAS would let a man-in-the-middle grind for matching
 * codes. The KEM values are instead authenticated by both identity signatures over th2.
 */
class PairingCrypto(val isInitiator: Boolean, private val identity: Signer) {

    private val ephPriv = Crypto.x25519Private()
    val ephPub: ByteArray = Crypto.x25519Public(ephPriv)
    val nonce: ByteArray = Crypto.random(16)
    val myId: ByteArray get() = identity.publicKey

    val myCommit: ByteArray by lazy { Crypto.sha256("WR-COMMIT".toByteArray(), ephPub, myId, nonce) }

    /** Responder's one-time ML-KEM key pair. */
    private val kem: MlKem.KeyPair? = if (isInitiator) null else MlKem.generate()
    val kemPublicKey: ByteArray get() = kem!!.publicKey
    private var kemPk: ByteArray? = null
    var kemCiphertext: ByteArray? = null; private set
    private var kemSecret: ByteArray? = null

    private var peerCommit: ByteArray? = null
    var peerEph: ByteArray? = null; private set
    var peerId: ByteArray? = null; private set
    private var peerNonce: ByteArray? = null

    private var shared: ByteArray? = null
    var transcript: ByteArray? = null; private set
    private var transcript2: ByteArray? = null

    fun onPeerCommit(commit: ByteArray) {
        require(commit.size == 32)
        peerCommit = commit
    }

    /** Initiator: B's values. Encapsulates to B's ML-KEM key; send [kemCiphertext] in the reveal. */
    fun onResponse(eph: ByteArray, id: ByteArray, n: ByteArray, peerKemPk: ByteArray): Boolean {
        if (eph.size != 32 || id.size != 32 || n.size != 16 || peerKemPk.size != MlKem.PUBLIC_KEY_SIZE) return false
        if (Crypto.ctEquals(id, myId)) return false
        peerEph = eph; peerId = id; peerNonce = n
        if (!derive()) return false
        return try {
            val (ct, ss) = MlKem.encapsulate(peerKemPk)
            kemPk = peerKemPk; kemCiphertext = ct; kemSecret = ss
            finishTranscript(); true
        } catch (e: Exception) { false }
    }

    /** Responder: A's reveal must match the commitment received earlier. */
    fun onReveal(eph: ByteArray, id: ByteArray, n: ByteArray, ct: ByteArray): Boolean {
        val c = peerCommit ?: return false
        if (eph.size != 32 || id.size != 32 || n.size != 16 || ct.size != MlKem.CIPHERTEXT_SIZE) return false
        val expected = Crypto.sha256("WR-COMMIT".toByteArray(), eph, id, n)
        if (!Crypto.ctEquals(c, expected)) return false
        if (Crypto.ctEquals(id, myId)) return false
        peerEph = eph; peerId = id; peerNonce = n
        if (!derive()) return false
        return try {
            kemSecret = MlKem.decapsulate(kem!!, ct)
            kemPk = kem.publicKey; kemCiphertext = ct
            finishTranscript(); true
        } catch (e: Exception) { false }
    }

    private fun derive(): Boolean = try {
        shared = Crypto.x25519(ephPriv, peerEph!!)
        val (cA, eA, iA, nA) = if (isInitiator) listOf(myCommit, ephPub, myId, nonce)
        else listOf(peerCommit!!, peerEph!!, peerId!!, peerNonce!!)
        val (eB, iB, nB) = if (isInitiator) listOf(peerEph!!, peerId!!, peerNonce!!)
        else listOf(ephPub, myId, nonce)
        transcript = Crypto.sha256("WR-PAIR-V2".toByteArray(), cA, eB, iB, nB, eA, iA, nA)
        true
    } catch (e: Exception) {
        false
    }

    private fun finishTranscript() {
        transcript2 = Crypto.sha256("WR-PAIR-PQ".toByteArray(), transcript!!, kemPk!!, kemCiphertext!!)
    }

    val ready: Boolean get() = transcript2 != null

    /** 6-digit verification code, formatted "123 456". */
    fun sasCode(): String {
        val b = Crypto.hkdf(shared!!, transcript!!, "wr sas", 4)
        val v = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
                ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
        val code = (v % 1_000_000).toString().padStart(6, '0')
        return code.substring(0, 3) + " " + code.substring(3)
    }

    private fun confirmData(initiatorRole: Boolean): ByteArray =
        "WR-CONFIRM".toByteArray() + byteArrayOf(if (initiatorRole) 'A'.code.toByte() else 'B'.code.toByte()) + transcript2!!

    fun myConfirmation(): ByteArray = identity.sign(confirmData(isInitiator))

    fun verifyPeerConfirmation(sig: ByteArray): Boolean =
        Crypto.ed25519Verify(peerId!!, sig, confirmData(!isInitiator))

    fun derivePsk(): ByteArray = Crypto.hkdf(shared!! + kemSecret!!, transcript2!!, "wr psk v2", 32)

    fun wipe() { Crypto.wipe(ephPriv); Crypto.wipe(shared); Crypto.wipe(kemSecret) }
}
