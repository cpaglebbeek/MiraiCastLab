package nl.icthorse.miraicastlab.scene

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.LabTheme

/**
 * Hosts the diagnostic scene, optionally on a secondary display.
 *
 * Launched with an ActivityOptions launch-display-id when an external display is present, which is
 * how an ordinary app puts content on a Miracast/DeX screen without any privileged API.
 *
 * The implementation agent for the scene module owns the body of this activity.
 */
class PresentationHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionLogger.log(
            LabCategory.DISPLAY,
            "presentation_host_created",
            LabStatus.OBSERVED,
            mapOf("displayId" to display?.displayId.toString()),
        )
        setContent {
            LabTheme { TestPatternScreen(onBack = { finish() }) }
        }
    }

    override fun onDestroy() {
        SessionLogger.log(
            LabCategory.DISPLAY,
            "presentation_host_destroyed",
            LabStatus.OBSERVED,
        )
        super.onDestroy()
    }
}
