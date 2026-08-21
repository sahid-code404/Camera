from pathlib import Path

path = Path("feature-camera/src/main/kotlin/com/camera/feature/camera/Camera2PreviewController.kt")
text = path.read_text()

old_surface = '''        val surface = Surface(texture)
        previewSurface = surface
        val error = NativeCameraNdkBridge.startSession(lens.cameraId, surface)
'''
new_surface = '''        val surface = previewSurface?.takeIf { it.isValid }
            ?: Surface(texture).also { previewSurface = it }
        val error = NativeCameraNdkBridge.startSession(lens.cameraId, surface)
'''
if old_surface in text:
    text = text.replace(old_surface, new_surface, 1)
elif new_surface not in text:
    raise SystemExit("native preview surface target not found")

old_use_cases = '''                val useCases = activeCharacteristics
                    ?.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                    .orEmpty()
'''
new_use_cases = '''                val useCases = activeCharacteristics
                    ?.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                    ?: longArrayOf()
'''
if old_use_cases in text:
    text = text.replace(old_use_cases, new_use_cases, 1)
elif new_use_cases not in text:
    raise SystemExit("preview stream use-case target not found")

old_key = '''    private fun lensKey(lens: ValuableLens, targetAspect: Float?): String =
        "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}:a${aspectKey(targetAspect)}"
'''
new_key = '''    private fun lensKey(lens: ValuableLens, targetAspect: Float?): String {
        val transport = if (lens.nativeRoutePreferred && lens.physicalCameraId == null) "native" else "java"
        return "${lens.cameraId}:${lens.physicalCameraId ?: "direct"}:$transport:a${aspectKey(targetAspect)}"
    }
'''
if old_key in text:
    text = text.replace(old_key, new_key, 1)
elif new_key not in text:
    raise SystemExit("lens transport key target not found")

path.write_text(text)
print("preview surface reuse + compile fix + transport-aware key applied")
