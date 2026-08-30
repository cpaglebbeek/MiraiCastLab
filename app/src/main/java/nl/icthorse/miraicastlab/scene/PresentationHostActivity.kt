package nl.icthorse.miraicastlab.scene

import android.os.Build
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
            mapOf("displayId" to currentDisplayId()),
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

    /**
     * The id of the display this activity actually landed on.
     *
     * Activity.getDisplay() only exists from API 30; on API 29 the deprecated
     * WindowManager.getDefaultDisplay() is the supported route. Which display we ended up on is one
     * of the findings this project cares about, so it must be readable on every supported version
     * rather than silently absent on the oldest one.
     */
    private fun currentDisplayId(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId?.toString() ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId?.toString() ?: "unknown"
        }
    } catch (t: Throwable) {
        t::class.java.simpleName
    }
}
