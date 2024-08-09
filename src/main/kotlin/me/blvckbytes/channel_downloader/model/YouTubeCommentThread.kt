package me.blvckbytes.channel_downloader.model

import me.blvckbytes.springhttptesting.validation.JsonObjectExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

data class YouTubeCommentThread(
  override val id: String,
  override val videoId: String,
  override val text: String,
  override val likeCount: Int,
  override val publishedAt: ZonedDateTime,
  override val updatedAt: ZonedDateTime,
  override val authorDisplayName: String,
  override val authorChannelId: String,
  override val edits: Array<YouTubeCommentEdit>?,
  val replyCount: Int,
  val replies: Array<YouTubeCommentReply>,
) : YouTubeComment {
  companion object {
    fun extractCommentThread(extractor: JsonObjectExtractor): YouTubeCommentThread {
      return YouTubeCommentThread(
        extractor.extractValue("id", String::class),
        extractor.extractValue("snippet.topLevelComment.snippet.videoId", String::class),
        extractor.extractValue("snippet.topLevelComment.snippet.textOriginal", String::class),
        extractor.extractValue("snippet.topLevelComment.snippet.likeCount", Int::class),
        ZonedDateTime.parse(extractor.extractValue("snippet.topLevelComment.snippet.publishedAt", String::class)),
        ZonedDateTime.parse(extractor.extractValue("snippet.topLevelComment.snippet.updatedAt", String::class)),
        extractor.extractValue("snippet.topLevelComment.snippet.authorDisplayName", String::class),
        extractor.extractValue("snippet.topLevelComment.snippet.authorChannelId.value", String::class),
        arrayOf(),
        extractor.extractValue("snippet.totalReplyCount", Int::class),
        buildList {
          for (comment in extractor.extractValueIfExists("replies.comments", JSONArray::class) ?: return@buildList)
            add(YouTubeCommentReply.extractComment(JsonObjectExtractor(comment as JSONObject)))
        }.toTypedArray()
      )
    }
  }

  fun copyWithAdditionalEdit(edit: YouTubeCommentEdit): YouTubeCommentThread {
    return YouTubeCommentThread(
      id, videoId, text, likeCount, publishedAt, updatedAt,
      authorDisplayName, authorChannelId, if (edits != null) edits + edit else arrayOf(edit), replyCount, replies
    )
  }

  fun copyWithReplyList(replyList: Array<YouTubeCommentReply>): YouTubeCommentThread {
    return YouTubeCommentThread(
      id, videoId, text, likeCount, publishedAt, updatedAt,
      authorDisplayName, authorChannelId, edits, replyCount, replyList
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as YouTubeCommentThread

    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }

  override fun toString(): String {
    return "YouTubeCommentThread(authorDisplayName='$authorDisplayName', publishedAt='$publishedAt', replyCount=$replyCount, text='$text')"
  }
}