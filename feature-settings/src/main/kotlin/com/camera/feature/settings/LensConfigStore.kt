package com.camera.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.camera.core.model.LensRole
import com.camera.core.model.LensStableId
import com.camera.core.model.LensUserConfig
import com.camera.core.model.ValuableLens
import com.camera.core.model.ZoomLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.lensPreferences by preferencesDataStore(name = "camera_lenses")

/**
 * Persistent user overrides for lens visibility, ordering and naming.
 *
 * The stable lens ID is the key. Camera2 list order and numeric camera IDs are never used as the
 * persistence identity. Photo/video tuning fields will be added to this schema later without
 * changing that identity contract.
 */
class LensConfigStore(context: Context) {
    private val appContext = context.applicationContext

    val configs: Flow<Map<String, LensUserConfig>> = appContext.lensPreferences.data.map { prefs ->
        decode(prefs[KEY_LAYOUT])
    }

    suspend fun reconcile(discovered: List<ValuableLens>): List<LensUserConfig> {
        val existing = configs.first().toMutableMap()
        val active = discovered.map { lens ->
            val previous = existing[lens.id.value]
            val resolved = if (previous == null) {
                LensUserConfig(
                    lensId = lens.id,
                    visible = lens.userVisible,
                    position = lens.userOrder,
                    customName = lens.userName,
                    displayZoomAnchor = lens.displayZoomAnchor,
                )
            } else {
                // Keep user overrides, but backfill an optical anchor if an older stored profile did
                // not have one. The custom visible label remains presentation-only.
                previous.copy(
                    displayZoomAnchor = previous.displayZoomAnchor ?: lens.displayZoomAnchor,
                )
            }
            existing[lens.id.value] = resolved
            resolved
        }.sortedWith(compareBy<LensUserConfig> { it.position }.thenBy { it.lensId.value })
        write(existing)
        return active
    }

    suspend fun save(config: LensUserConfig) {
        val existing = configs.first().toMutableMap()
        existing[config.lensId.value] = sanitize(config)
        write(existing)
    }

    suspend fun saveLayout(configs: List<LensUserConfig>) {
        val existing = this.configs.first().toMutableMap()
        configs.forEachIndexed { index, config ->
            existing[config.lensId.value] = sanitize(config.copy(position = index))
        }
        write(existing)
    }

    suspend fun reset(lensId: LensStableId) {
        val existing = configs.first().toMutableMap()
        existing.remove(lensId.value)
        write(existing)
    }

    private fun sanitize(config: LensUserConfig): LensUserConfig = config.copy(
        customName = config.customName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_CUSTOM_NAME_LENGTH),
        customZoomLabel = ZoomLabel.normalizeCustom(config.customZoomLabel),
        displayZoomAnchor = config.displayZoomAnchor?.takeIf { it.isFinite() && it > 0f },
        position = config.position.coerceAtLeast(0),
    )

    private suspend fun write(configs: Map<String, LensUserConfig>) {
        val encoded = encode(configs)
        appContext.lensPreferences.edit { prefs -> prefs[KEY_LAYOUT] = encoded }
    }

    private fun encode(configs: Map<String, LensUserConfig>): String {
        val array = JSONArray()
        configs.values
            .map(::sanitize)
            .sortedWith(compareBy<LensUserConfig> { it.position }.thenBy { it.lensId.value })
            .forEach { config ->
                array.put(
                    JSONObject()
                        .put("id", config.lensId.value)
                        .put("visible", config.visible)
                        .put("position", config.position)
                        .put("role", config.confirmedRole?.name ?: JSONObject.NULL)
                        .put("name", config.customName ?: JSONObject.NULL)
                        .put("zoomAnchor", config.displayZoomAnchor ?: JSONObject.NULL)
                        .put("zoomLabel", config.customZoomLabel ?: JSONObject.NULL),
                )
            }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("lenses", array)
            .toString()
    }

    private fun decode(raw: String?): Map<String, LensUserConfig> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("lenses") ?: JSONArray()
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optNullableString("id") ?: continue
                    val role = item.optNullableString("role")
                        ?.let { value -> runCatching { LensRole.valueOf(value) }.getOrNull() }
                    val anchor = if (item.has("zoomAnchor") && !item.isNull("zoomAnchor")) {
                        item.optDouble("zoomAnchor")
                            .toFloat()
                            .takeIf { it.isFinite() && it > 0f }
                    } else null
                    put(
                        id,
                        sanitize(
                            LensUserConfig(
                                lensId = LensStableId(id),
                                visible = item.optBoolean("visible", true),
                                position = item.optInt("position", index),
                                confirmedRole = role,
                                customName = item.optNullableString("name"),
                                displayZoomAnchor = anchor,
                                customZoomLabel = item.optNullableString("zoomLabel"),
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CUSTOM_NAME_LENGTH = 32
        val KEY_LAYOUT = stringPreferencesKey("lens_layout_v1")
    }
}
