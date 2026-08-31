package nl.icthorse.miraicastlab.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nl.icthorse.miraicastlab.audio.AudioTestScreen
import nl.icthorse.miraicastlab.auto.AndroidAutoScreen
import nl.icthorse.miraicastlab.auto.MotionStateScreen
import nl.icthorse.miraicastlab.dex.DexControlScreen
import nl.icthorse.miraicastlab.display.DisplayTestScreen
import nl.icthorse.miraicastlab.input.InputTestScreen
import nl.icthorse.miraicastlab.net.NetworkTestScreen
import nl.icthorse.miraicastlab.projection.MediaProjectionScreen
import nl.icthorse.miraicastlab.report.LogViewerScreen
import nl.icthorse.miraicastlab.route.RouteScreen
import nl.icthorse.miraicastlab.report.ReportScreen
import nl.icthorse.miraicastlab.samsung.DexScreen
import nl.icthorse.miraicastlab.samsung.MiracastWizardScreen
import nl.icthorse.miraicastlab.scan.DeviceScanScreen
import nl.icthorse.miraicastlab.scene.TestPatternScreen

/** Every destination in the app. String routes keep the graph readable in logs. */
object Dest {
    const val DASHBOARD = "dashboard"
    const val DEVICE_SCAN = "device_scan"
    const val MIRACAST = "miracast"
    const val DISPLAY = "display"
    const val NETWORK = "network"
    const val AUDIO = "audio"
    const val INPUT = "input"
    const val PROJECTION = "projection"
    const val ANDROID_AUTO = "android_auto"
    const val MOTION = "motion"
    const val DEX = "dex"
    const val SCENE = "scene"
    const val REPORT = "report"
    const val LOG = "log"
    const val ROUTES = "routes"
    const val DEX_CONTROL = "dex_control"
}

@Composable
fun LabNavHost(nav: NavHostController = rememberNavController()) {
    val back: () -> Unit = { if (!nav.popBackStack()) Unit }

    NavHost(navController = nav, startDestination = Dest.DASHBOARD) {
        composable(Dest.DASHBOARD) { DashboardScreen(onNavigate = { nav.navigate(it) }) }
        composable(Dest.DEVICE_SCAN) { DeviceScanScreen(onBack = back) }
        composable(Dest.MIRACAST) { MiracastWizardScreen(onBack = back) }
        composable(Dest.DISPLAY) { DisplayTestScreen(onBack = back) }
        composable(Dest.NETWORK) { NetworkTestScreen(onBack = back) }
        composable(Dest.AUDIO) { AudioTestScreen(onBack = back) }
        composable(Dest.INPUT) { InputTestScreen(onBack = back) }
        composable(Dest.PROJECTION) { MediaProjectionScreen(onBack = back) }
        composable(Dest.ANDROID_AUTO) { AndroidAutoScreen(onBack = back) }
        composable(Dest.MOTION) { MotionStateScreen(onBack = back) }
        composable(Dest.DEX) { DexScreen(onBack = back) }
        composable(Dest.SCENE) { TestPatternScreen(onBack = back) }
        composable(Dest.REPORT) { ReportScreen(onBack = back) }
        composable(Dest.LOG) { LogViewerScreen(onBack = back) }
        composable(Dest.ROUTES) { RouteScreen(onBack = back) }
        composable(Dest.DEX_CONTROL) { DexControlScreen(onBack = back) }
    }
}
