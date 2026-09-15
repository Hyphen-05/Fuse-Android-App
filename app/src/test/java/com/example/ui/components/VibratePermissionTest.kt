package com.example.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VibratePermissionTest {

    @Test
    fun `code that drives the vibrator directly has the permission to`() {
        // 2026-09-15: PowerButton called Vibrator.vibrate with no VIBRATE in the manifest, and
        // touching the power button crashed on Joe's Pixel. It passed on the moto only because the
        // moto lacks composition primitives and takes a fallback that needs no permission - so no
        // device test on the spare phone could have caught it. This one can.
        val usesVibrator = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { it.readText().contains(".vibrate(") }
        if (!usesVibrator) return
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "source calls Vibrator.vibrate but the manifest does not declare android.permission.VIBRATE",
            manifest.contains("android.permission.VIBRATE")
        )
    }
}
