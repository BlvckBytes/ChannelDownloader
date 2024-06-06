package me.blvckbytes.channel_downloader

import java.io.File

class Main {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      if (args.size != 1)
        throw IllegalStateException("Expected there to be an argument: <outputPath>")

      val rootDirectory = File(args[0])

      ChannelDownloader.makeDirs(rootDirectory)

      val configFile = File(rootDirectory, "config.txt")

      if (!configFile.exists())
        configFile.writeBytes(Main::class.java.getResourceAsStream("/config.txt")!!.readAllBytes())

      if (!configFile.isFile)
        throw IllegalStateException("Expected $configFile to be a file")

      val configMap = buildMap {
        configFile.readLines()
          .map(String::trimStart)
          .filterNot { it.isBlank() || it.startsWith('#') }
          .forEach {
            val equalsIndex = it.indexOf('=')

            if (equalsIndex < 1)
              throw IllegalStateException("Missing '=' on line $it")

            put(
              it.substring(0, equalsIndex).trimEnd().lowercase(),
              it.substring(equalsIndex + 1).trimEnd(),
            )
          }
      }

      val accessConfigKey: (String) -> String = {
        configMap[it.lowercase()] ?: throw IllegalStateException("Missing config-key \"$it\"")
      }

      val downloader = ChannelDownloader(
        accessConfigKey("apiKey"),
        accessConfigKey("ffmpegPath"),
        accessConfigKey("channelHandle"),
        rootDirectory,
      )

      Runtime.getRuntime().addShutdownHook(Thread { downloader.onShutdown() })

      downloader.download(
        accessConfigKey("updateJson").toBoolean(),
        accessConfigKey("downloadVideos").toBoolean(),
      )
    }
  }
}