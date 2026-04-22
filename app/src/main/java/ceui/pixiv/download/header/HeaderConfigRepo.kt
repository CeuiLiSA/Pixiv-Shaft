package ceui.pixiv.download.header

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.tencent.mmkv.MMKV
import timber.log.Timber

/**
 * MMKV-backed persistence for the user's novel TXT header presets.
 *
 * Stored as a single JSON blob (Gson, compact) under `store` in the
 * `novel_header_config_v1` MMKV id. We intentionally do not migrate older
 * shapes — the feature is new.
 *
 * All reads go through [load] which falls back to the default preset set
 * if nothing is stored or the payload fails to parse (the corrupt blob is
 * NOT overwritten until the next [save], so the user still has a chance
 * to surface and recover).
 */
object HeaderConfigRepo {

    private const val MMKV_ID = "novel_header_config_v1"
    private const val KEY = "store"

    const val DEFAULT_PRESET_NAME = "默认"

    private val gson = Gson()

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /**
     * The system-recommended default preset — rich enough that TXT files
     * look similar to (or better than) the hardcoded block that existed
     * before this feature was introduced.
     */
    fun defaultPreset(): HeaderPreset = HeaderPreset(
        name = DEFAULT_PRESET_NAME,
        fields = HeaderField.ALL,
    )

    fun defaultStore(): HeaderConfigStore = HeaderConfigStore(
        presets = listOf(defaultPreset()),
        activeName = DEFAULT_PRESET_NAME,
    )

    fun load(): HeaderConfigStore {
        val raw = mmkv.decodeString(KEY) ?: return defaultStore()
        return try {
            val parsed = gson.fromJson(raw, HeaderConfigStore::class.java)
            sanitize(parsed)
        } catch (t: JsonSyntaxException) {
            Timber.w(t, "HeaderConfigRepo: corrupt store, falling back to default")
            defaultStore()
        } catch (t: Throwable) {
            Timber.w(t, "HeaderConfigRepo: unexpected error, falling back to default")
            defaultStore()
        }
    }

    fun save(store: HeaderConfigStore) {
        mmkv.encode(KEY, gson.toJson(sanitize(store)))
    }

    fun update(transform: (HeaderConfigStore) -> HeaderConfigStore): HeaderConfigStore {
        val next = transform(load())
        save(next)
        return next
    }

    fun reset() {
        save(defaultStore())
    }

    /** Convenience for render sites — resolves the active preset. */
    fun activePreset(): HeaderPreset = load().activePreset()

    /**
     * Defensive normalisation — guarantees:
     * - At least one preset exists.
     * - Every preset has a non-blank name.
     * - [HeaderConfigStore.activeName] points at an existing preset.
     * - Field list does not contain nulls (Gson can deserialise weird
     *   payloads from older dev builds).
     */
    private fun sanitize(store: HeaderConfigStore?): HeaderConfigStore {
        if (store == null || store.presets.isEmpty()) return defaultStore()
        val cleanedPresets = store.presets
            .mapNotNull { p ->
                val name = p?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val fields = p.fields?.filterNotNull().orEmpty()
                HeaderPreset(name, fields)
            }
            .ifEmpty { return defaultStore() }
        val active = cleanedPresets.firstOrNull { it.name == store.activeName }?.name
            ?: cleanedPresets.first().name
        return HeaderConfigStore(cleanedPresets, active)
    }
}
