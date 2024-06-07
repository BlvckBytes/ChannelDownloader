package me.blvckbytes.channel_downloader

import me.blvckbytes.springhttptesting.HttpClient
import me.blvckbytes.springhttptesting.HttpMethod
import java.net.URL
import javax.script.Invocable
import javax.script.ScriptEngineManager

/**
 * If a video has been age-restricted, there's a way to circumvent having to log in, based
 * on the iframe API - an embeddable video player. Making use of this alternative client key
 * when requesting adds an additional layer of complexity: URLs are no longer provided directly.
 * Now, a "signatureCipher" is to be extracted, which requires special runtime processing, depending
 * on the player-version. To not hard-code algorithms, the utility below tries to mask out relevant
 * pieces of code from the player's source, which will later be executed within a JS-environment.
 */
object PlayerDecoder {

  fun createPlayerDecoder(): Pair<Long, (String) -> String> {
//    val responseString = HttpClient.performRequest(
//      URL("https://www.youtube.com/iframe_api"),
//      HttpMethod.GET
//    ).responseString
//
//    // https:\/\/www.youtube.com\/s\/player\/dee49cfa\/...
//    //                                       ^^^^^^^^
//    val marker = "https:\\/\\/www.youtube.com\\/s\\/player\\/"
//    val markerIndex = responseString.indexOf(marker)
//    val beginIndex = markerIndex + marker.length
//    val endIndex = responseString.indexOf("\\/", beginIndex)
//    val playerVersion = responseString.substring(beginIndex, endIndex)

    // Since I know my below "source finder" to work on this exact version, I might as well keep it static...
    // If need ever be, the latest version fetcher above may be uncommented.
    val playerVersion = "dee49cfa"

    val playerSource = HttpClient.performRequest(
      URL("https://www.youtube.com/s/player/$playerVersion/player_ias.vflset/en_US/base.js"),
      HttpMethod.GET
    ).responseString

    val signatureTimestampMarker = "signatureTimestamp:"
    val signatureTimestampMarkerIndex = playerSource.indexOf(signatureTimestampMarker)
    val signatureTimestampBeginIndex = signatureTimestampMarkerIndex + signatureTimestampMarker.length

    var signatureTimestampEndIndex = signatureTimestampBeginIndex + 1

    while (signatureTimestampEndIndex < playerSource.length) {
      if (!playerSource[signatureTimestampEndIndex].isDigit()) {
        --signatureTimestampEndIndex
        break
      }

      ++signatureTimestampEndIndex
    }

    val signatureTimestamp = playerSource.substring(signatureTimestampBeginIndex, signatureTimestampEndIndex + 1)

    val decipherMethodMarker = "a=a.split(\"\");"

    val decipherMethodMarkerIndex = playerSource.lastIndexOf(decipherMethodMarker)
    val decipherMethodBlockBeginIndex = closestSurroundingIndex(playerSource, decipherMethodMarkerIndex, "{", true)
    val decipherMethodBlockEndIndex = closestSurroundingIndex(playerSource, decipherMethodMarkerIndex, "}", false)

    if (decipherMethodBlockBeginIndex < 0 || decipherMethodBlockEndIndex < 0)
      throw IllegalStateException("Could not locate the block begin- and or end-index of the decipher method!")

    val decipherMethodBody = playerSource.substring(decipherMethodBlockBeginIndex + 1, decipherMethodBlockEndIndex)
    val decipherMethodLines = decipherMethodBody.split(';')
    val decipherMethodDependency = decipherMethodLines[1].split('.', limit=2)[0]

    val dependencyDefinitionMarker = "$decipherMethodDependency="
    var dependencyDefinitionIndex = closestSurroundingIndex(playerSource, decipherMethodMarkerIndex, dependencyDefinitionMarker, true)

    if (dependencyDefinitionIndex < 0)
      dependencyDefinitionIndex = closestSurroundingIndex(playerSource, decipherMethodMarkerIndex, dependencyDefinitionMarker, false)

    if (dependencyDefinitionIndex < 0)
      throw IllegalStateException("Could not locate the index of the decipher method's dependency definition")

    val dependencyDefinitionFirstCurlyIndex = closestSurroundingIndex(playerSource, dependencyDefinitionIndex, "{", false)

    if (dependencyDefinitionFirstCurlyIndex < 0)
      throw IllegalStateException("Could not locate the index of the decipher method's dependency definition's first curly")

    var dependencyDefinitionEndIndex = dependencyDefinitionFirstCurlyIndex + 1
    var curlyCounter = 1

    while (dependencyDefinitionEndIndex < playerSource.length - 1) {
      val currChar = playerSource[dependencyDefinitionEndIndex]

      if (currChar == '{')
        ++curlyCounter

      else if (currChar == '}') {
        if (--curlyCounter == 0)
          break
      }

      ++dependencyDefinitionEndIndex
    }

    val dependencyBlock = playerSource.substring(
      dependencyDefinitionFirstCurlyIndex,
      dependencyDefinitionEndIndex + 1,
    )

    val engine = ScriptEngineManager().getEngineByName("JavaScript")

    engine.eval("var $decipherMethodDependency=$dependencyBlock;var decipher=function(a) {$decipherMethodBody};")

    return Pair(
      signatureTimestamp.toLong()
    ) { (engine as Invocable).invokeFunction("decipher", it).toString() }
  }

  private fun closestSurroundingIndex(text: String, index: Int, search: String, before: Boolean): Int {
    var position = index

    walkLoop@ while (position > 0 && position < text.length - 1) {
      position += if (before) -1 else 1

      if (position + search.length < text.length) {
        for (i in search.indices) {
          if (text[position + i] != search[i])
            continue@walkLoop
        }

        return position
      }
    }

    return -1
  }
}