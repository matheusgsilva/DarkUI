package com.matheus.darkui.pack

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

class ApkSignerService {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "darkui-generated-pack-signer-v1"
    }

    fun sign(input: File, output: File) {
        val entry = signingEntry()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "DARKUI",
            entry.privateKey,
            listOf(entry.certificate as X509Certificate)
        ).build()

        if (output.exists()) output.delete()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setOtherSignersSignaturesPreserved(false)
            .build()
            .sign()
    }

    private fun signingEntry(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) createKey()
        return keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun createKey() {
        val now = Calendar.getInstance()
        val expiry = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=DarkUI Generated Pack"))
                .setCertificateSerialNumber(BigInteger.valueOf(20260920L))
                .setCertificateNotBefore(now.time)
                .setCertificateNotAfter(expiry.time)
                .build()
        )
        generator.generateKeyPair()
    }
}
