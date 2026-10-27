package com.homecam.tv

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class CamConfig(
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val brand: Int,        // 0 = Hikvision, 1 = Dahua / CP Plus, 2 = Custom path
    val channels: Int,
    val customPath: String
) {
    fun url(ch: Int, main: Boolean): String {
        val u = Uri.encode(user)
        val p = Uri.encode(pass)
        val path = when (brand) {
            0 -> "/Streaming/Channels/${ch}0${if (main) 1 else 2}"
            1 -> "/cam/realmonitor?channel=$ch&subtype=${if (main) 0 else 1}"
            else -> customPath
                .replace("{ch}", ch.toString())
                .replace("{sub}", if (main) "0" else "1")
        }
        return "rtsp://$u:$p@$host:$port$path"
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
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("user", cfg.user)
            .putString("pass", cfg.pass)
            .putInt("brand", cfg.brand)
            .putInt("channels", cfg.channels)
            .putString("path", cfg.customPath)
            .putBoolean("set", true)
            .apply()
    }

    fun load(c: Context): CamConfig? {
        return try {
            val p = prefs(c)
            if (!p.getBoolean("set", false)) return null
            CamConfig(
                p.getString("host", "") ?: "",
                p.getInt("port", 554),
                p.getString("user", "") ?: "",
                p.getString("pass", "") ?: "",
                p.getInt("brand", 0),
                p.getInt("channels", 4),
                p.getString("path", "/Streaming/Channels/{ch}02") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }
}
