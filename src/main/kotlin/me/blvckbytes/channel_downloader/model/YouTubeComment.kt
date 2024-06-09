package me.blvckbytes.channel_downloader.model

import java.time.ZonedDateTime

interface YouTubeComment : Identifiable {
  override val id: String
  val videoId: String
  val text: String
  val likeCount: Int
  val publishedAt: ZonedDateTime
  val updatedAt: ZonedDateTime
  val authorDisplayName: String
  val authorChannelId: String
}