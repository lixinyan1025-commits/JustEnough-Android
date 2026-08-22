package com.justenough.planner.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
    private val alias = "just_enough_api_keys"

    fun putApiKey(value: String) = put("primary", value)
    fun getApiKey(): String? = get("primary")
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
    fun clearApiKey() = clear("primary")
    fun putFallbackApiKey(value: String) = put("fallback", value)
    fun getFallbackApiKey(): String? = get("fallback")
    fun hasFallbackApiKey(): Boolean = !getFallbackApiKey().isNullOrBlank()
    fun clearFallbackApiKey() = clear("fallback")
    fun clearAll() = prefs.edit().clear().apply()

    private fun put(slot: String, value: String) {
        if (value.isBlank()) { clear(slot); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${slot}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${slot}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun get(slot: String): String? = runCatching {
        val data = prefs.getString("${slot}_data", null) ?: return null
        val iv = prefs.getString("${slot}_iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun clear(slot: String) = prefs.edit().remove("${slot}_data").remove("${slot}_iv").apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
}
