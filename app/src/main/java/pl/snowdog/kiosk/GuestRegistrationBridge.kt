package pl.snowdog.kiosk

import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class GuestRegistrationKioskConfig(
    val allowedOrigin: String,
    val kioskToken: String,
    val locationId: String,
    val deviceName: String,
    val printerHost: String,
    val printerPort: Int,
)

class GuestRegistrationBridge(
    private val webView: WebView,
    private val config: GuestRegistrationKioskConfig,
    private val isPageAllowed: () -> Boolean,
) {
    companion object {
        const val INTERFACE_NAME = "GuestRegistrationKiosk"
        private const val TEST_JOB = "test"
        private const val MAX_PDF_BYTES = 10 * 1024 * 1024
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val activeJobs = Collections.synchronizedSet(mutableSetOf<String>())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @JavascriptInterface
    fun getConfiguration(): String {
        if (!currentPageAllowed()) return "{}"
        return JSONObject()
            .put("token", config.kioskToken)
            .put("locationId", config.locationId)
            .put("deviceName", config.deviceName)
            .toString()
    }

    @JavascriptInterface
    fun print(jobId: String) {
        if (!currentPageAllowed() || !KioskSecurity.isValidJobId(jobId)) {
            notifyPage(jobId, "FAILED", "invalid_print_request")
            return
        }
        enqueue(jobId, "/api/kiosk/print-jobs/$jobId/pdf")
    }

    @JavascriptInterface
    fun getPrinterInfo(): String {
        if (!currentPageAllowed()) return "{}"
        return JSONObject()
            .put("host", config.printerHost)
            .put("port", config.printerPort)
            .put("dpi", ZplBadge.DPI)
            .put("widthDots", ZplBadge.widthDots)
            .put("lengthDots", ZplBadge.lengthDots)
            .toString()
    }

    @JavascriptInterface
    fun testPrint() {
        if (currentPageAllowed()) enqueue(TEST_JOB, "/api/kiosk/test-print/pdf")
    }

    private fun enqueue(jobId: String, pdfPath: String) {
        if (!activeJobs.add(jobId)) return
        executor.execute {
            var status = "FAILED"
            var error: String? = null
            var phase = "PDF-Abruf"
            try {
                if (jobId == TEST_JOB) notifyPage(jobId, "RUNNING", null, phase)
                val pdf = downloadPdf(pdfPath)
                phase = "Rasterung"
                if (jobId == TEST_JOB) notifyPage(jobId, "RUNNING", null, "${pdf.size} Byte PDF geladen. Besucherausweis wird gerastert.")
                val zpl = ZplBadge.render(webView.context.cacheDir, pdf)
                phase = "TCP-Versand"
                if (jobId == TEST_JOB) notifyPage(jobId, "RUNNING", null, "${zpl.size} Byte ZPL gerastert. Sende an den Drucker.")
                sendToPrinter(zpl)
                status = "PRINTED"
            } catch (exception: Exception) {
                val reason = exception.message?.take(160) ?: exception.javaClass.simpleName
                error = "$phase: $reason"
            } finally {
                if (jobId != TEST_JOB) runCatching { reportResult(jobId, status, error) }
                activeJobs.remove(jobId)
                notifyPage(jobId, status, error)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun currentPageAllowed(): Boolean = isPageAllowed()

    private fun downloadPdf(path: String): ByteArray {
        val request = Request.Builder()
            .url(config.allowedOrigin + path)
            .header("x-kiosk-token", config.kioskToken)
            .header("Accept", "application/pdf")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("pdf_http_${response.code}")
            if (response.body?.contentType()?.let { it.type == "application" && it.subtype == "pdf" } != true) {
                throw IllegalStateException("invalid_pdf_content_type")
            }
            val declaredLength = response.body?.contentLength() ?: -1
            if (declaredLength > MAX_PDF_BYTES) throw IllegalStateException("pdf_too_large")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            response.body!!.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_PDF_BYTES) throw IllegalStateException("pdf_too_large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray().also {
                if (it.size < 5 || !it.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                    throw IllegalStateException("invalid_pdf_signature")
                }
            }
        }
    }

    private fun sendToPrinter(zpl: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(config.printerHost, config.printerPort), 5_000)
            socket.soTimeout = 15_000
            socket.getOutputStream().use { output ->
                output.write(zpl)
                output.flush()
            }
        }
    }

    private fun reportResult(jobId: String, status: String, error: String?) {
        val json = JSONObject().put("status", status)
        if (error != null) json.put("errorMessage", error)
        val request = Request.Builder()
            .url("${config.allowedOrigin}/api/kiosk/print-jobs/$jobId/result")
            .header("x-kiosk-token", config.kioskToken)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("result_http_${response.code}")
        }
    }

    private fun notifyPage(jobId: String, status: String, error: String?, progress: String? = null) {
        val detail = JSONObject()
            .put("jobId", jobId)
            .put("status", status)
            .put("error", error)
            .put("progress", progress)
            .toString()
        webView.post {
            if (currentPageAllowed()) {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('native-print-result',{detail:$detail}));",
                    null,
                )
            }
        }
    }
}
