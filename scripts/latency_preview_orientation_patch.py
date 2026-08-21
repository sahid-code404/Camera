from pathlib import Path

path = Path("feature-camera/src/main/kotlin/com/camera/feature/camera/Camera2PreviewController.kt")
text = path.read_text()
old = '''        val surface = Surface(texture)
        previewSurface = surface
        val error = NativeCameraNdkBridge.startSession(lens.cameraId, surface)
'''
new = '''        val surface = previewSurface?.takeIf { it.isValid }
            ?: Surface(texture).also { previewSurface = it }
        val error = NativeCameraNdkBridge.startSession(lens.cameraId, surface)
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("native preview surface target not found")
path.write_text(text)
print("final preview surface reuse patch applied")
