package me.blvckbytes.channel_downloader

import com.google.gson.*
import java.lang.reflect.Type
import java.time.ZonedDateTime

object ZonedDateTimeAdapter : JsonSerializer<ZonedDateTime>, JsonDeserializer<ZonedDateTime> {

  override fun serialize(src: ZonedDateTime?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
    if (src == null)
      return JsonNull.INSTANCE

    return JsonPrimitive(src.toString())
  }

  override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): ZonedDateTime? {
    if (json == JsonNull.INSTANCE)
      return null

    if (json !is JsonPrimitive)
      throw IllegalStateException("Expected primitive value for ZonedDateTime deserialization")

    if (!json.isString)
      throw IllegalStateException("Expected string value for ZonedDateTime deserialization")

    return ZonedDateTime.parse(json.asString)
  }
}