package com.homecam.tv

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** One camera: which DVR (1 or 2) and which channel on it. */
data class Cam(val dvr: Int, val ch: Int)

data class CamConfig(
    val host1: String,
    val ch1: Int,
    val host2: String,      // blank = no second DVR
    val ch2: Int,
    val port: Int,
    val user: String,
    val pass: String,
    val brand: Int,         // 0 = Hikvision, 1 = Dahua / CP Plus, 2 = Custom path
    val customPath: String,
    val sharp: Boolean = true   // true = small tiles also use the main (full-quality) stream
) {
    fun cameras(): List<Cam> {
        val list = ArrayList<Cam>()
        if (host1.isNotBlank()) for (i in 1..ch1) list.add(Cam(1, i))
        if (host2.isNotBlank()) for (i in 1..ch2) list.add(Cam(2, i))
        return list
    }

    fun hostOf(dvr: Int) = if (dvr == 1) host1 else host2

    fun url(cam: Cam, main: Boolean): String {
        val u = Uri.encode(user)
        val p = Uri.encode(pass)
        val ch = cam.ch
        val path = when (brand) {
            0 -> "/Streaming/Channels/${ch}0${if (main) 1 else 2}"
            1 -> "/cam/realmonitor?channel=$ch&subtype=${if (main) 0 else 1}"
            else -> customPath
                .replace("{ch}", ch.toString())
                .replace("{sub}", if (main) "0" else "1")
        }
        return "rtsp://$u:$p@${hostOf(cam.dvr)}:$port$path"
    }

    companion object {
        val BRANDS = listOf("Hikvision", "Dahua / CP Plus", "Custom path")
    }
}

/** Credentials are stored encrypted (AES-256, key held in the Android Keystore). */
object SecureStore {
    private fun prefs(c: Context) = EncryptedSharedPreferences.create(
        c,
        "cctv_secure",
        MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(c: Context, cfg: CamConfig) {
        prefs(c).edit()
            .putString("host", cfg.host1)
            .putInt("channels", cfg.ch1)
            .putString("host2", cfg.host2)
            .putInt("channels2", cfg.ch2)
            .putInt("port", cfg.port)
            .putString("user", cfg.user)
            .putString("pass", cfg.pass)
            .putInt("brand", cfg.brand)
            .putString("path", cfg.customPath)
            .putBoolean("sharp", cfg.sharp)
            .putBoolean("set", true)
            .apply()
    }

    fun load(c: Context): CamConfig? {
        return try {
            val p = prefs(c)
            if (!p.getBoolean("set", false)) return null
            CamConfig(
                p.getString("host", "") ?: "",
                p.getInt("channels", 4),
                p.getString("host2", "") ?: "",
                p.getInt("channels2", 0),
                p.getInt("port", 554),
                p.getString("user", "") ?: "",
                p.getString("pass", "") ?: "",
                p.getInt("brand", 1),
                p.getString("path", "/Streaming/Channels/{ch}02") ?: "",
                p.getBoolean("sharp", true)
            )
        } catch (e: Exception) {
            null
        }
    }
}
