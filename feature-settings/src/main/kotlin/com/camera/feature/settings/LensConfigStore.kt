package com.camera.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.camera.core.model.LensRole
import com.camera.core.model.LensStableId
import com.camera.core.model.LensUserConfig
import com.camera.core.model.PhotoLensSettings
import com.camera.core.model.ValuableLens
import com.camera.core.model.ZoomLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.lensPreferences by preferencesDataStore(name = "camera_lenses")

/** Persistent per-lens layout + computational photo configuration keyed by stable lens identity. */
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
                previous.copy(
                    displayZoomAnchor = previous.displayZoomAnchor ?: lens.displayZoomAnchor,
                )
            }
            existing[lens.id.value] = sanitize(resolved)
            sanitize(resolved)
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
        photo = sanitizePhoto(config.photo),
    )

    private fun sanitizePhoto(photo: PhotoLensSettings): PhotoLensSettings = photo.copy(
        hdrStrength = photo.hdrStrength.clampOrNull(0f, 2f),
        highlightProtection = photo.highlightProtection.clampOrNull(0f, 2f),
        shadowRecovery = photo.shadowRecovery.clampOrNull(0f, 2f),
        denoise = photo.denoise.clampOrNull(0f, 2f),
        temporalDenoise = photo.temporalDenoise.clampOrNull(0f, 2f),
        sharpness = photo.sharpness.clampOrNull(0f, 2f),
        texture = photo.texture.clampOrNull(0f, 2f),
        saturation = photo.saturation.clampOrNull(0f, 2.5f),
        vibrance = photo.vibrance.clampOrNull(0f, 2.5f),
        warmth = photo.warmth.clampOrNull(-1f, 1f),
        tint = photo.tint.clampOrNull(-1f, 1f),
        upscaleFactor = photo.upscaleFactor.clampOrNull(1f, 4f),
    )

    private fun Float?.clampOrNull(min: Float, max: Float): Float? =
        this?.takeIf { it.isFinite() }?.coerceIn(min, max)

    private suspend fun write(configs: Map<String, LensUserConfig>) {
        appContext.lensPreferences.edit { prefs -> prefs[KEY_LAYOUT] = encode(configs) }
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
                        .put("zoomLabel", config.customZoomLabel ?: JSONObject.NULL)
                        .put("photo", encodePhoto(config.photo)),
                )
            }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("lenses", array)
            .toString()
    }

    private fun encodePhoto(photo: PhotoLensSettings): JSONObject = JSONObject().apply {
        putNullable("hdrEnabled", photo.hdrEnabled)
        putNullable("hdrStrength", photo.hdrStrength)
        putNullable("highlightProtection", photo.highlightProtection)
        putNullable("shadowRecovery", photo.shadowRecovery)
        putNullable("denoise", photo.denoise)
        putNullable("temporalDenoise", photo.temporalDenoise)
        putNullable("sharpness", photo.sharpness)
        putNullable("texture", photo.texture)
        putNullable("saturation", photo.saturation)
        putNullable("vibrance", photo.vibrance)
        putNullable("warmth", photo.warmth)
        putNullable("tint", photo.tint)
        putNullable("upscaleFactor", photo.upscaleFactor)
        putNullable("trueMultiFrameSuperResolution", photo.trueMultiFrameSuperResolution)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
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
                    val anchor = item.optNullableFloat("zoomAnchor")?.takeIf { it > 0f }
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
                                photo = decodePhoto(item.optJSONObject("photo")),
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun decodePhoto(item: JSONObject?): PhotoLensSettings {
        if (item == null) return PhotoLensSettings()
        return PhotoLensSettings(
            hdrEnabled = item.optNullableBoolean("hdrEnabled"),
            hdrStrength = item.optNullableFloat("hdrStrength"),
            highlightProtection = item.optNullableFloat("highlightProtection"),
            shadowRecovery = item.optNullableFloat("shadowRecovery"),
            denoise = item.optNullableFloat("denoise"),
            temporalDenoise = item.optNullableFloat("temporalDenoise"),
            sharpness = item.optNullableFloat("sharpness"),
            texture = item.optNullableFloat("texture"),
            saturation = item.optNullableFloat("saturation"),
            vibrance = item.optNullableFloat("vibrance"),
            warmth = item.optNullableFloat("warmth"),
            tint = item.optNullableFloat("tint"),
            upscaleFactor = item.optNullableFloat("upscaleFactor"),
            trueMultiFrameSuperResolution = item.optNullableBoolean("trueMultiFrameSuperResolution"),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).toFloat().takeIf { it.isFinite() }
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return optBoolean(key)
    }

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_CUSTOM_NAME_LENGTH = 32
        val KEY_LAYOUT = stringPreferencesKey("lens_layout_v1")
    }
}
