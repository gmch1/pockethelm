package com.gmch.codexremote

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real system picker, shared production launcher, Composer chip and remove action. */
@RunWith(AndroidJUnit4::class)
class ImagePickerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            found.add(n); for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }
    private fun waitFor(predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            if (predicate()) return
            Thread.sleep(100)
        }
        val tree = nodes().joinToString("\n") { "${it.viewIdResourceName} text=${it.text} desc=${it.contentDescription}" }
        fail("UI timeout\n$tree")
    }
    private fun click(n: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = n
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    @Test fun selectSyntheticPhotoAndRemoveChip() {
        val resolver = instrumentation.targetContext.contentResolver
        val name = "PocketHelm-Test-${System.currentTimeMillis()}.jpg"
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketHelm-Test")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        try {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            resolver.openOutputStream(uri)!!.use { assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            bmp.recycle()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val selected = mutableStateOf(false)
            val error = mutableStateOf("")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    PocketHelmTheme {
                        var attachments by remember { mutableStateOf(emptyList<ChatAttachment>()) }
                        val picker = rememberAttachmentPicker({ "test-scope" }) { picked, display, mime, token ->
                            // Never decode a normal user photo if the picker selected a different row.
                            if (picked.lastPathSegment?.substringBefore('.') != uri.lastPathSegment || token != "test-scope")
                                error.value = "Unexpected selection identity"
                            else {
                                val result = ImageProcessor(activity).process(picked, display, mime)
                                result.onSuccess { attachments = listOf(it); selected.value = true }
                                    .onFailure { error.value = it.toString() }
                            }
                        }
                        Column {
                            Text("Picker test")
                            ChatComposer("", {}, false, false, false, {}, {}, attachments = attachments,
                                canAttach = true, attach = picker,
                                removeAttachment = { id -> attachments = attachments.filterNot { it.id == id } })
                        }
                    }
                } }
                waitFor { nodes().any { it.contentDescription == "添加图片" } }
                click(nodes().first { it.contentDescription == "添加图片" })
                // The fixture was just inserted; prefer exact filename (DocumentsUI), otherwise
                // the first photo in the system's descending-time Photo Picker grid.
                waitFor { nodes().any { it.text?.toString() == name || it.viewIdResourceName?.endsWith("/icon_thumbnail") == true || it.viewIdResourceName?.endsWith("/thumbnail") == true } }
                val item = nodes().firstOrNull { it.text?.toString() == name }
                    ?: nodes().first { it.viewIdResourceName?.endsWith("/icon_thumbnail") == true || it.viewIdResourceName?.endsWith("/thumbnail") == true }
                click(item)
                waitFor { selected.value || error.value.isNotEmpty() }
                assertEquals("", error.value)
                waitFor { nodes().any { it.contentDescription == "移除图片" } }
                click(nodes().first { it.contentDescription == "移除图片" })
                waitFor { nodes().none { it.contentDescription == "移除图片" } }
                assertTrue(selected.value)
            }
        } finally { resolver.delete(uri, null, null) }
    }
}
