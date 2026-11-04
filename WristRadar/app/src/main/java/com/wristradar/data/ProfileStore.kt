package com.wristradar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wristradar.crypto.KeystoreVault
import com.wristradar.util.Images
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Display identity (username + avatar) for this watch and the partner's synced copy.
 * Usernames are cosmetic: they are never used for authentication.
 */
class ProfileStore(private val context: Context, private val vault: KeystoreVault) {
    private val prefs = context.getSharedPreferences("wr_profile", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "profile").apply { mkdirs() }
    private val myAvatarFile = File(dir, "me.jpg")
    private val partnerAvatarFile = File(dir, "partner.bin")

    private val _myName = MutableStateFlow(prefs.getString("my_name", null) ?: defaultName())
    val myName: StateFlow<String> = _myName.asStateFlow()

    private val _myAvatar = MutableStateFlow(loadMine())
    val myAvatar: StateFlow<Bitmap?> = _myAvatar.asStateFlow()

    private val _myBio = MutableStateFlow(prefs.getString("my_bio", "") ?: "")
    val myBio: StateFlow<String> = _myBio.asStateFlow()

    private val _partnerBio = MutableStateFlow(prefs.getString("partner_bio", "") ?: "")
    val partnerBio: StateFlow<String> = _partnerBio.asStateFlow()

    fun setMyBio(bio: String) {
        val clean = sanitizeBio(bio)
        prefs.edit().putString("my_bio", clean).apply(); _myBio.value = clean
    }

    fun setPartnerBio(bio: String) {
        val clean = sanitizeBio(bio)
        prefs.edit().putString("partner_bio", clean).apply(); _partnerBio.value = clean
    }

    private val _partnerName = MutableStateFlow(prefs.getString("partner_name", null))
    val partnerName: StateFlow<String?> = _partnerName.asStateFlow()

    private val _partnerAvatar = MutableStateFlow(loadPartner())
    val partnerAvatar: StateFlow<Bitmap?> = _partnerAvatar.asStateFlow()

    private fun defaultName(): String {
        val n = "Watch-" + (1000..9999).random()
        prefs.edit().putString("my_name", n).apply()
        return n
    }

    private fun loadMine(): Bitmap? =
        if (myAvatarFile.exists()) BitmapFactory.decodeFile(myAvatarFile.path) else null

    private fun loadPartner(): Bitmap? = runCatching {
        if (!partnerAvatarFile.exists()) null
        else vault.decrypt(partnerAvatarFile.readBytes(), "wr-partner-avatar")
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()

    fun setMyName(name: String) {
        val clean = sanitizeName(name) ?: return
        prefs.edit().putString("my_name", clean).apply()
        _myName.value = clean
    }

    fun setMyAvatar(bitmap: Bitmap?) {
        if (bitmap == null) {
            myAvatarFile.delete(); _myAvatar.value = null; return
        }
        val sq = Images.squareCrop(bitmap, 256)
        myAvatarFile.writeBytes(Images.jpeg(sq, 85))
        _myAvatar.value = sq
    }

    /** Small avatar for the public discovery profile (sent in clear before pairing). */
    fun myAvatarThumb(): ByteArray? = _myAvatar.value?.let { Images.jpeg(Images.squareCrop(it, 64), 70) }

    /** Avatar sent to the partner over the encrypted channel. */
    fun myAvatarForSync(): ByteArray? = _myAvatar.value?.let { Images.jpeg(Images.squareCrop(it, 160), 80) }

    fun setPartner(name: String?, avatarJpeg: ByteArray?) {
        name?.let { sanitizeName(it) }?.let {
            prefs.edit().putString("partner_name", it).apply(); _partnerName.value = it
        }
        if (avatarJpeg != null) {
            val bmp = BitmapFactory.decodeByteArray(avatarJpeg, 0, avatarJpeg.size)
            if (bmp != null) {
                partnerAvatarFile.writeBytes(vault.encrypt(avatarJpeg, "wr-partner-avatar"))
                _partnerAvatar.value = bmp
            }
        }
    }

    fun clearPartnerAvatar() {
        partnerAvatarFile.delete(); _partnerAvatar.value = null
    }

    fun clearPartner() {
        prefs.edit().remove("partner_name").remove("partner_bio").apply()
        partnerAvatarFile.delete()
        _partnerName.value = null
        _partnerBio.value = ""
        _partnerAvatar.value = null
    }

    companion object {
        const val MAX_NAME = 20
        const val MAX_BIO = 120
        fun sanitizeBio(raw: String): String = raw.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").trim().take(MAX_BIO)
        fun sanitizeName(raw: String): String? {
            val t = raw.replace(Regex("[\\p{Cntrl}]"), "").trim().take(MAX_NAME)
            return t.ifEmpty { null }
        }
    }
}
