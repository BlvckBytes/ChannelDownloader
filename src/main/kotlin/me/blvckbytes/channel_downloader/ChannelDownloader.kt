package me.blvckbytes.channel_downloader

import com.github.kiulian.downloader.YoutubeDownloader
import com.github.kiulian.downloader.downloader.YoutubeProgressCallback
import com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo
import com.github.kiulian.downloader.downloader.response.Response
import com.github.kiulian.downloader.downloader.response.ResponseStatus
import com.github.kiulian.downloader.model.videos.VideoInfo
import com.github.kiulian.downloader.model.videos.formats.AudioFormat
import com.github.kiulian.downloader.model.videos.formats.Format
import com.github.kiulian.downloader.model.videos.formats.VideoFormat
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import me.blvckbytes.channel_downloader.model.*
import me.blvckbytes.springhttptesting.HttpClient
import me.blvckbytes.springhttptesting.HttpMethod
import me.blvckbytes.springhttptesting.HttpResponse
import me.blvckbytes.springhttptesting.MultiValueStringMapBuilder
import me.blvckbytes.springhttptesting.validation.JsonObjectExtractor
import java.io.*
import java.net.URL
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.*

class ChannelDownloader(
  private val apiKey: String,
  ffmpegPath: String,
  private val channelHandle: String,
  rootDirectory: File,
) {

  companion object {
    private const val BASE_URL = "https://www.googleapis.com/youtube/v3"
    private const val VIDEOS_FILE_NAME = "videos.json"
    private const val VIDEOS_FOLDER_NAME = "videos"
    private const val DOWNLOAD_FILES_PREFIX = "download_"
    private const val AUDIO_FORMAT = "m4a"
    private const val VIDEO_FORMAT = "mp4"
  }

  private val videoOutputDir = File(rootDirectory, VIDEOS_FOLDER_NAME)
  private val videosFile = File(rootDirectory, VIDEOS_FILE_NAME)
  private val ffmpegExecutable = File(ffmpegPath)

  init {
    makeDirs(rootDirectory)
    makeDirs(videoOutputDir)

    if (!(ffmpegExecutable.isFile && ffmpegExecutable.canExecute()))
      throw IllegalStateException("Expected the ffmpeg executable to be an executable file: $ffmpegExecutable")
  }

  /*
    TODO: XR1BF50_qbU is age-restricted; download manually!
    TODO: Timestamps should really be objects, not plain strings...

    Requests are made as follows:

    Find "uploads" playlist using /channels (1 quota unit)
    Page through videos using /playlistItems (1 quota unit)
    For each video, fetch top-level comments using /commentThreads (1 quota unit)
    For each top-level comment, fetch replies using /comments (1 quota unit)
      NOTE: For few replies (<=5, I believe), replies inlined into the response of
      /commentThreads suffice, thus unnecessary additional requests are not issued

    At the time of writing this, there are ~940 videos with ~2500 top-level comments
    and ~4500 replies in total. One execution consumes ~300 quota units; with 10k units
    per day, one could pull updates around 30 times a day. Realistically, 1-5 times a
    day will suffice to capture all channel activity.
   */

  private val gson = GsonBuilder()
    .setPrettyPrinting()
    .serializeNulls()
    .create()

  private val youtubeDownloader = YoutubeDownloader()
  private var currentOutputFile: File? = null

  fun onShutdown() {
    // Delete (partially) corrupted file, which hasn't been fully completed
    if (currentOutputFile?.exists() == true) {
      if (currentOutputFile?.delete() == true)
        println("Deleted partially downloaded file at ${currentOutputFile?.path}")
    }
  }

  /**
   * @param update Whether to update local video and comment data using the YouTube API
   */
  fun download(update: Boolean) {
    var videos = loadLocalVideos()

    println("Loaded ${videos.size} local videos")

    if (update) {
      println("Updating local information")
      videos = pullUpdate(videos, channelHandle, apiKey)
      videosFile.writeText(gson.toJson(videos), Charsets.UTF_8)
    }

    println("Downloading videos")

    val errorVideoIds = mutableMapOf<String, String>()

    for ((videoIndex, video) in videos.withIndex()) {
      val error = downloadVideo(video.videoId, videoIndex + 1, videos.size)

      if (error != null)
        errorVideoIds[video.videoId] = error
    }

    println("The following video downloads resulted in an error:")

    errorVideoIds.forEach {
      println("- ${it.key}:")
      println(it.value)
    }
  }

  private fun pullUpdate(localVideos: List<YouTubeVideo>, channelHandle: String, apiKey: String): List<YouTubeVideo> {
    val playlistId = findUploadsPlaylistId(apiKey, channelHandle)
    val remoteVideos = fetchVideos(apiKey, playlistId)

    println("Fetched ${remoteVideos.size} remote videos")

    val (videosExtensionResult, totalVideos) = ExtensionUtil.extend(localVideos.iterator(), remoteVideos.iterator())

    println("Extended YouTube video list: $videosExtensionResult")

    val (commentsExtensionResult, updatedVideos) = updateComments(apiKey, totalVideos) { video, number, extensionResult ->
      print("Updated comments for videoId ${video.videoId}, $number/${remoteVideos.size}: ")

      if (extensionResult.countMissingLocally == 0)
        println("no delta")
      else
        println(extensionResult)
    }

    println("Extended all video comments: $commentsExtensionResult")
    return updatedVideos
  }

  private fun combineVideoFiles(videoId: String, videoFile: File, audioFile: File): String? {
    println("Combining separated audio/video files ${videoFile.name} and ${audioFile.name}")

    val outputFile = File(videoFile.parentFile, "$videoId.$VIDEO_FORMAT")

    val process = ProcessBuilder(
      ffmpegExecutable.absolutePath,
      "-i", videoFile.absolutePath,
      "-i", audioFile.absolutePath,
      "-acodec", "copy",
      "-vcodec", "copy",
      "-y", // Overwrite if exists
      outputFile.absolutePath
    )
      .redirectErrorStream(true)
      .start()

    val logBuilder = StringBuilder()

    BufferedReader(InputStreamReader(process.inputStream)).use {
      while (true) {
        val line = it.readLine() ?: break
        logBuilder.append(line)
        println(line)
      }
    }

    if (process.waitFor() == 0) {
      println("Deleting separated files ${videoFile.name} and ${audioFile.name}")

      if (!videoFile.delete())
        return "Could not delete downloaded video file"

      if (!audioFile.delete())
        return "Could not delete downloaded audio file"

      println("Finished combining into ${outputFile.name}")
      return null
    }

    println("An error occurred while trying to combine audio/video files, deleting result")
    outputFile.delete()
    return logBuilder.toString()
  }

  private fun downloadVideo(videoId: String, videoNumber: Int, videoCount: Int): String? {
    if (File(videoOutputDir, "$videoId.$VIDEO_FORMAT").exists()) {
      println("Found existing combined audio/video file for video $videoId ($videoNumber/$videoCount)")
      return null
    }

    val videoFile = File(videoOutputDir, "$DOWNLOAD_FILES_PREFIX$videoId.$VIDEO_FORMAT")
    val audioFile = File(videoOutputDir, "$DOWNLOAD_FILES_PREFIX$videoId.$AUDIO_FORMAT")
    val videoInfoRequest = RequestVideoInfo(videoId)
    val response: Response<VideoInfo> = youtubeDownloader.getVideoInfo(videoInfoRequest)

    if (response.status() == ResponseStatus.error)
      return response.error().stackTraceToString()

    val video: VideoInfo = response.data()

    val bestVideoFormat = video.videoFormats()
      .filter { it.extension().value() == VIDEO_FORMAT }
      .sortedWith(compareBy({ it.videoQuality().ordinal }, { it.fps() }))
      .last()
      ?: return "ERROR: Could not locate a $VIDEO_FORMAT-format for video $videoId ($videoNumber/$videoCount)"

    val videoError = downloadFormat(videoFile, bestVideoFormat, videoNumber, videoCount)

    if (videoError != null)
      return videoError.stackTraceToString()

    val bestAudioFormat = video.audioFormats()
      .filter { it.extension().value() == AUDIO_FORMAT }
      .maxByOrNull { it.audioQuality().ordinal }
      ?: return "ERROR: Could not locate a $AUDIO_FORMAT-format for video $videoId ($videoNumber/$videoCount)"

    val audioError = downloadFormat(audioFile, bestAudioFormat, videoNumber, videoCount)

    if (audioError != null)
      return audioError.stackTraceToString()

    return combineVideoFiles(videoId, videoFile, audioFile)
  }

  private fun downloadFormat(
    outputFile: File, format: Format, videoNumber: Int, videoCount: Int,
    skipExistenceCheck: Boolean = false
  ): Throwable? {
    val fileName = outputFile.name

    if (!skipExistenceCheck && outputFile.exists()) {
      println("Skipping existing file $fileName ($videoNumber/$videoCount)")
      return null
    }

    currentOutputFile = outputFile

    val request = RequestVideoFileDownload(format)
      .saveTo(videoOutputDir)
      .overwriteIfExists(true)
      .renameTo(outputFile.nameWithoutExtension)
      .callback(object : YoutubeProgressCallback<File> {
        override fun onDownloading(progress: Int) {
          println("Downloaded $progress% of $fileName ($videoNumber/$videoCount)")
        }

        override fun onFinished(videoInfo: File) {
          val qualityString = when(format) {
            is VideoFormat -> "${format.videoQuality().name} ${format.fps()} fps"
            is AudioFormat -> "${format.audioQuality().name} sampleRate ${format.audioSampleRate()}"
            else -> "?"
          }

          println("Finished downloading $fileName (quality $qualityString)")
          currentOutputFile = null
        }

        override fun onError(throwable: Throwable) {
          outputFile.delete() // Maybe next time, right? ;)
          throwable.printStackTrace()
        }
      })
      .async()


    val result = youtubeDownloader.downloadVideoFile(request)
    result.data() // will block current thread

    if (result.status() == ResponseStatus.error)
      return result.error()

    return null
  }

  private fun findUploadsPlaylistId(apiKey: String, handle: String): String {
    val uploadsPlaylistIds = collectPaginationItems(
      apiKey,
      { baseParams -> HttpClient.performRequest(
        URL(joinPaths(BASE_URL, "channels")),
        HttpMethod.GET,
        requestParams = baseParams
          .add("part", "contentDetails")
          .add("forHandle", handle)
      ) },
      mapper@ {
        if (it.extractValue("kind", String::class) != "youtube#channel")
          return@mapper null

        it.extractValue("contentDetails.relatedPlaylists.uploads", String::class)
      },
      { null }
    )

    if (uploadsPlaylistIds.size != 1)
      throw IllegalStateException("Expected there to be exactly one upload playlist: ${uploadsPlaylistIds.joinToString()}")

    return uploadsPlaylistIds.first()
  }

  private fun loadLocalVideos(): List<YouTubeVideo> {
    return try {
      return (
        gson.fromJson(
          videosFile.readText(Charsets.UTF_8),
          Array<YouTubeVideo>::class.java
        )
          .map { Pair(it, DateTimeFormatter.ISO_DATE_TIME.parse(it.publishedAt)) }
          .sortedByDescending { it.second.getLong(ChronoField.INSTANT_SECONDS) }
          .map { it.first }
          .toList()
      )
    } catch (exception: Exception) {
      when (exception) {
        is FileNotFoundException, is JsonSyntaxException -> listOf()
        else -> throw exception
      }
    }
  }

  private fun updateComments(
    apiKey: String,
    videos: Iterable<YouTubeVideo>,
    progressCallback: (video: YouTubeVideo, elementNumber: Int, extensionResult: ExtensionUtil.ExtensionResult) -> Unit
  ): Pair<ExtensionUtil.ExtensionResult, List<YouTubeVideo>> {
    val totalExtensionResult = ExtensionUtil.ExtensionResult()
    val result = buildList {
      var elementNumber = 0

      for (video in videos) {
        val (extensionResult, extendedVideo) = video.copyWithExtendedComments(fetchComments(apiKey, video))
        totalExtensionResult.extendBy(extensionResult)
        add(extendedVideo)

        progressCallback(video, ++elementNumber, extensionResult)
      }
    }

    return Pair(totalExtensionResult, result)
  }

  private fun fetchComments(apiKey: String, video: YouTubeVideo): List<YouTubeCommentThread> {
    return collectPaginationItems(
      apiKey,
      { baseParams -> HttpClient.performRequest(
        URL(joinPaths(BASE_URL, "commentThreads")),
        HttpMethod.GET,
        requestParams = baseParams
          .add("videoId", video.videoId)
          .add("textFormat", "plainText")
          .add("part", "id,replies,snippet")
          .add("maxResults", 100)
      ) },
      { extractor ->
        var commentThread = YouTubeCommentThread.extractCommentThread(extractor)

        // If the number of replies inlined into the threads-response does not match the number of replies
        // in total, that means that it only represents a subset, which in turn requires fetching the replies
        // in the usual paginated manner, to then exchange the list of replies within the thread object.
        if (commentThread.replyCount != commentThread.replies.size) {
          println("Only got ${commentThread.replies.size}/${commentThread.replyCount} inlined reply-comments")

          commentThread = commentThread.copyWithReplyList(
            collectPaginationItems(
              apiKey,
              { baseParams -> HttpClient.performRequest(
                URL(joinPaths(BASE_URL, "comments")),
                HttpMethod.GET,
                requestParams = baseParams
                  .add("videoId", video.videoId)
                  .add("parentId", commentThread.id)
                  .add("textFormat", "plainText")
                  .add("part", "id,snippet")
                  .add("maxResults", 100)
              ) },
              { YouTubeCommentReply.extractComment(it, video.videoId) },
              { null },
              { println("There are $it reply-comments in total") },
              { println("Fetched $it reply-comments") }
            ).toTypedArray()
          )
        }

        commentThread
      },
      {
        if (
          it.statusCode == 403 &&
          it.extractValue("error.errors.0.reason", String::class) == "commentsDisabled"
        ) {
          println("Comments are disabled on video ${video.videoId}")
          listOf()
        }
        else
          null
      },
      { println("There are $it top-level comments in total") },
      { println("Fetched $it top-level comments") }
    )
  }

  private fun fetchVideos(apiKey: String, playlistId: String): List<YouTubeVideo> {
    return collectPaginationItems(
      apiKey,
      { baseParams -> HttpClient.performRequest(
        URL(joinPaths(BASE_URL, "playlistItems")),
        HttpMethod.GET,
        requestParams = baseParams
          .add("part", "snippet,contentDetails")
          .add("playlistId", playlistId)
          .add("maxResults", 50)
      ) },
      YouTubeVideo.Companion::extract,
      { null },
      { println("There are $it videos in total") },
      { println("Fetched $it videos") }
    )
  }

  private inline fun <T> collectPaginationItems(
    apiKey: String,
    responseGenerator: (baseParams: MultiValueStringMapBuilder) -> HttpResponse,
    mapper: (item: JsonObjectExtractor) -> T?,
    errorResponseFallbackGenerator: (response: HttpResponse) -> List<T>?,
    totalSizeCallback: (size: Int) -> Unit = {},
    fetchSizeCallback: (size: Int) -> Unit = {},
  ): List<T> {
    return buildList {
      var nextPageToken: String? = null

      do {
        val response = responseGenerator(makeBaseParams(apiKey, nextPageToken))

        if (response.statusCode != 200) {
          return errorResponseFallbackGenerator(response)
            ?: throw IllegalStateException("Encountered status-code of ${response.statusCode}: $response")
        }

        if (nextPageToken == null) {
          // In the case of /comments, totalResults is already known by the parent's totalReplyCount,
          // so they seem to have thought that it'd be a good idea to omit this field...
          response.extractValueIfExists("pageInfo.totalResults", Int::class)?.let { totalSizeCallback(it) }
        }

        nextPageToken = response.extractValueIfExists("nextPageToken", String::class)

        val items = response.extractObjectArray("items")

        fetchSizeCallback(items.size)

        for (item in items)
          add(mapper(JsonObjectExtractor(item)) ?: continue)
      } while (nextPageToken != null)
    }
  }

  private fun makeBaseParams(apiKey: String, pageToken: String? = null): MultiValueStringMapBuilder {
    val result = MultiValueStringMapBuilder()
      .add("key", apiKey)

    if (pageToken != null)
      result.add("pageToken", pageToken)

    return result
  }

  private fun joinPaths(vararg paths: String): String {
    val result = StringBuilder()

    for (path in paths) {
      if (result.isEmpty()) {
        result.append(path)
        continue
      }

      if (result.last() == '/') {
        if (path[0] == '/') {
          result.append(path.substring(1))
          continue
        }

        result.append(path)
        continue
      }

      if (path[0] == '/') {
        result.append(path)
        continue
      }

      result.append('/').append(path)
    }

    return result.toString()
  }

  private fun makeDirs(directory: File) {
    if (!directory.exists()) {
      if (!directory.mkdirs())
        throw IllegalStateException("Could not create directory $directory")
    }
    else if (!directory.isDirectory)
      throw IllegalStateException("Expected $directory to be a directory")

  }
}