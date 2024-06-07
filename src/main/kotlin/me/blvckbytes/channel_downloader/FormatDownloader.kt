package me.blvckbytes.channel_downloader

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

object FormatDownloader {

  private const val DOWNLOAD_BUFFER_SIZE = 1024 * 16
  private const val DOWNLOAD_PART_SIZE = 1024 * 1024 * 2
  private const val TOTAL_TRY_COUNT = 3

  /**
   * If an error occurs during the process of downloading blocks, the file is deleted automatically
   */
  fun downloadFormat(format: ResourceFormat, file: File, progressCallback: (Double) -> Unit): Boolean {
    if (file.exists())
      return false

    val downloadBuffer = ByteArray(DOWNLOAD_BUFFER_SIZE)

    try {
      file.outputStream().use { fileStream ->
        var partNumber = 0
        var totalRead = 0L

        while (totalRead < format.contentLength) {
          var expectedRead = DOWNLOAD_PART_SIZE.toLong()
          if (totalRead + expectedRead > format.contentLength)
            expectedRead = format.contentLength - totalRead

          ++partNumber

          val url = URL("${format.url}&range=$totalRead-${totalRead + expectedRead - 1}&rn=$partNumber")
          var triesLeft = TOTAL_TRY_COUNT
          var connection: HttpURLConnection

          do {
            connection = url.openConnection() as HttpURLConnection

            if (--triesLeft == 0)
              throw IllegalStateException("Could not fetch URL\n${connection.responseMessage}")
          } while (connection.responseCode != 200)

          val inputStream = connection.inputStream
          var currentRead = 0L

          while (true) {
            val readBytes = inputStream.read(downloadBuffer, 0, downloadBuffer.size)

            if (readBytes <= 0)
              break

            fileStream.write(downloadBuffer, 0, readBytes)

            currentRead += readBytes
            totalRead += readBytes

            if (currentRead == expectedRead)
              break

            progressCallback(floor(totalRead.toDouble() / format.contentLength * 100 * 100) / 100)
          }

          try {
            inputStream.close()
          } catch (_: Exception) {}
        }

        progressCallback(100.0)
      }
    } catch (exception: Exception) {
      file.delete()
      throw exception
    }

    return true
  }
}