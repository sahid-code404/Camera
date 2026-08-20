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
 * persistence identity. Photo/video tuning fields will be added to this schema in Phase 8 without
 * changing the stable identity contract.
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
            val resolved = previous ?: LensUserConfig(
                lensId = lens.id,
                visible = lens.userVisible,
                position = lens.userOrder,
                customName = lens.userName,
                displayZoomAnchor = lens.displayZoomAnchor,
            )
            existing[lens.id.value] = resolved
            resolved
        }.sortedBy { it.position }
        write(existing)
        return active
    }

    suspend fun save(config: LensUserConfig) {
        val existing = configs.first().toMutableMap()
        existing[config.lensId.value] = config.copy(
            customZoomLabel = ZoomLabel.normalizeCustom(config.customZoomLabel),
        )
        write(existing)
    }

    suspend fun saveLayout(configs: List<LensUserConfig>) {
        val existing = this.configs.first().toMutableMap()
        configs.forEachIndexed { index, config ->
            existing[config.lensId.value] = config.copy(
                position = index,
                customZoomLabel = ZoomLabel.normalizeCustom(config.customZoomLabel),
            )
        }
        write(existing)
    }

    suspend fun reset(lensId: LensStableId) {
        val existing = configs.first().toMutableMap()
        existing.remove(lensId.value)
        write(existing)
    }

    private suspend fun write(configs: Map<String, LensUserConfig>) {
        val encoded = encode(configs)
        appContext.lensPreferences.edit { prefs -> prefs[KEY_LAYOUT] = encoded }
    }

    private fun encode(configs: Map<String, LensUserConfig>): String {
        val array = JSONArray()
        configs.values
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
                        .put("zoomLabel", ZoomLabel.normalizeCustom(config.customZoomLabel) ?: JSONObject.NULL),
                )
            }
        return JSONObject()
            .put("schemaVersion", 1)
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
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val role = item.optString("role")
                        .takeIf { it.isNotBlank() }
                        ?.let { value -> runCatching { LensRole.valueOf(value) }.getOrNull() }
                    val name = item.optString("name").takeIf { it.isNotBlank() }
                    val label = item.optString("zoomLabel").takeIf { it.isNotBlank() }
                    val anchor = if (item.has("zoomAnchor") && !item.isNull("zoomAnchor")) {
                        item.optDouble("zoomAnchor").toFloat().takeIf { it.isFinite() && it > 0f }
                    } else null
                    put(
                        id,
                        LensUserConfig(
                            lensId = LensStableId(id),
                            visible = item.optBoolean("visible", true),
                            position = item.optInt("position", index),
                            confirmedRole = role,
                            customName = name,
                            displayZoomAnchor = anchor,
                            customZoomLabel = ZoomLabel.normalizeCustom(label),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY_LAYOUT = stringPreferencesKey("lens_layout_v1")
    }
}
