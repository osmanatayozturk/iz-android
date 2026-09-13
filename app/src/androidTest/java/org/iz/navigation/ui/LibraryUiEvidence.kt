package org.iz.navigation.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/** Synthetic test screens, retained only in this test target's private cache for visual QA. */
internal object LibraryUiEvidence {
    private val directory by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "library-ui-evidence/${UUID.randomUUID()}").apply { mkdirs() }
    }
    fun capture(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
