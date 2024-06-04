package me.blvckbytes.channel_downloader

import java.io.File

class Main {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      if (args.size < 3)
        throw IllegalStateException("Expected there to be three arguments: <channelHandle> <outputPath> <apiKey>")

      val downloader = ChannelDownloader(args[2], args[0], File(args[1]))
      Runtime.getRuntime().addShutdownHook(Thread { downloader.onShutdown() })
      downloader.download(false)
    }
  }
}