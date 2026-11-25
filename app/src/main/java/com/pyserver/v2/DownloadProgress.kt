package com.pyserver.v2

data class DownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val fileName: String = "Media",
    val status: String = "downloading"
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

fun formatByteCount(bytes: Long): String {
    if (bytes < 0L) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = bytes.toDouble()
    var unit = 0
    while (amount >= 1024.0 && unit < units.lastIndex) {
        amount /= 1024.0
        unit++
    }
    return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(java.util.Locale.US, "%.1f %s", amount, units[unit])
}
