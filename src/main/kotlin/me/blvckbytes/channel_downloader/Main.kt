package me.blvckbytes.channel_downloader

import java.io.File

class Main {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      if (args.size < 4)
        throw IllegalStateException("Expected there to be four arguments: <channelHandle> <outputPath> <apiKey> <ffmpegExecutable> [update]")

      val downloader = ChannelDownloader(args[2], args[3], args[0], File(args[1]))
      val update = if (args.size >= 5) args[4].toBoolean() else false

      Runtime.getRuntime().addShutdownHook(Thread { downloader.onShutdown() })
      downloader.download(update)
    }
  }
}