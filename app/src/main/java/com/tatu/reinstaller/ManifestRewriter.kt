package com.tatu.reinstaller

/**
 * パッケージ名を変更するときに AndroidManifest.xml のどこを書き換えるかを決める規則。
 *
 * Android の依存を持たない純粋なロジックなので、JVM のテストで検証できる。
 */
object ManifestRewriter {

    const val MANIFEST_ENTRY = "AndroidManifest.xml"

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** android:name がクラス名を指す要素。dex 側は元の名前のままなので展開が必要。 */
    private val CLASS_ELEMENTS = setOf(
        "application", "activity", "activity-alias", "service",
        "receiver", "provider", "instrumentation"
    )

    /** android:name がパーミッション等の識別子を指す要素。こちらは置換する。 */
    private val PERMISSION_ELEMENTS = setOf(
        "permission", "permission-group", "permission-tree",
        "uses-permission", "uses-permission-sdk-23"
    )

    /** クラス名を指すその他の属性。 */
    private val CLASS_ATTRIBUTES = setOf("targetActivity", "backupAgent", "appComponentFactory")

    /** 値にパッケージ名が含まれうる識別子。重複するとインストールが失敗する。 */
    private val IDENTIFIER_ATTRIBUTES = setOf(
        "permission", "readPermission", "writePermission",
        "targetPackage", "taskAffinity", "sharedUserId", "process"
    )

    fun currentPackage(editor: AxmlEditor): String? =
        editor.attributes()
            .firstOrNull { it.elementName == "manifest" && it.namespace == null && it.name == "package" }
            ?.value

    /**
     * [editor] にパッケージ名変更を適用し、変更内容の一覧を返す。
     */
    fun rewritePackage(
        editor: AxmlEditor,
        originalPackage: String,
        newPackage: String
    ): List<String> {
        val changes = mutableListOf<String>()

        for (attribute in editor.attributes()) {
            val value = attribute.value ?: continue
            if (!attribute.isStringValue) continue

            val replacement = when {
                // package 属性そのもの。
                attribute.elementName == "manifest" &&
                    attribute.namespace == null &&
                    attribute.name == "package" -> newPackage

                attribute.namespace != ANDROID_NS -> null

                // android:name は要素によって意味が違う。クラス名か、識別子か。
                attribute.name == "name" -> when (attribute.elementName) {
                    in CLASS_ELEMENTS -> absolutize(value, originalPackage)
                    in PERMISSION_ELEMENTS -> rename(value, originalPackage, newPackage)
                    else -> null
                }

                // クラスを指すその他の属性。
                attribute.name in CLASS_ATTRIBUTES -> absolutize(value, originalPackage)

                // provider の authorities は必ずユニークにする必要がある。
                attribute.name == "authorities" ->
                    value.split(";").joinToString(";") { rename(it, originalPackage, newPackage) }

                // パーミッション名・共有ユーザーIDなど、パッケージ名を含む識別子。
                attribute.name in IDENTIFIER_ATTRIBUTES ->
                    rename(value, originalPackage, newPackage)

                else -> null
            }

            if (replacement != null && replacement != value) {
                editor.setValue(attribute, replacement)
                changes.add("${attribute.elementName}/${attribute.name}: $value → $replacement")
            }
        }
        return changes
    }

    /** ".Foo" や "Foo" を "元のパッケージ.Foo" に展開する。Android のクラス解決規則と同じ。 */
    fun absolutize(value: String, originalPackage: String): String = when {
        value.startsWith(".") -> originalPackage + value
        !value.contains(".") -> "$originalPackage.$value"
        else -> value
    }

    fun rename(value: String, originalPackage: String, newPackage: String): String = when {
        value == originalPackage -> newPackage
        value.startsWith("$originalPackage.") -> newPackage + value.removePrefix(originalPackage)
        else -> value
    }

    /** 元の署名ファイル。複製時には取り除く。 */
    fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC") ||
            upper == "META-INF/MANIFEST.MF"
    }
}
