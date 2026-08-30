package nl.icthorse.miraicastlab.report

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.icthorse.miraicastlab.ui.LabScaffold

/**
 * Live log.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    LabScaffold(title = "Live log", onBack = onBack) {
        Text(
            "Not implemented yet.",
            modifier = Modifier.padding(16.dp),
        )
    }
}
