package app.qthrone.mobile.utils

/** Base64 encoder available on every supported Android API and in local JVM tests. */
internal object Base64Compat {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encodeToString(data: ByteArray, urlSafe: Boolean = false, padding: Boolean = true): String {
        val alphabet = if (urlSafe) URL_SAFE else STANDARD
        val output = StringBuilder((data.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < data.size) {
            val value = ((data[index].toInt() and 0xff) shl 16) or
                ((data[index + 1].toInt() and 0xff) shl 8) or
                (data[index + 2].toInt() and 0xff)
            output.append(alphabet[value ushr 18])
            output.append(alphabet[(value ushr 12) and 0x3f])
            output.append(alphabet[(value ushr 6) and 0x3f])
            output.append(alphabet[value and 0x3f])
            index += 3
        }
        val remaining = data.size - index
        if (remaining > 0) {
            val first = data[index].toInt() and 0xff
            val second = if (remaining == 2) data[index + 1].toInt() and 0xff else 0
            output.append(alphabet[first ushr 2])
            output.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            if (remaining == 2) output.append(alphabet[(second and 0x0f) shl 2])
            else if (padding) output.append('=')
            if (padding) output.append('=')
        }
        return output.toString()
    }
}
