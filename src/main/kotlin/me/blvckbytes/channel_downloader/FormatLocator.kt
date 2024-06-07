package me.blvckbytes.channel_downloader

import me.blvckbytes.springhttptesting.*
import me.blvckbytes.springhttptesting.validation.JsonObjectExtractor
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object FormatLocator {

  private const val TOTAL_TRY_COUNT = 3

  private val playerDecoderDecode: (String) -> String
  private val playerDecoderSignatureTimestamp: Long

  init {
    val playerDecoder = PlayerDecoder.createPlayerDecoder()

    playerDecoderSignatureTimestamp = playerDecoder.first
    playerDecoderDecode = playerDecoder.second
  }

  fun locateDownloadFormats(videoId: String, videoNumber: Int, videoCount: Int): Pair<ResourceFormat, ResourceFormat> {
    var triesLeft = TOTAL_TRY_COUNT
    var infoResponse: HttpResponse

    do {
      // Spoofing an android player; this should yield the fastest downloads, but cannot access age-gated content.
      infoResponse = HttpClient.performRequest(
        URL("https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"),
        HttpMethod.POST,
        requestHeaders = MultiValueStringMapBuilder()
          .add("Content-Type", "application/json"),
        requestBody = JsonObjectBuilder.empty {
          addString("videoId", videoId)
          addObject("context") {
            addObject("client") {
              addString("hl", "en")
              addString("gl", "US")
              addString("clientName", "ANDROID_TESTSUITE")
              addString("clientVersion", "1.9")
              addInt("androidSdkVersion", 31)
            }
          }
        }.jsonObject
      )

      if (--triesLeft == 0)
        throw IllegalStateException("Could not fetch formats URL for android-player")
    } while (infoResponse.statusCode != 200)

    var playabilityStatus = infoResponse.extractValue("playabilityStatus.status", String::class)

    if (playabilityStatus == "LOGIN_REQUIRED") {
      println("Trying alternative API-method for age-gated video $videoId")

      triesLeft = TOTAL_TRY_COUNT

      do {
        // There is one YouTube-client which allows unauthenticated access of age-gated
        // content: that's the embedded player used for Smart TV browsers.
        // This alternative may provide slower (throttled) URLs, so it's only made use of whenever necessary
        infoResponse = HttpClient.performRequest(
          URL("https://www.youtube.com/youtubei/v1/player?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"),
          HttpMethod.POST,
          requestHeaders = MultiValueStringMapBuilder()
            .add("Content-Type", "application/json"),
          requestBody = JsonObjectBuilder.empty {
            addString("videoId", videoId)
            addObject("context") {
              addObject("client") {
                addString("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                addString("clientVersion", "2.0")
              }
              addObject("thirdParty") {
                addString("embedUrl", "https://www.youtube.com")
              }
            }
            addObject("playbackContext") {
              addObject("contentPlaybackContext") {
                addLong("signatureTimestamp", playerDecoderSignatureTimestamp)
              }
            }
          }.jsonObject
        )

        if (--triesLeft == 0)
          throw IllegalStateException("Could not fetch formats URL for android-player")
      } while (infoResponse.statusCode != 200)

      playabilityStatus = infoResponse.extractValue("playabilityStatus.status", String::class)
    }

    if (playabilityStatus != "OK")
      throw IllegalStateException("Could not fetch formats; status was not OK: ${infoResponse.responseString}")

    val formats = infoResponse
      .extractObjectArray("streamingData.adaptiveFormats")
      .map(::JsonObjectExtractor)

    val bestVideoFormat = formats
      .filter { it.extractValue("mimeType", String::class).startsWith("video/mp4") }
      .sortedWith(compareBy({ it.extractValue("width", Int::class) }, { it.extractValue("fps", Int::class) }))
      .lastOrNull()
      ?: throw IllegalStateException("ERROR: Could not locate a mp4-format for video $videoId ($videoNumber/$videoCount)")

    val bestAudioFormat = formats
      .filter { it.extractValue("mimeType", String::class).startsWith("audio/mp4") }
      .maxByOrNull { it.extractValue("averageBitrate", Int::class) }
      ?: throw IllegalStateException("ERROR: Could not locate a m4a-format for video $videoId ($videoNumber/$videoCount)")

    return Pair(
      ResourceFormat(
        extractUrl(videoId, bestVideoFormat),
        bestVideoFormat.extractValue("contentLength", String::class).toLong(),
      ),
      ResourceFormat(
        extractUrl(videoId, bestAudioFormat),
        bestAudioFormat.extractValue("contentLength", String::class).toLong(),
      )
    )
  }

  private fun extractUrl(videoId: String, format: JsonObjectExtractor): String {
    val signatureCipher = format.extractValueIfExists("signatureCipher", String::class)

    if (signatureCipher != null) {
      println("Decoding signature cipher for video $videoId")
      val (_s, _sp, _url) = signatureCipher.split('&', limit = 3)

      val cipherValue = URLDecoder.decode(_s, Charsets.UTF_8).split('=', limit = 2)[1]
      val cipherParameterName = URLDecoder.decode(_sp, Charsets.UTF_8).split('=', limit = 2)[1]
      val cipherUrl = URLDecoder.decode(_url, Charsets.UTF_8).split('=', limit = 2)[1]

      return "$cipherUrl&$cipherParameterName=${URLEncoder.encode(playerDecoderDecode(cipherValue), Charsets.UTF_8)}"
    }

    val url = format.extractValueIfExists("url", String::class)

    if (url != null) {
      println("Accessing provided url for video $videoId")
      return url
    }

    throw IllegalStateException("Found neither signatureCipher nor url properties")
  }
}