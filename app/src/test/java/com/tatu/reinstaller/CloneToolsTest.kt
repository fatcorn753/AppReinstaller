package com.tatu.reinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * AXML の書き換えと ZIP の組み直しを、実際にビルドした APK に対して検証する。
 *
 * 端末がなくても壊れていないことを確かめられるよう、Android 依存のない部分だけを扱う。
 * 出力した APK は build/clone-test/ に残るので、aapt2 でも中身を確認できる。
 */
class CloneToolsTest {

    private val projectDir = File(System.getProperty("user.dir"))
    private val sourceApk = File(projectDir, "build/outputs/apk/debug/app-debug.apk")
    private val outputDir = File(projectDir, "build/clone-test").apply { mkdirs() }

    private val original = "com.tatu.reinstaller"
    private val renamed = "com.tatu.reinstallerclone"

    @Test
    fun `名前解決の規則`() {
        assertEquals("com.example.app.MainActivity", ManifestRewriter.absolutize(".MainActivity", "com.example.app"))
        assertEquals("com.example.app.Main", ManifestRewriter.absolutize("Main", "com.example.app"))
        // 既に絶対名なら触らない。
        assertEquals("androidx.work.Worker", ManifestRewriter.absolutize("androidx.work.Worker", "com.example.app"))

        assertEquals("com.new.app", ManifestRewriter.rename("com.example.app", "com.example.app", "com.new.app"))
        assertEquals(
            "com.new.app.provider",
            ManifestRewriter.rename("com.example.app.provider", "com.example.app", "com.new.app")
        )
        // 他社のパーミッションは巻き込まない。
        assertEquals(
            "android.permission.INTERNET",
            ManifestRewriter.rename("android.permission.INTERNET", "com.example.app", "com.new.app")
        )
    }

    @Test
    fun `署名ファイルだけを取り除く`() {
        assertTrue(ManifestRewriter.isSignatureEntry("META-INF/CERT.RSA"))
        assertTrue(ManifestRewriter.isSignatureEntry("META-INF/CERT.SF"))
        assertTrue(ManifestRewriter.isSignatureEntry("META-INF/MANIFEST.MF"))
        assertTrue(!ManifestRewriter.isSignatureEntry("META-INF/services/foo"))
        assertTrue(!ManifestRewriter.isSignatureEntry("classes.dex"))
    }

    @Test
    fun `AXML を読み書きしても壊れない`() {
        assumeTrue("先に assembleDebug が必要", sourceApk.exists())
        val manifest = readManifest(sourceApk)

        // 何も変更せずに書き戻したものが、もう一度読めること。
        val rebuilt = AxmlEditor(manifest).build()
        val reparsed = AxmlEditor(rebuilt)
        assertEquals(original, ManifestRewriter.currentPackage(reparsed))

        // 属性が失われていないこと。
        assertEquals(AxmlEditor(manifest).attributes().size, reparsed.attributes().size)
    }

    @Test
    fun `パッケージ名を書き換える`() {
        assumeTrue("先に assembleDebug が必要", sourceApk.exists())
        val editor = AxmlEditor(readManifest(sourceApk))
        val changes = ManifestRewriter.rewritePackage(editor, original, renamed)
        assertTrue("変更が1件もない", changes.isNotEmpty())

        val result = AxmlEditor(editor.build())
        assertEquals(renamed, ManifestRewriter.currentPackage(result))

        // コンポーネント名は「元の」パッケージのままでなければならない。
        val activity = result.attributes()
            .firstOrNull { it.elementName == "activity" && it.name == "name" }
        assertNotNull("activity/name が見つからない", activity)
        assertEquals("$original.MainActivity", activity!!.value)
    }

    @Test
    fun `APK を組み直せる`() {
        assumeTrue("先に assembleDebug が必要", sourceApk.exists())
        val editor = AxmlEditor(readManifest(sourceApk))
        ManifestRewriter.rewritePackage(editor, original, renamed)

        val dest = File(outputDir, "repacked.apk")
        ApkRepacker.repack(
            source = sourceApk,
            dest = dest,
            replacements = mapOf(ManifestRewriter.MANIFEST_ENTRY to editor.build()),
            drop = ManifestRewriter::isSignatureEntry
        )

        assertTrue("出力がない", dest.exists())

        ZipFile(sourceApk).use { source ->
            ZipFile(dest).use { output ->
                val sourceNames = source.entries().toList().map { it.name }
                val outputNames = output.entries().toList().map { it.name }
                val expected = sourceNames.filterNot { ManifestRewriter.isSignatureEntry(it) }

                assertEquals("エントリの数と順序が一致しない", expected, outputNames)

                // 差し替えていないエントリは、展開後の中身が元と同じであること。
                for (name in outputNames) {
                    if (name == ManifestRewriter.MANIFEST_ENTRY) continue
                    val a = source.getInputStream(source.getEntry(name)).use { it.readBytes() }
                    val b = output.getInputStream(output.getEntry(name)).use { it.readBytes() }
                    assertTrue("中身が変わっている: $name", a.contentEquals(b))
                }

                // 無圧縮エントリが4バイト境界に載っていること (zipalign 相当)。
                for (entry in output.entries()) {
                    if (entry.method != java.util.zip.ZipEntry.STORED) continue
                    val alignment = if (entry.name.endsWith(".so")) 16384 else 4
                    val offset = dataOffset(dest, entry.name)
                    assertEquals("整列していない: ${entry.name}", 0, offset % alignment)
                }
            }
        }
    }

    private fun readManifest(apk: File): ByteArray =
        ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry(ManifestRewriter.MANIFEST_ENTRY)).use { it.readBytes() }
        }

    /** ローカルヘッダを読んで、実データが始まる絶対位置を求める。 */
    private fun dataOffset(apk: File, name: String): Int {
        val bytes = apk.readBytes()
        val target = name.toByteArray(Charsets.UTF_8)
        var position = 0
        while (position + 30 <= bytes.size) {
            if (readU32(bytes, position) != 0x04034b50) break
            val nameLength = readU16(bytes, position + 26)
            val extraLength = readU16(bytes, position + 28)
            val compressedSize = readU32(bytes, position + 18)
            val entryName = ByteArray(nameLength)
            System.arraycopy(bytes, position + 30, entryName, 0, nameLength)
            val dataStart = position + 30 + nameLength + extraLength
            if (entryName.contentEquals(target)) return dataStart
            position = dataStart + compressedSize
        }
        throw AssertionError("エントリが見つからない: $name")
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
