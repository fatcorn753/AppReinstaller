package com.tatu.reinstaller

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal

/**
 * APK のパッケージ名を書き換えて複製する。
 *
 * パッケージ名を変えると、マニフェスト内の相対クラス名 (".MainActivity") が
 * 新パッケージ基準で解決されてしまい、dex 内の実際のクラスに届かなくなる。
 * そのため書き換えは package 属性だけでは足りない。詳しい規則は [ManifestRewriter]。
 *
 * 署名は元のものが使えなくなるので、端末の AndroidKeyStore に生成した鍵で付け直す。
 */
class ApkCloner(private val context: Context) {

    data class Result(
        val file: File,
        val packageName: String,
        val changes: List<String>
    )

    /**
     * @param newPackage 新しいパッケージ名。null なら名前を変えず再署名だけ行う。
     * @param progress 進捗をUIに出すためのコールバック。
     */
    fun clone(
        source: File,
        newPackage: String?,
        dest: File,
        progress: (String) -> Unit
    ): Result {
        val editor = AxmlEditor(readManifest(source))
        val originalPackage = ManifestRewriter.currentPackage(editor)
            ?: throw IllegalArgumentException("マニフェストから package 属性を読めませんでした")

        val changes = if (newPackage != null && newPackage != originalPackage) {
            progress("マニフェストを書き換えています…")
            ManifestRewriter.rewritePackage(editor, originalPackage, newPackage)
        } else {
            listOf("パッケージ名は変更していません（再署名のみ）")
        }

        val unsigned = File(dest.parentFile, "${dest.name}.unsigned")
        try {
            progress("APK を組み直しています…")
            ApkRepacker.repack(
                source = source,
                dest = unsigned,
                replacements = mapOf(ManifestRewriter.MANIFEST_ENTRY to editor.build()),
                drop = ManifestRewriter::isSignatureEntry
            )

            progress("署名しています…")
            sign(unsigned, dest, minSdkOf(source))
        } finally {
            unsigned.delete()
        }

        return Result(dest, newPackage ?: originalPackage, changes)
    }

    private fun readManifest(source: File): ByteArray =
        ZipFile(source).use { zip ->
            val entry = zip.getEntry(ManifestRewriter.MANIFEST_ENTRY)
                ?: throw IllegalArgumentException("AndroidManifest.xml が見つかりません")
            zip.getInputStream(entry).use { it.readBytes() }
        }

    // ------------------------------------------------------------------ 署名

    @Suppress("DEPRECATION") // SignerConfig.Builder の後継 API は apksig 側でまだ安定していない
    private fun sign(unsigned: File, dest: File, minSdk: Int) {
        val (privateKey, certificates) = signingKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(KEY_ALIAS, privateKey, certificates, false).build()

        dest.delete()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsigned)
            .setOutputApk(dest)
            .setMinSdkVersion(minSdk)
            // v1 は全エントリを展開してダイジェストを取るので遅い。
            // v2 が使える API 24 以上なら省略する。
            .setV1SigningEnabled(minSdk < MIN_SDK_FOR_V2)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    /**
     * 複製アプリの署名に使う鍵。固定鍵をアプリに同梱すると、それを知っている全員が
     * 同じ署名で APK を作れてしまうため、端末の AndroidKeyStore に端末ごとの鍵を
     * 初回だけ生成して使い回す。秘密鍵の値自体はアプリからも読み出せない。
     */
    private fun signingKey(): Pair<PrivateKey, List<X509Certificate>> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=AppReinstaller Clone"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now))
                .setCertificateNotAfter(Date(now + CERT_VALIDITY_MILLIS))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val certificates = keyStore.getCertificateChain(KEY_ALIAS).map { it as X509Certificate }
        return privateKey to certificates
    }

    /** 署名時に必要な minSdkVersion。取れなければ安全側の既定値。 */
    private fun minSdkOf(source: File): Int {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(source.absolutePath, 0)
        val applicationInfo = info?.applicationInfo ?: return DEFAULT_MIN_SDK
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationInfo.minSdkVersion.takeIf { it > 0 } ?: DEFAULT_MIN_SDK
        } else {
            DEFAULT_MIN_SDK
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clone"
        const val DEFAULT_MIN_SDK = 21
        const val MIN_SDK_FOR_V2 = 24
        const val CERT_VALIDITY_MILLIS = 30L * 365 * 24 * 60 * 60 * 1000 // 約30年
    }
}
