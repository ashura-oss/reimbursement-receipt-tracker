package com.receiptclaims.android.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageZoomDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun fullscreenZoomControlsStayVisibleAndChangeScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageFile = File(context.cacheDir, "zoom-test-receipt.png")
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(245, 230, 180))
        FileOutputStream(imageFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        composeRule.setContent {
            ReceiptImageViewerDialog(imageFile, onDismiss = {})
        }

        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val screenshotFile = File(context.cacheDir, "zoom-controls.png")
        FileOutputStream(screenshotFile).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        assert(screenshotFile.length() > 0L)
        composeRule.onNodeWithText("Close").assertIsDisplayed()
        composeRule.onNodeWithText("Zoom out").assertIsDisplayed()
        composeRule.onNodeWithText("100%").assertIsDisplayed()
        composeRule.onNodeWithText("Zoom in").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("125%").assertIsDisplayed()
        composeRule.onNodeWithText("Reset").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("100%").assertIsDisplayed()
        composeRule.onNodeWithText("Zoom in").performClick()
        composeRule.onNodeWithText("125%").assertIsDisplayed()
        composeRule.onNodeWithText("Zoom out").performClick()
        composeRule.onNodeWithText("100%").assertIsDisplayed()
    }
}
