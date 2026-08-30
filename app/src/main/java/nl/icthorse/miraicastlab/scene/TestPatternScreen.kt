package nl.icthorse.miraicastlab.scene

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.icthorse.miraicastlab.ui.LabScaffold

/**
 * Diagnostic test pattern.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
@Composable
fun TestPatternScreen(onBack: () -> Unit) {
    LabScaffold(title = "Diagnostic test pattern", onBack = onBack) {
        Text(
            "Not implemented yet.",
            modifier = Modifier.padding(16.dp),
        )
    }
}
