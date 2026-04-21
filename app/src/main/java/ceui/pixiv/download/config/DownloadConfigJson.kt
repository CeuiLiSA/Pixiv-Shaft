package ceui.pixiv.download.config

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Memento: serialise a [DownloadConfig] to / from a human-readable JSON
 * string. Used by [DownloadConfigStore] and by any import/export UI.
 *
 * The [StorageChoice] hierarchy is tagged with a `kind` discriminator so the
 * sealed `when` stays exhaustive on the deserialise side too.
 */
object DownloadConfigJson {

    private val compact: Gson = GsonBuilder()
        .registerTypeAdapter(StorageChoice::class.java, StorageChoiceAdapter)
        .create()

    private val pretty: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(StorageChoice::class.java, StorageChoiceAdapter)
        .create()

    /** Compact form — used by [DownloadConfigStore] to save space in MMKV. */
    fun toJson(config: DownloadConfig): String = compact.toJson(config)

    /** Human-readable form — used only for explicit import/export UI. */
    fun toPrettyJson(config: DownloadConfig): String = pretty.toJson(config)

    fun fromJson(json: String): DownloadConfig = compact.fromJson(json, DownloadConfig::class.java)

    private object StorageChoiceAdapter : JsonSerializer<StorageChoice>, JsonDeserializer<StorageChoice> {

        override fun serialize(
            src: StorageChoice,
            typeOfSrc: Type,
            context: JsonSerializationContext,
        ): JsonElement {
            val obj = JsonObject()
            when (src) {
                is StorageChoice.MediaStore -> {
                    obj.addProperty("kind", "media_store")
                    obj.addProperty("collection", src.collection.name)
                }
                is StorageChoice.Saf -> {
                    obj.addProperty("kind", "saf")
                    obj.addProperty("tree_uri", src.treeUri.toString())
                }
                StorageChoice.AppCache -> {
                    obj.addProperty("kind", "app_cache")
                }
            }
            return obj
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext,
        ): StorageChoice {
            val obj = json.asJsonObject
            return when (val kind = obj.get("kind").asString) {
                "media_store" -> StorageChoice.MediaStore(
                    collection = StorageChoice.MediaStore.Collection.valueOf(obj.get("collection").asString),
                )
                "saf" -> StorageChoice.Saf(Uri.parse(obj.get("tree_uri").asString))
                "app_cache" -> StorageChoice.AppCache
                else -> error("Unknown StorageChoice kind '$kind'")
            }
        }
    }

}
