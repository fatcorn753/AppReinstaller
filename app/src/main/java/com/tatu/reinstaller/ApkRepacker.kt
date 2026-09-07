package com.tatu.reinstaller

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * APK (= ZIP) を、一部のエントリだけ差し替えて書き直す。
 *
 * 圧縮済みデータをそのままバイトコピーするので、再圧縮が発生しない。
 * ZipOutputStream 経由だと全エントリを展開→再圧縮することになり、
 * 100MB 級の APK では端末上で分単位かかってしまう。
 *
 * ついでに zipalign 相当の整列も行う。無圧縮エントリは4バイト境界、
 * ネイティブライブラリは 16KB ページ境界に載せる。
 */
object ApkRepacker {

    private data class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val modTime: Int,
        val modDate: Int,
        val crc: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
        val externalAttrs: Int
    )

    /**
     * @param replacements 差し替えるエントリ（名前 -> 中身）。無圧縮で書き込む。
     * @param drop true を返した名前のエントリは出力しない。
     */
    fun repack(
        source: File,
        dest: File,
        replacements: Map<String, ByteArray>,
        drop: (String) -> Boolean
    ) {
        RandomAccessFile(source, "r").use { input ->
            val entries = readCentralDirectory(input)
            val written = mutableListOf<Pair<Entry, Int>>()
            val buffer = ByteArray(COPY_BUFFER)

            CountingOutputStream(BufferedOutputStream(dest.outputStream(), COPY_BUFFER)).use { output ->
                for (entry in entries) {
                    if (drop(entry.name)) continue

                    val replacement = replacements[entry.name]
                    val outEntry = if (replacement != null) {
                        entry.copy(
                            method = METHOD_STORED,
                            // データ記述子は使わないのでフラグを落とす。
                            flags = entry.flags and DATA_DESCRIPTOR_FLAG.inv(),
                            crc = crc32(replacement),
                            compressedSize = replacement.size,
                            uncompressedSize = replacement.size
                        )
                    } else {
                        entry.copy(flags = entry.flags and DATA_DESCRIPTOR_FLAG.inv())
                    }

                    val nameBytes = outEntry.name.toByteArray(Charsets.UTF_8)
                    val padding = paddingFor(output.count, nameBytes.size, outEntry)
                    val offset = output.count
                    writeLocalHeader(output, outEntry, nameBytes, padding)

                    if (replacement != null) {
                        output.write(replacement)
                    } else {
                        copyRaw(input, output, entry, buffer)
                    }
                    written.add(outEntry to offset)
                }

                val centralStart = output.count
                for ((entry, offset) in written) {
                    writeCentralEntry(output, entry, offset)
                }
                val centralSize = output.count - centralStart
                writeEndOfCentralDirectory(output, written.size, centralSize, centralStart)
            }
        }
    }

    /** 無圧縮エントリのデータ位置を境界に合わせるための詰め物の長さ。 */
    private fun paddingFor(position: Int, nameLength: Int, entry: Entry): Int {
        if (entry.method != METHOD_STORED) return 0
        val alignment = if (entry.name.endsWith(".so")) PAGE_ALIGNMENT else DEFAULT_ALIGNMENT
        val dataStart = position + LOCAL_HEADER_SIZE + nameLength
        var padding = (alignment - dataStart % alignment) % alignment
        // extra field はヘッダ4バイトを含むので、1〜3バイトでは表現できない。
        if (padding in 1 until EXTRA_HEADER_SIZE) padding += alignment
        return padding
    }

    // ------------------------------------------------------------------ 読み込み

    private fun readCentralDirectory(input: RandomAccessFile): List<Entry> {
        val fileLength = input.length().toInt()
        val searchLength = minOf(fileLength, MAX_EOCD_SEARCH)
        val tail = ByteArray(searchLength)
        input.seek((fileLength - searchLength).toLong())
        input.readFully(tail)

        var eocd = -1
        for (i in searchLength - EOCD_SIZE downTo 0) {
            if (readU32(tail, i) == EOCD_SIGNATURE) {
                eocd = i
                break
            }
        }
        require(eocd >= 0) { "ZIP の終端レコードが見つかりません（APK が壊れている可能性があります）" }

        val totalEntries = readU16(tail, eocd + 10)
        val centralOffset = readU32(tail, eocd + 16)
        val centralSize = readU32(tail, eocd + 12)
        require(totalEntries != 0xFFFF && centralOffset != -1) { "ZIP64 形式の APK には対応していません" }

        val central = ByteArray(centralSize)
        input.seek(centralOffset.toLong())
        input.readFully(central)

        val entries = ArrayList<Entry>(totalEntries)
        var position = 0
        repeat(totalEntries) {
            require(readU32(central, position) == CENTRAL_SIGNATURE) { "中央ディレクトリが壊れています" }
            val nameLength = readU16(central, position + 28)
            val extraLength = readU16(central, position + 30)
            val commentLength = readU16(central, position + 32)
            entries.add(
                Entry(
                    name = String(central, position + CENTRAL_HEADER_SIZE, nameLength, Charsets.UTF_8),
                    flags = readU16(central, position + 8),
                    method = readU16(central, position + 10),
                    modTime = readU16(central, position + 12),
                    modDate = readU16(central, position + 14),
                    crc = readU32(central, position + 16),
                    compressedSize = readU32(central, position + 20),
                    uncompressedSize = readU32(central, position + 24),
                    localHeaderOffset = readU32(central, position + 42),
                    externalAttrs = readU32(central, position + 38)
                )
            )
            position += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** 元ファイルの圧縮済みデータを展開せずにそのまま流し込む。 */
    private fun copyRaw(
        input: RandomAccessFile,
        output: OutputStream,
        entry: Entry,
        buffer: ByteArray
    ) {
        val header = ByteArray(LOCAL_HEADER_SIZE)
        input.seek(entry.localHeaderOffset.toLong())
        input.readFully(header)
        require(readU32(header, 0) == LOCAL_SIGNATURE) { "ローカルヘッダが壊れています: ${entry.name}" }
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE +
            readU16(header, 26) + readU16(header, 28)

        input.seek(dataOffset.toLong())
        var remaining = entry.compressedSize
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            require(read > 0) { "データが途中で終わっています: ${entry.name}" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    // ------------------------------------------------------------------ 書き込み

    private fun writeLocalHeader(
        output: OutputStream,
        entry: Entry,
        nameBytes: ByteArray,
        padding: Int
    ) {
        val header = ByteArray(LOCAL_HEADER_SIZE)
        writeU32(header, 0, LOCAL_SIGNATURE)
        writeU16(header, 4, VERSION_NEEDED)
        writeU16(header, 6, entry.flags)
        writeU16(header, 8, entry.method)
        writeU16(header, 10, entry.modTime)
        writeU16(header, 12, entry.modDate)
        writeU32(header, 14, entry.crc)
        writeU32(header, 18, entry.compressedSize)
        writeU32(header, 22, entry.uncompressedSize)
        writeU16(header, 26, nameBytes.size)
        writeU16(header, 28, padding)
        output.write(header)
        output.write(nameBytes)
        if (padding > 0) output.write(alignmentExtraField(padding))
    }

    /** zipalign と同じ書式の詰め物 extra field。 */
    private fun alignmentExtraField(length: Int): ByteArray {
        val extra = ByteArray(length)
        writeU16(extra, 0, ALIGNMENT_EXTRA_ID)
        writeU16(extra, 2, length - EXTRA_HEADER_SIZE)
        return extra
    }

    private fun writeCentralEntry(output: OutputStream, entry: Entry, offset: Int) {
        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
        val header = ByteArray(CENTRAL_HEADER_SIZE)
        writeU32(header, 0, CENTRAL_SIGNATURE)
        writeU16(header, 4, VERSION_MADE_BY)
        writeU16(header, 6, VERSION_NEEDED)
        writeU16(header, 8, entry.flags)
        writeU16(header, 10, entry.method)
        writeU16(header, 12, entry.modTime)
        writeU16(header, 14, entry.modDate)
        writeU32(header, 16, entry.crc)
        writeU32(header, 20, entry.compressedSize)
        writeU32(header, 24, entry.uncompressedSize)
        writeU16(header, 28, nameBytes.size)
        writeU16(header, 30, 0)
        writeU16(header, 32, 0)
        writeU16(header, 34, 0)
        writeU16(header, 36, 0)
        writeU32(header, 38, entry.externalAttrs)
        writeU32(header, 42, offset)
        output.write(header)
        output.write(nameBytes)
    }

    private fun writeEndOfCentralDirectory(
        output: OutputStream,
        entryCount: Int,
        centralSize: Int,
        centralOffset: Int
    ) {
        val eocd = ByteArray(EOCD_SIZE)
        writeU32(eocd, 0, EOCD_SIGNATURE)
        writeU16(eocd, 8, entryCount)
        writeU16(eocd, 10, entryCount)
        writeU32(eocd, 12, centralSize)
        writeU32(eocd, 16, centralOffset)
        output.write(eocd)
    }

    private fun crc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    /** 現在の書き込み位置を知るためだけのラッパー。整列計算に使う。 */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()

        override fun close() {
            delegate.flush()
            delegate.close()
        }
    }

    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22
    private const val EXTRA_HEADER_SIZE = 4
    private const val ALIGNMENT_EXTRA_ID = 0xd935
    private const val METHOD_STORED = 0
    private const val DATA_DESCRIPTOR_FLAG = 1 shl 3
    private const val VERSION_NEEDED = 20
    private const val VERSION_MADE_BY = 20
    private const val DEFAULT_ALIGNMENT = 4
    private const val PAGE_ALIGNMENT = 16384
    private const val COPY_BUFFER = 256 * 1024
    private const val MAX_EOCD_SEARCH = 66 * 1024

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
