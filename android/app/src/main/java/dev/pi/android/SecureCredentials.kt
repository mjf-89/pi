package dev.pi.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted OAuth data is stored in preferences; its key stays in Android Keystore. */
class SecureCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("pi-auth", Context.MODE_PRIVATE)
    private val alias = "pi-durable-oauth-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
    @Synchronized fun read(): String {
        val stored = preferences.getString("credential", null) ?: return ""
        val pieces = stored.split(":", limit = 2)
        require(pieces.size == 2) { "Invalid credential storage" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun write(value: String) {
        if (value.isEmpty()) {
            check(preferences.edit().remove("credential").commit()) { "Could not remove credential" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(preferences.edit().putString("credential", payload).commit()) { "Could not persist credential" }
    }
    @Synchronized fun deviceId(): String {
        preferences.getString("deviceId", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString("deviceId", id).commit()) { "Could not persist installation ID" }
        return id
    }
}
