package me.blvckbytes.channel_downloader.model

interface YouTubeComment : Identifiable {
  override val id: String
  val videoId: String
  val text: String
  val likeCount: Int
  val publishedAt: String
  val updatedAt: String
  val authorDisplayName: String
  val authorChannelId: String
}