package nl.icthorse.miraicastlab.input

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.icthorse.miraicastlab.ui.LabScaffold

/**
 * Input / touch-back test.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
@Composable
fun InputTestScreen(onBack: () -> Unit) {
    LabScaffold(title = "Input / touch-back test", onBack = onBack) {
        Text(
            "Not implemented yet.",
            modifier = Modifier.padding(16.dp),
        )
    }
}
