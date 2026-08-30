package com.veilkeepers.app.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.veilkeepers.app.crypto.AndroidBiometricKeyStore
import com.veilkeepers.app.crypto.BiometricVaultCore
import java.security.InvalidKeyException

/**
 * Biometric enrollment/unlock orchestration (spec.md §25, spec-1.md §B.8).
 *
 * HARD rule: this class NEVER makes a network call. Biometric authentication
 * only releases locally wrapped key material — it must not authenticate
 * against the backend. Enrollment is OPT-IN from the vault settings while the
 * VK is in memory; disabling / signing out wipes the blob and the Keystore
 * alias.
 */
class BiometricUnlockController(
    context: Context,
    private val core: BiometricVaultCore,
) {

    /** Display-ready biometric strings kept central and constant. */
    object Text {
        const val ENROLL_TITLE = "Enable biometric unlock"
        const val ENROLL_SUBTITLE = "Confirm your biometric to protect the vault key."
        const val UNLOCK_TITLE = "Unlock vault"
        const val UNLOCK_SUBTITLE = "Confirm your biometric to lift the veil."
        const val NEGATIVE = "Cancel"
        const val LOCKOUT_MESSAGE =
            "Biometric is locked. Please wait a moment, then try again."
        const val PERMANENT_LOCKOUT_MESSAGE =
            "Biometric is locked on this device. Unlock with your password."
        const val INVALIDATED_MESSAGE =
            "Biometric unlock is no longer available on this device. " +
                "Unlock with your password, then enable it again in Settings."
    }

    private val biometricManager = BiometricManager.from(context)

    /** Hardware/enrollment availability for BIOMETRIC_STRONG (no device credential). */
    fun hardwareAvailable(): Boolean =
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** True when the toggle is on AND a wrapped blob exists. */
    fun hasEnrollment(): Boolean = core.hasEnrollment()

    /**
     * OPT-IN enrollment: the prompt guards a WRAP cipher; ONLY on success is
     * the VK wrapped (`doFinal` inside the callback) and the blob persisted.
     * [vk] is the in-memory Vault Key — the user is already authenticated.
     */
    fun enroll(activity: FragmentActivity, vk: ByteArray, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val cipher = try {
            keyStore.createWrapCipher()
        } catch (e: InvalidKeyException) {
            onError(Text.INVALIDATED_MESSAGE)
            return
        } catch (e: Exception) {
            onError("Biometric setup failed on this device.")
            return
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val promptCipher = result.cryptoObject?.cipher ?: cipher
                try {
                    // The ONLY doFinal of the enrollment flow — inside the
                    // success callback, while the cipher is authenticated.
                    val nonce = promptCipher.iv
                    val ciphertext = promptCipher.doFinal(vk)
                    core.storeWrapped(nonce, ciphertext)
                    onSuccess()
                } catch (e: Exception) {
                    // A cipher that fails after success is unusable forever:
                    // wipe and fall back to password-only unlock.
                    core.wipe()
                    keyStore.deleteKey()
                    onError(Text.INVALIDATED_MESSAGE)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_LOCKOUT -> onError(Text.LOCKOUT_MESSAGE)
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> onError(Text.PERMANENT_LOCKOUT_MESSAGE)
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    -> { /* user cancel — silent by design */ }
                    else -> onError(errString.toString())
                }
            }
        }
        prompt(activity, callback).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(Text.ENROLL_TITLE)
                .setSubtitle(Text.ENROLL_SUBTITLE)
                .setNegativeButtonText(Text.NEGATIVE)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * Unlock: the prompt guards an UNWRAP cipher built from the stored blob's
     * nonce; ONLY on success is the VK unwrapped (`doFinal` in the callback)
     * and handed to [onSuccess].
     */
    fun unlock(activity: FragmentActivity, onSuccess: (ByteArray) -> Unit, onError: (String) -> Unit) {
        when (val prep = core.prepareUnlock()) {
            is BiometricVaultCore.UnlockPrep.Failed -> {
                onError(
                    when (prep.result) {
                        is BiometricVaultCore.UnlockResult.Invalidated -> Text.INVALIDATED_MESSAGE
                        is BiometricVaultCore.UnlockResult.Unavailable ->
                            (prep.result as BiometricVaultCore.UnlockResult.Unavailable).message
                        else -> "Biometric unlock failed. Use your password."
                    }
                )
                return
            }
            is BiometricVaultCore.UnlockPrep.Ready -> {
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val promptCipher = result.cryptoObject?.cipher ?: prep.cipher
                        when (val unlocked = core.finishUnlock(promptCipher)) {
                            is BiometricVaultCore.UnlockResult.Unlocked -> onSuccess(unlocked.vaultKey)
                            is BiometricVaultCore.UnlockResult.Invalidated -> {
                                keyStore.deleteKey()
                                onError(Text.INVALIDATED_MESSAGE)
                            }
                            is BiometricVaultCore.UnlockResult.Unavailable -> onError(unlocked.message)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        when (errorCode) {
                            BiometricPrompt.ERROR_LOCKOUT -> onError(Text.LOCKOUT_MESSAGE)
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> onError(Text.PERMANENT_LOCKOUT_MESSAGE)
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            -> { /* user cancel — silent by design */ }
                            else -> onError(errString.toString())
                        }
                    }
                }
                prompt(activity, callback).authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(Text.UNLOCK_TITLE)
                        .setSubtitle(Text.UNLOCK_SUBTITLE)
                        .setNegativeButtonText(Text.NEGATIVE)
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .build(),
                    BiometricPrompt.CryptoObject(prep.cipher),
                )
            }
        }
    }

    /**
     * Disables biometric unlock: deletes the blob + flags AND the Keystore
     * alias. Called from the settings toggle and on sign out.
     */
    fun disable() {
        core.wipe()
        keyStore.deleteKey()
    }

    private fun prompt(
        activity: FragmentActivity,
        callback: BiometricPrompt.AuthenticationCallback,
    ): BiometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        callback,
    )

    companion object {
        /** Production Keystore engine; injectable in tests via [core]. */
        val keyStore: AndroidBiometricKeyStore = AndroidBiometricKeyStore()
    }
}
