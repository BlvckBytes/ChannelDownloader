package me.blvckbytes.channel_downloader.model

import me.blvckbytes.channel_downloader.ExtensionUtil
import me.blvckbytes.springhttptesting.validation.JsonObjectExtractor
import org.json.JSONObject
import java.time.ZonedDateTime

data class YouTubeVideo (
  override val id: String,
  val videoId: String,
  val publishedAt: ZonedDateTime,
  val title: String,
  val description: String,
  val thumbnailUrl: String,
  val comments: Array<YouTubeCommentThread>,
) : Identifiable {
  companion object {
    fun extract(extractor: JsonObjectExtractor): YouTubeVideo {
      return YouTubeVideo(
        extractor.extractValue("id", String::class),
        extractor.extractValue("contentDetails.videoId", String::class),
        ZonedDateTime.parse(extractor.extractValue("contentDetails.videoPublishedAt", String::class)),
        extractor.extractValue("snippet.title", String::class),
        extractor.extractValue("snippet.description", String::class),
        decideThumbnailUrl(extractor),
        arrayOf()
      )
    }

    private fun decideThumbnailUrl(extractor: JsonObjectExtractor): String {
      val thumbnails = extractor.extractValue("snippet.thumbnails", JSONObject::class)

      var maxWidth = 0
      var maxWidthUrl: String? = null

      for (key in thumbnails.keys()) {
        val thumbnail = thumbnails.getJSONObject(key)
        val width = thumbnail.getInt("width")

        if (width > maxWidth) {
          maxWidth = width
          maxWidthUrl = thumbnail.getString("url")
        }
      }

      return maxWidthUrl ?: throw IllegalStateException("Could not locate any thumbnail urls: $extractor")
    }
  }

  fun copyWithExtendedComments(commentList: List<YouTubeCommentThread>): Pair<ExtensionUtil.ExtensionResult, YouTubeVideo> {
    val totalExtensionResult: ExtensionUtil.ExtensionResult = ExtensionUtil.ExtensionResult()
    var addedEditAnywhere = false

    val (threadExtensionResult, totalComments) = ExtensionUtil.extend(comments.iterator(), commentList.iterator(), { localThread, remoteThread ->
      var resultThread = localThread

      if (
        resultThread.text != remoteThread.text &&
        (resultThread.edits?.lastOrNull()?.text != remoteThread.text)
      ) {
        resultThread = resultThread.copyWithAdditionalEdit(YouTubeCommentEdit(remoteThread.text, remoteThread.updatedAt))
        addedEditAnywhere = true
      }

      var addedEditToAReply = false

      val (replyExtensionResult, totalReplies) = ExtensionUtil.extend(resultThread.replies.iterator(), remoteThread.replies.iterator(), { localReply, remoteReply ->
        var resultReply = localReply

        if (
          resultReply.text != remoteReply.text &&
          (resultReply.edits?.lastOrNull()?.text != remoteReply.text)
        ) {
          resultReply = resultReply.copyWithAdditionalEdit(YouTubeCommentEdit(remoteReply.text, remoteReply.updatedAt))
          addedEditToAReply = true
        }

        resultReply
      })

      if (addedEditToAReply)
        addedEditAnywhere = true

      totalExtensionResult.extendBy(replyExtensionResult)

      if (addedEditToAReply || replyExtensionResult.countMissingLocally > 0)
        resultThread = resultThread.copyWithReplyList(totalReplies.toTypedArray())

      resultThread
    })

    totalExtensionResult.extendBy(threadExtensionResult)

    if (!addedEditAnywhere && totalExtensionResult.countMissingLocally == 0)
      return Pair(totalExtensionResult, this)

    return Pair(totalExtensionResult, YouTubeVideo(id, videoId, publishedAt, title, description, thumbnailUrl, totalComments.toTypedArray()))
  }

  inline fun forEachComment(handler: (comment: YouTubeComment) -> Unit) {
    comments.forEach {
      handler(it)
      it.replies.forEach(handler)
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as YouTubeVideo

    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }

  override fun toString(): String {
    return "YouTubeVideo(videoId='$videoId', publishedAt='$publishedAt', title='$title')"
  }
}