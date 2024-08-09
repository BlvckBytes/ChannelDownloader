package me.blvckbytes.channel_downloader.model

import me.blvckbytes.springhttptesting.validation.JsonObjectExtractor
import java.time.ZonedDateTime

data class YouTubeCommentReply(
  override val id: String,
  override val videoId: String,
  override val text: String,
  override val likeCount: Int,
  override val publishedAt: ZonedDateTime,
  override val updatedAt: ZonedDateTime,
  override val authorDisplayName: String,
  override val authorChannelId: String,
  override val edits: Array<YouTubeCommentEdit>?,
) : YouTubeComment {
  companion object {
    fun extractComment(extractor: JsonObjectExtractor, videoId: String? = null): YouTubeCommentReply {
      return YouTubeCommentReply(
        extractor.extractValue("id", String::class),
        videoId ?: extractor.extractValue("snippet.videoId", String::class),
        extractor.extractValue("snippet.textOriginal", String::class),
        extractor.extractValue("snippet.likeCount", Int::class),
        ZonedDateTime.parse(extractor.extractValue("snippet.publishedAt", String::class)),
        ZonedDateTime.parse(extractor.extractValue("snippet.updatedAt", String::class)),
        extractor.extractValue("snippet.authorDisplayName", String::class),
        extractor.extractValue("snippet.authorChannelId.value", String::class),
        arrayOf(),
      )
    }
  }

  fun copyWithAdditionalEdit(edit: YouTubeCommentEdit): YouTubeCommentReply {
    return YouTubeCommentReply(
      id, videoId, text, likeCount, publishedAt, updatedAt,
      authorDisplayName, authorChannelId, if (edits != null) edits + edit else arrayOf(edit)
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as YouTubeCommentReply

    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }

  override fun toString(): String {
    return "YouTubeCommentReply(authorDisplayName='$authorDisplayName', publishedAt='$publishedAt', text='$text')"
  }
}