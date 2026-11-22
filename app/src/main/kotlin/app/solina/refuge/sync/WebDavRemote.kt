package app.solina.refuge.sync

import app.solina.refuge.core.sync.SyncRemote
import app.solina.refuge.core.sync.WebDavProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

/**
 * همگام‌سازی رمزگذاری‌شده روی هر سرور WebDAV استاندارد (Nextcloud، ownCloud، Synology یا سرور اختصاصی).
 *
 * امنیت:
 * - فقط HTTPS مجاز است (`WebDavProtocol.normalizeHttpsBaseUrl`).
 * - همهٔ داده‌ها پیش از رسیدن به این لایه توسط `SyncEngine` با AES-256-GCM و HMAC-SHA256
 *   رمز و امضا شده‌اند؛ سرور هیچ دسترسی به کلید یا محتوای خزانه ندارد.
 */
class WebDavRemote(
    baseUrl: String,
    private val username: String,
    private val password: String,
) : SyncRemote {
    private val rootUrl: String = WebDavProtocol.normalizeHttpsBaseUrl(baseUrl)
    private val rootPath: String = URI(rootUrl).rawPath ?: "/"
    private val authHeader: String? = if (username.isNotEmpty() || password.isNotEmpty()) {
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    } else null

    companion object {
        /** سقف خواندن یک پاسخ از سرور؛ رکورد همگام‌سازی حداکثر ~۴ مگابایت و مانیفست
         * حداکثر ~۴ مگابایت است، پس ۸ مگابایت (مثل FolderRemote) امن است. سرور
         * به‌خطرافتاده نمی‌تواند با پاسخ غول‌پیکر حافظهٔ برنامه را پر کند. */
        const val MAX_READ_BYTES = 8 * 1024 * 1024

        /** خواندن با سقف؛ null اگر ورودی بیش از سقف باشد (به‌جای OutOfMemory). */
        internal fun readCapped(input: java.io.InputStream, maxBytes: Int = MAX_READ_BYTES): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) return null
            }
            return out.toByteArray()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        // برخی پیاده‌سازی‌های HttpURLConnection متد PROPFIND/MKCOL را مستقیم می‌پذیرند یا با override کار می‌کنند.
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = false
        authHeader?.let { conn.setRequestProperty("Authorization", it) }
        conn.setRequestProperty("User-Agent", "Solina-Refuge")
        return conn
    }

    private fun fileUrl(name: String): String {
        require(WebDavProtocol.isSafeFileName(name)) { "نام فایل نامعتبر است" }
        return rootUrl + name
    }

    /** اطمینان از وجود پوشهٔ همگام‌سازی و اعتبار دسترسی سرور. */
    suspend fun ensureReady(): Unit = withContext(Dispatchers.IO) {
        val conn = open(rootUrl, "PROPFIND")
        conn.setRequestProperty("Depth", "0")
        try {
            val code = conn.responseCode
            if (code == 404) {
                conn.disconnect()
                val mk = open(rootUrl, "MKCOL")
                try {
                    if (mk.responseCode !in 200..299 && mk.responseCode != 405) {
                        throw IOException("MKCOL failed: HTTP ${mk.responseCode}")
                    }
                } finally {
                    mk.disconnect()
                }
            } else if (code !in setOf(200, 207)) {
                throw IOException("WebDAV auth/access failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun readHeader(): ByteArray? = read("vault.hdr")

    suspend fun writeHeader(bytes: ByteArray) = write("vault.hdr", bytes)

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val conn = open(rootUrl, "PROPFIND")
        conn.setRequestProperty("Depth", "1")
        try {
            val code = conn.responseCode
            if (code == 404) return@withContext emptyList()
            if (code !in setOf(200, 207)) throw IOException("PROPFIND failed: HTTP $code")
            val raw = conn.inputStream.use { readCapped(it) }
                ?: throw IOException("PROPFIND response too large")
            val xml = String(raw, Charsets.UTF_8)
            WebDavProtocol.parsePropfindFileNames(xml, rootPath)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val conn = open(fileUrl(name), "GET")
        try {
            val code = conn.responseCode
            if (code == 404) return@withContext null
            if (code !in 200..299) throw IOException("GET failed: HTTP $code")
            conn.inputStream.use { readCapped(it) }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun write(name: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val conn = open(fileUrl(name), "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(bytes.size)
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("PUT failed: HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        val conn = open(fileUrl(name), "DELETE")
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw IOException("DELETE failed: HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}
