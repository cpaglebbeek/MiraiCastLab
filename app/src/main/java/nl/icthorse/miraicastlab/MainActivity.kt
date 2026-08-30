package nl.icthorse.miraicastlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.LabNavHost
import nl.icthorse.miraicastlab.ui.LabTheme

/**
 * Single-activity host for MiraiCast Lab.
 *
 * Everything the tester does happens here or in [nl.icthorse.miraicastlab.scene.PresentationHostActivity],
 * which is the only other activity and exists solely to place the diagnostic scene on a secondary
 * display.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionLogger.init(applicationContext)
        SessionLogger.log(
            LabCategory.USER,
            "app_started",
            LabStatus.CONFIRMED,
            mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE.toString(),
            ),
        )
        enableEdgeToEdge()
        setContent {
            LabTheme { LabNavHost() }
        }
    }
}
