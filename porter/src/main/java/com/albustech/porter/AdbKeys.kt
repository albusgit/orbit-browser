package com.albustech.porter

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Porter's ADB identity: an RSA key and a self-signed certificate, made once and kept in the app's
 * private files. The watch remembers it after pairing, so later connections need no code.
 */
class AdbKeys(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        fun loadOrCreate(dir: File): AdbKeys {
            val keyFile = File(dir, "adb_key.pk8")
            val certFile = File(dir, "adb_cert.der")
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                    return AdbKeys(key, cert)
                }
            }
            val keys = create()
            keyFile.writeBytes(keys.privateKey.encoded)
            certFile.writeBytes(keys.certificate.encoded)
            return keys
        }

        fun create(): AdbKeys {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Porter")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 30L * 365 * 86_400_000L),
                name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            return AdbKeys(pair.private, JcaX509CertificateConverter().getCertificate(holder))
        }
    }
}
