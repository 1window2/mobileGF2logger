package dev.gf2log.app.discord

import java.net.HttpURLConnection
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Sends one explicitly confirmed original CSV file to a validated Discord incoming webhook. */
class DiscordWebhookSender {
    fun send(webhook: String, csv: String) {
        val uri = DiscordWebhookPolicy.requireValid(webhook)
        val csvBytes = csv.toByteArray(Charsets.UTF_8)
        require(csvBytes.isNotEmpty() && csvBytes.size <= OriginalCsvPayload.MAX_CSV_BYTES)
        val boundary = "----GF2logger" + UUID.randomUUID().toString().replace("-", "")
        val prefix = (
            "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"files[0]\"; " +
                "filename=\"GF2logger-original.csv\"\r\n" +
                "Content-Type: text/csv; charset=utf-8\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val suffix = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
        val contentLength = prefix.size.toLong() + csvBytes.size + suffix.size
        require(contentLength <= MAX_REQUEST_BYTES)

        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            connection.setRequestProperty("User-Agent", "GF2logger-Android")
            connection.setFixedLengthStreamingMode(contentLength)
            connection.outputStream.use { output ->
                output.write(prefix)
                output.write(csvBytes)
                output.write(suffix)
                output.flush()
            }
            val status = connection.responseCode
            consumeBounded(
                if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    connection.errorStream
                } else {
                    connection.inputStream
                },
            )
            check(status in 200..299) { "Discord webhook returned HTTP " + status }
        } finally {
            connection.disconnect()
        }
    }

    private fun consumeBounded(input: java.io.InputStream?) {
        if (input == null) return
        input.use { stream ->
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) break
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val MAX_RESPONSE_BYTES = 8 * 1024
        const val MAX_REQUEST_BYTES = OriginalCsvPayload.MAX_CSV_BYTES + 4 * 1024L
    }
}
