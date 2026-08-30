package nl.icthorse.miraicastlab.net

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.icthorse.miraicastlab.ui.LabScaffold

/**
 * Network & Wi-Fi Direct.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
@Composable
fun NetworkTestScreen(onBack: () -> Unit) {
    LabScaffold(title = "Network & Wi-Fi Direct", onBack = onBack) {
        Text(
            "Not implemented yet.",
            modifier = Modifier.padding(16.dp),
        )
    }
}
