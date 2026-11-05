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
    private val myBannerFile = File(dir, "me_banner.jpg")
    private val partnerBannerFile = File(dir, "partner_banner.bin")

    // ---- banners (wide picture at the top of the profile) and animated profile effects
    private val _myBanner = MutableStateFlow(if (myBannerFile.exists()) BitmapFactory.decodeFile(myBannerFile.path) else null)
    val myBanner: StateFlow<Bitmap?> = _myBanner.asStateFlow()
    private val _partnerBanner = MutableStateFlow(loadPartnerBanner())
    val partnerBanner: StateFlow<Bitmap?> = _partnerBanner.asStateFlow()
    private val _myEffect = MutableStateFlow(ProfileEffect.of(prefs.getString("my_effect", null)))
    val myEffect: StateFlow<ProfileEffect> = _myEffect.asStateFlow()
    private val _partnerEffect = MutableStateFlow(ProfileEffect.of(prefs.getString("partner_effect", null)))
    val partnerEffect: StateFlow<ProfileEffect> = _partnerEffect.asStateFlow()

    private fun loadPartnerBanner(): Bitmap? = runCatching {
        if (!partnerBannerFile.exists()) null
        else vault.decrypt(partnerBannerFile.readBytes(), "wr-partner-banner")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()

    /** Banner is already cropped to [BANNER_W] x [BANNER_H] by the crop screen. */
    fun setMyBanner(bitmap: Bitmap?) {
        if (bitmap == null) { myBannerFile.delete(); _myBanner.value = null; return }
        val b = if (bitmap.width == BANNER_W && bitmap.height == BANNER_H) bitmap else Bitmap.createScaledBitmap(bitmap, BANNER_W, BANNER_H, true)
        myBannerFile.writeBytes(Images.jpeg(b, 85))
        _myBanner.value = b
    }

    fun myBannerForSync(): ByteArray? = _myBanner.value?.let { Images.jpeg(it, 72) }

    fun setMyEffect(e: ProfileEffect) { prefs.edit().putString("my_effect", e.name).apply(); _myEffect.value = e }

    fun setPartnerExtras(bannerJpeg: ByteArray?, clearBanner: Boolean, effect: String?) {
        if (bannerJpeg != null) {
            val bmp = BitmapFactory.decodeByteArray(bannerJpeg, 0, bannerJpeg.size)
            if (bmp != null && bmp.width <= 1024 && bmp.height <= 1024) {
                partnerBannerFile.writeBytes(vault.encrypt(bannerJpeg, "wr-partner-banner"))
                _partnerBanner.value = bmp
            }
        } else if (clearBanner) { partnerBannerFile.delete(); _partnerBanner.value = null }
        if (effect != null) {
            val e = ProfileEffect.of(effect)
            prefs.edit().putString("partner_effect", e.name).apply(); _partnerEffect.value = e
        }
    }

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
        val sq = if (bitmap.width == 256 && bitmap.height == 256) bitmap else Images.squareCrop(bitmap, 256)
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
        prefs.edit().remove("partner_name").remove("partner_bio").remove("partner_effect").apply()
        partnerAvatarFile.delete()
        partnerBannerFile.delete(); _partnerBanner.value = null; _partnerEffect.value = ProfileEffect.NONE
        _partnerName.value = null
        _partnerBio.value = ""
        _partnerAvatar.value = null
    }

    companion object {
        const val MAX_NAME = 20
        const val BANNER_W = 400
        const val BANNER_H = 160
        const val MAX_BIO = 120
        fun sanitizeBio(raw: String): String = raw.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").trim().take(MAX_BIO)
        fun sanitizeName(raw: String): String? {
            val t = raw.replace(Regex("[\\p{Cntrl}]"), "").trim().take(MAX_NAME)
            return t.ifEmpty { null }
        }
    }
}

/** Animated decoration drawn around a profile picture. */
enum class ProfileEffect(val label: String) {
    NONE("None"), GLOW("Glow ring"), RAINBOW("Rainbow ring"), SPARKLES("Sparkles"), ORBIT("Orbiting stars"),
    BUBBLES("Bubbles"), SNOW("Snow"), FIRE("Flames"), PETALS("Petals"), AURORA("Aurora");
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: NONE }
}
