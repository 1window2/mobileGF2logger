package dev.gf2log.app.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores a user-provided Discord webhook token encrypted by a non-exportable Android Keystore key. */
class DiscordWebhookSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun save(value: String) {
        val canonical = DiscordWebhookPolicy.requireValid(value).toASCIIString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(canonical.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to store the encrypted Discord webhook" }
    }

    fun read(): String? {
        val ciphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                requireKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val decoded = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            DiscordWebhookPolicy.requireValid(decoded).toASCIIString()
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) {
            "Unable to clear the encrypted Discord webhook"
        }
    }

    private fun getOrCreateKey(): SecretKey = keyStore().let { store ->
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun requireKey(): SecretKey = requireNotNull(
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey,
    ) { "Discord webhook encryption key is unavailable" }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private companion object {
        const val PREFERENCES = "discord_webhook_secret"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
        const val KEY_ALIAS = "gf2logger.discord.webhook.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
