package com.fatcorn753.reinstaller

import java.io.ByteArrayOutputStream

/**
 * バイナリ形式の AndroidManifest.xml (AXML) を読み、属性の文字列値を差し替えて書き戻す。
 *
 * 方針は「文字列プールを作り直し、属性が指す文字列インデックスだけを差し替える」。
 * 要素チャンクの構造には一切手を入れないので、属性の追加・削除はできないが、
 * パッケージ名の変更に必要な書き換えはすべて値の差し替えで済む。
 *
 * 追加する文字列はプール末尾に足すため、既存インデックスは動かない。
 */
class AxmlEditor(private val original: ByteArray) {

    /** 読み取った属性1つ分。offset は元データ内の絶対位置。 */
    data class Attribute(
        val elementName: String,
        val namespace: String?,
        val name: String,
        val value: String?,
        val dataType: Int,
        val offset: Int
    ) {
        val isStringValue: Boolean get() = dataType == TYPE_STRING
    }

    private val strings = mutableListOf<String>()
    private var stringCount = 0
    private var styleCount = 0
    private var poolFlags = 0
    private var poolSize = 0
    private val poolStart = HEADER_SIZE

    /** 追加した文字列のインデックス（重複は使い回す）。 */
    private val addedIndex = mutableMapOf<String, Int>()

    /** offset -> 新しい文字列インデックス。 */
    private val valuePatches = mutableMapOf<Int, Int>()

    private val attributes = mutableListOf<Attribute>()

    init {
        parse()
    }

    /** 読み取ったすべての属性。 */
    fun attributes(): List<Attribute> = attributes

    /** 指定した属性の値を差し替える。実際の書き込みは [build] で行う。 */
    fun setValue(attribute: Attribute, newValue: String) {
        require(attribute.isStringValue) { "文字列以外の属性は変更できません: ${attribute.name}" }
        valuePatches[attribute.offset] = internString(newValue)
    }

    /** 差し替えを反映した AXML を組み立てる。 */
    fun build(): ByteArray {
        val patched = original.copyOf()
        for ((offset, index) in valuePatches) {
            // rawValue と typedValue.data の両方が同じ文字列インデックスを指す。
            writeU32(patched, offset + ATTR_RAW_VALUE, index)
            writeU32(patched, offset + ATTR_DATA, index)
            patched[offset + ATTR_DATA_TYPE] = TYPE_STRING.toByte()
        }

        val newPool = buildStringPool()
        val tailStart = poolStart + poolSize
        val out = ByteArray(HEADER_SIZE + newPool.size + (original.size - tailStart))
        System.arraycopy(patched, 0, out, 0, HEADER_SIZE)
        System.arraycopy(newPool, 0, out, HEADER_SIZE, newPool.size)
        System.arraycopy(patched, tailStart, out, HEADER_SIZE + newPool.size, original.size - tailStart)
        writeU32(out, 4, out.size)
        return out
    }

    // ------------------------------------------------------------------ 解析

    private fun parse() {
        require(readU16(original, 0) == RES_XML_TYPE) { "AndroidManifest.xml が AXML 形式ではありません" }

        var offset = HEADER_SIZE
        var poolParsed = false
        while (offset + 8 <= original.size) {
            val type = readU16(original, offset)
            val size = readU32(original, offset + 4)
            require(size >= 8) { "壊れたチャンク (offset=$offset)" }

            when (type) {
                RES_STRING_POOL_TYPE -> {
                    require(offset == poolStart) { "文字列プールの位置が想定外です" }
                    poolSize = size
                    parseStringPool(offset)
                    poolParsed = true
                }

                RES_XML_START_ELEMENT_TYPE -> parseStartElement(offset)
            }
            offset += size
        }
        require(poolParsed) { "文字列プールが見つかりません" }
    }

    private fun parseStringPool(start: Int) {
        stringCount = readU32(original, start + 8)
        styleCount = readU32(original, start + 12)
        poolFlags = readU32(original, start + 16)
        val stringsStart = readU32(original, start + 20)

        // スタイル付き文字列はマニフェストには現れない。出てきたら安全側に倒して中断する。
        require(styleCount == 0) { "スタイル付き文字列プールには対応していません" }

        val utf8 = (poolFlags and UTF8_FLAG) != 0
        for (i in 0 until stringCount) {
            val offset = start + stringsStart + readU32(original, start + 28 + i * 4)
            strings.add(if (utf8) readUtf8String(offset) else readUtf16String(offset))
        }
    }

    private fun parseStartElement(start: Int) {
        val headerSize = readU16(original, start + 2)
        val ext = start + headerSize
        val elementName = stringAt(readU32(original, ext + 4)) ?: return
        val attributeStart = readU16(original, ext + 8)
        val attributeSize = readU16(original, ext + 10)
        val attributeCount = readU16(original, ext + 12)

        for (i in 0 until attributeCount) {
            val offset = ext + attributeStart + i * attributeSize
            if (offset + ATTR_MIN_SIZE > original.size) return
            val namespace = stringAt(readU32(original, offset + ATTR_NAMESPACE))
            val name = stringAt(readU32(original, offset + ATTR_NAME)) ?: continue
            val dataType = original[offset + ATTR_DATA_TYPE].toInt() and 0xFF
            val value = if (dataType == TYPE_STRING) stringAt(readU32(original, offset + ATTR_DATA)) else null
            attributes.add(Attribute(elementName, namespace, name, value, dataType, offset))
        }
    }

    private fun stringAt(index: Int): String? =
        if (index in strings.indices) strings[index] else null

    private fun readUtf16String(offset: Int): String {
        var position = offset
        var length = readU16(original, position)
        position += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or readU16(original, position)
            position += 2
        }
        return String(original, position, length * 2, Charsets.UTF_16LE)
    }

    private fun readUtf8String(offset: Int): String {
        var position = offset
        // 1つ目は UTF-16 換算の長さ。使わないので読み飛ばす。
        if ((original[position].toInt() and 0x80) != 0) position += 2 else position += 1
        var length = original[position].toInt() and 0xFF
        position += 1
        if (length and 0x80 != 0) {
            length = ((length and 0x7F) shl 8) or (original[position].toInt() and 0xFF)
            position += 1
        }
        return String(original, position, length, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ 組み立て

    /** 文字列をプールに足して（既にあれば使い回して）インデックスを返す。 */
    private fun internString(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        addedIndex[value]?.let { return it }
        strings.add(value)
        val index = strings.size - 1
        addedIndex[value] = index
        return index
    }

    private fun buildStringPool(): ByteArray {
        val utf8 = (poolFlags and UTF8_FLAG) != 0
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)

        for ((i, value) in strings.withIndex()) {
            offsets[i] = data.size()
            if (utf8) writeUtf8String(data, value) else writeUtf16String(data, value)
        }

        val stringsStart = POOL_HEADER_SIZE + strings.size * 4
        var totalSize = stringsStart + data.size()
        val padding = (4 - totalSize % 4) % 4
        totalSize += padding

        val out = ByteArray(totalSize)
        writeU16(out, 0, RES_STRING_POOL_TYPE)
        writeU16(out, 2, POOL_HEADER_SIZE)
        writeU32(out, 4, totalSize)
        writeU32(out, 8, strings.size)
        writeU32(out, 12, 0)
        // 末尾に文字列を足すのでソート済みではなくなる。フラグを落としておく。
        writeU32(out, 16, poolFlags and SORTED_FLAG.inv())
        writeU32(out, 20, stringsStart)
        writeU32(out, 24, 0)
        for (i in strings.indices) writeU32(out, POOL_HEADER_SIZE + i * 4, offsets[i])
        System.arraycopy(data.toByteArray(), 0, out, stringsStart, data.size())
        return out
    }

    private fun writeUtf16String(out: ByteArrayOutputStream, value: String) {
        require(value.length <= 0x7FFF) { "文字列が長すぎます: $value" }
        out.write(value.length and 0xFF)
        out.write((value.length shr 8) and 0xFF)
        out.write(value.toByteArray(Charsets.UTF_16LE))
        out.write(0)
        out.write(0)
    }

    private fun writeUtf8String(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeUtf8Length(out, value.length)
        writeUtf8Length(out, bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        require(length <= 0x7FFF) { "文字列が長すぎます" }
        if (length > 0x7F) {
            out.write(((length shr 8) or 0x80) and 0xFF)
            out.write(length and 0xFF)
        } else {
            out.write(length)
        }
    }

    private companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val HEADER_SIZE = 8
        const val POOL_HEADER_SIZE = 28
        const val SORTED_FLAG = 1 shl 0
        const val UTF8_FLAG = 1 shl 8

        const val TYPE_STRING = 0x03

        // ResXMLTree_attribute 内のオフセット
        const val ATTR_NAMESPACE = 0
        const val ATTR_NAME = 4
        const val ATTR_RAW_VALUE = 8
        const val ATTR_DATA_TYPE = 15
        const val ATTR_DATA = 16
        const val ATTR_MIN_SIZE = 20

        fun readU16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        fun readU32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        fun writeU16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        fun writeU32(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
            data[offset + 2] = ((value shr 16) and 0xFF).toByte()
            data[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
    }
}
