package com.wristradar.crypto

/**
 * Emoji safety code: both watches turn the two identity keys into the same row of emojis.
 * Compare them face to face: if they match, nobody sits in the middle of your connection.
 *
 * The keys are sorted first, so both sides compute the same code. 8 emojis x 6 bits = 48 bits
 * of the SHA-256 hash, plenty for a face-to-face comparison.
 */
object SafetyCode {
    /** 64 distinct, easy-to-tell-apart emojis (one per 6-bit value). */
    val EMOJI = listOf(
        "🐶", "🐱", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁",
        "🐸", "🐵", "🐔", "🐧", "🦉", "🦄", "🐝", "🦋",
        "🐢", "🐍", "🐙", "🦀", "🐬", "🐳", "🦈", "🐊",
        "🍎", "🍌", "🍇", "🍓", "🍒", "🍍", "🥕", "🌽",
        "🍕", "🍔", "🍩", "🍪", "🎂", "🍿", "🧀", "🥨",
        "⚽", "🏀", "🎾", "🎲", "🎸", "🎧", "🎮", "🎯",
        "🚀", "🚗", "🚲", "⛵", "✈️", "🚁", "⚓", "🔑",
        "🌙", "⭐", "🔥", "💧", "🌈", "❄️", "⚡", "🌵",
    )
    const val LENGTH = 8

    fun compute(myKey: ByteArray, theirKey: ByteArray): List<String> {
        val (a, b) = if (Crypto.compare(myKey, theirKey) <= 0) myKey to theirKey else theirKey to myKey
        val h = Crypto.sha256("WR-SAFETY-V1".toByteArray(), a, b)
        var bits = 0L
        for (i in 0 until 6) bits = (bits shl 8) or (h[i].toLong() and 0xFF)
        return List(LENGTH) { i -> EMOJI[((bits ushr (42 - 6 * i)) and 0x3F).toInt()] }
    }

    /** Short fingerprint of the partner key, to remember "verified" only for this exact friend. */
    fun fingerprint(theirKey: ByteArray): String = Crypto.sha256("WR-SAFETY-FP".toByteArray(), theirKey).take(8).joinToString("") { "%02x".format(it) }
}
