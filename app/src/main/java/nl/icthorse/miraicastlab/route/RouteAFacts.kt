package nl.icthorse.miraicastlab.route

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.media.MediaRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Low-level, side-effect-free readers used by the route A and route B experiments.
 *
 * Named for route A because that is where these readings carry the verdict: route A asks whether an
 * ordinary app may *initiate* a Wi-Fi Display session, and the answer is decided by two things this
 * file measures - which methods the framework exposes to us, and which permission gate stands in
 * front of them. [RouteB] reuses the MediaRouter and permission readers from here rather than
 * duplicating them; both live in the same `route` module, so this is not a cross-module import
 * (docs/PRINCIPLES.md P10).
 *
 * Discipline in this file:
 * - nothing here starts, stops, connects, configures or negotiates anything;
 * - reflection is used to *look up* members, never to invoke one;
 * - every failure is captured as a string and returned, never thrown and never swallowed;
 * - a lookup that fails is reported as a failed lookup, not as an absent capability. On API 28+ the
 *   reflection APIs filter blocked non-SDK members, so "not found" and "hidden from us" are the
 *   same observation, and callers must grade it accordingly (INFERRED at most).
 */
object RouteAFacts {

    // ------------------------------------------------------------------ permissions

    /**
     * What the platform says about one permission, and whether this app holds it.
     *
     * Both halves matter. [declaredOnDevice] plus [protection] is the platform answering "this gate
     * exists and this is how high it is"; [granted] is the platform answering "you are not through
     * it". Neither is reflection, so a caller may grade this CONFIRMED.
     */
    data class PermissionGate(
        val name: String,
        val declaredOnDevice: Boolean,
        val protection: String,
        val granted: Boolean,
        val failure: String?,
    ) {
        val shortName: String get() = name.substringAfterLast('.')

        fun line(): String = shortName + " = " +
            (if (declaredOnDevice) "declared[" + protection + "]" else "not declared on this build") +
            ", " + (if (granted) "HELD by this app" else "not held by this app") +
            (failure?.let { " [" + it + "]" } ?: "")
    }

    /** Reads one permission gate. Never throws. */
    fun readPermissionGate(context: Context, name: String): PermissionGate {
        var declared = false
        var protection = "unknown"
        var failure: String? = null

        try {
            val info = context.packageManager.getPermissionInfo(name, 0)
            declared = true
            protection = describeProtection(info)
        } catch (e: PackageManager.NameNotFoundException) {
            // Not an error: the platform answered that no such permission is defined on this build.
            failure = "NameNotFoundException: not defined on this build"
        } catch (t: Throwable) {
            failure = t::class.java.simpleName + ": " + (t.message ?: "no message")
        }

        var granted = false
        try {
            granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            failure = (failure?.plus("; ") ?: "") + "checkSelfPermission " + t::class.java.simpleName
        }

        return PermissionGate(name, declared, protection, granted, failure)
    }

    /** Base protection plus the flags that change who can ever be granted it. */
    @Suppress("DEPRECATION")
    private fun describeProtection(info: PermissionInfo): String {
        val base = when (info.protection) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> "signatureOrSystem"
            PermissionInfo.PROTECTION_INTERNAL -> "internal"
            else -> "protection(" + info.protection + ")"
        }
        val f = info.protectionFlags
        val flags = buildList {
            if (f and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) add("privileged")
            if (f and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) add("development")
            if (f and PermissionInfo.PROTECTION_FLAG_APPOP != 0) add("appop")
            if (f and PermissionInfo.PROTECTION_FLAG_PRE23 != 0) add("pre23")
            if (f and PermissionInfo.PROTECTION_FLAG_INSTALLER != 0) add("installer")
        }
        return if (flags.isEmpty()) base else base + "|" + flags.joinToString("|")
    }

    // ------------------------------------------------------------------ class surface

    /** The public method names a class exposes to *this* app, or why we could not ask. */
    data class ClassSurface(
        val className: String,
        val loaded: Boolean,
        val methodNames: List<String>,
        val failure: String?,
    ) {
        /** Method names containing any of [keywords], case-insensitive. */
        fun matching(keywords: List<String>): List<String> = methodNames
            .filter { n -> keywords.any { n.contains(it, ignoreCase = true) } }
            .distinct()
            .sorted()
    }

    /**
     * Enumerates the public methods a class exposes, filtered to those the class itself declares
     * or inherits from its own hierarchy (java.lang.Object members are dropped as noise).
     *
     * On API 28+ this list is already filtered by the hidden-API policy: blocked non-SDK members are
     * removed before we see them. So an empty match means "not reachable by us", which is weaker
     * than "not present in the framework". Callers must say so.
     */
    fun readClassSurface(className: String): ClassSurface = try {
        val c = Class.forName(className)
        val names = c.methods
            .filter { it.declaringClass != Any::class.java }
            .map { it.name }
            .distinct()
            .sorted()
        ClassSurface(className, true, names, null)
    } catch (t: Throwable) {
        ClassSurface(className, false, emptyList(), t::class.java.simpleName + ": " + (t.message ?: "no message"))
    }

    /** One named method: every signature of it visible to us, or why the lookup failed. */
    data class MethodFact(
        val className: String,
        val methodName: String,
        val signatures: List<String>,
        val failure: String?,
    ) {
        val found: Boolean get() = signatures.isNotEmpty()

        fun line(): String = className.substringAfterLast('.') + "." + methodName + " -> " + when {
            failure != null -> "lookup failed [" + failure + "]"
            signatures.isEmpty() -> "not visible to this app"
            else -> signatures.joinToString(" ; ")
        }
    }

    /**
     * Looks a method up by name and reports its signatures. **Never invokes it.**
     *
     * The signature itself is evidence: `removeUserRoute(UserRouteInfo)` proves by its parameter
     * type that an app cannot remove a route it did not create, without calling anything.
     */
    fun readMethod(className: String, methodName: String): MethodFact = try {
        val c = Class.forName(className)
        val sigs = c.methods
            .filter { it.name == methodName }
            .map { m ->
                m.name + "(" + m.parameterTypes.joinToString(", ") { it.simpleName } + "): " +
                    m.returnType.simpleName
            }
            .sorted()
        MethodFact(className, methodName, sigs, null)
    } catch (t: Throwable) {
        MethodFact(className, methodName, emptyList(), t::class.java.simpleName + ": " + (t.message ?: "no message"))
    }

    // ------------------------------------------------------------------ intents

    /** Which activities claim an implicit action, or why the query failed. */
    data class IntentReach(val action: String, val matches: List<String>, val failure: String?) {
        val resolvable: Boolean get() = matches.isNotEmpty()

        fun line(): String = action + " -> " + when {
            failure != null -> "query failed [" + failure + "]"
            matches.isEmpty() -> "no activity resolves it"
            else -> matches.joinToString(", ")
        }
    }

    @Suppress("DEPRECATION")
    fun resolveAction(context: Context, action: String): IntentReach = try {
        val list = context.packageManager.queryIntentActivities(Intent(action), 0)
        IntentReach(action, list.mapNotNull { it.activityInfo?.let { a -> a.packageName + "/" + a.name } }, null)
    } catch (t: Throwable) {
        IntentReach(action, emptyList(), t::class.java.simpleName + ": " + (t.message ?: "no message"))
    }

    // ------------------------------------------------------------------ MediaRouter

    val TYPE_LIVE_AUDIO: Int = MediaRouter.ROUTE_TYPE_LIVE_AUDIO
    val TYPE_LIVE_VIDEO: Int = MediaRouter.ROUTE_TYPE_LIVE_VIDEO
    val TYPE_USER: Int = MediaRouter.ROUTE_TYPE_USER

    /**
     * The remote-display bit is not public on every platform version, so the value is written out.
     * It is used only to *decode* a bitmask the platform handed us; nothing is called with it.
     */
    private val TYPE_REMOTE_DISPLAY: Int = 1 shl 2

    fun typeNames(types: Int): String {
        val out = mutableListOf<String>()
        if (types and TYPE_LIVE_AUDIO != 0) out += "LIVE_AUDIO"
        if (types and TYPE_LIVE_VIDEO != 0) out += "LIVE_VIDEO"
        if (types and TYPE_REMOTE_DISPLAY != 0) out += "REMOTE_DISPLAY"
        if (types and TYPE_USER != 0) out += "USER"
        return if (out.isEmpty()) "none(0x" + Integer.toHexString(types) + ")" else out.joinToString("+")
    }

    /** One route exactly as an unprivileged app may read it. */
    data class RouteFact(
        val index: Int,
        val name: String,
        val types: Int,
        val statusText: String?,
        val presentationDisplayId: Int?,
        val isDefault: Boolean,
        val isSelectedLiveVideo: Boolean,
    ) {
        fun line(): String = "#" + index + " \"" + name + "\" " + typeNames(types) +
            (presentationDisplayId?.let { " -> presentation display " + it } ?: " -> no presentation display") +
            (if (isDefault) " [DEFAULT]" else "") +
            (if (isSelectedLiveVideo) " [SELECTED live-video]" else "") +
            (statusText?.let { " status=\"" + it + "\"" } ?: "")
    }

    /** Everything one pass over MediaRouter saw. */
    data class RouterRead(
        val available: Boolean,
        val routes: List<RouteFact>,
        val selectedLiveVideo: RouteFact?,
        val failure: String?,
    ) {
        val liveVideoRoutes: List<RouteFact> get() = routes.filter { it.types and TYPE_LIVE_VIDEO != 0 }
        val routesWithPresentationDisplay: List<RouteFact> get() = routes.filter { it.presentationDisplayId != null }

        fun summary(): String = when {
            !available -> "MediaRouter unavailable" + (failure?.let { " [" + it + "]" } ?: "")
            routes.isEmpty() -> "MediaRouter present, 0 routes"
            else -> routes.size.toString() + " routes; " + liveVideoRoutes.size + " live-video; " +
                routesWithPresentationDisplay.size + " with a presentation display; selected live-video = " +
                (selectedLiveVideo?.name ?: "none")
        }
    }

    /** MediaRouter is main-thread affine, so every read is marshalled onto the main dispatcher. */
    suspend fun readRouter(context: Context): RouterRead = withContext(Dispatchers.Main) {
        val router = try {
            context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
        } catch (t: Throwable) {
            return@withContext RouterRead(
                false, emptyList(), null,
                t::class.java.simpleName + ": " + (t.message ?: "no message"),
            )
        }
        if (router == null) {
            // The platform answered: there is no MediaRouter here. That is a real negative.
            return@withContext RouterRead(false, emptyList(), null, "MEDIA_ROUTER_SERVICE returned null")
        }

        val defaultRoute = runCatching { router.defaultRoute }.getOrNull()
        val selected = runCatching { router.getSelectedRoute(TYPE_LIVE_VIDEO) }.getOrNull()
        val count = runCatching { router.routeCount }.getOrDefault(0)

        val facts = mutableListOf<RouteFact>()
        for (i in 0 until count) {
            val r = runCatching { router.getRouteAt(i) }.getOrNull() ?: continue
            facts += RouteFact(
                index = i,
                name = runCatching { (r.getName(context) ?: r.name)?.toString() }.getOrNull() ?: "unnamed",
                types = runCatching { r.supportedTypes }.getOrDefault(0),
                statusText = runCatching { r.status?.toString() }.getOrNull(),
                presentationDisplayId = runCatching { r.presentationDisplay?.displayId }.getOrNull(),
                // RouteInfo does not override equals(), so identity is the correct comparison here.
                isDefault = defaultRoute != null && r === defaultRoute,
                isSelectedLiveVideo = selected != null && r === selected,
            )
        }
        RouterRead(
            available = true,
            routes = facts,
            selectedLiveVideo = facts.firstOrNull { it.isSelectedLiveVideo },
            failure = null,
        )
    }

    /**
     * Result of testing whether `MediaRouter.selectRoute` is reachable at all.
     *
     * Deliberately narrow: the *currently selected* live-video route is re-selected, which the
     * framework treats as a no-op. That tests the entry point without mutating a session the tester
     * may be in the middle of. Selecting a *different* system-created route is not attempted here -
     * it would change what the tester is looking at mid-experiment - so "an app can switch routes"
     * stays NOT_TESTED and must be reported as such.
     */
    data class SelectRouteProbe(
        val attempted: Boolean,
        val skipReason: String?,
        val threw: String?,
        val selectedBefore: String?,
        val selectedAfter: String?,
    ) {
        val changedSelection: Boolean get() = attempted && selectedBefore != selectedAfter

        fun line(): String = when {
            !attempted -> "not attempted: " + (skipReason ?: "unknown reason")
            threw != null -> "selectRoute(LIVE_VIDEO, <already-selected route>) threw " + threw
            changedSelection -> "selectRoute re-selection CHANGED the selection: \"" +
                selectedBefore + "\" -> \"" + selectedAfter + "\""
            else -> "selectRoute(LIVE_VIDEO, <already-selected route>) returned without throwing; " +
                "selection unchanged (\"" + selectedAfter + "\")"
        }
    }

    suspend fun probeSelectRouteReachability(context: Context): SelectRouteProbe =
        withContext(Dispatchers.Main) {
            val router = runCatching {
                context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
            }.getOrNull()
                ?: return@withContext SelectRouteProbe(
                    false, "MediaRouter unavailable", null, null, null,
                )

            val before = runCatching { router.getSelectedRoute(TYPE_LIVE_VIDEO) }.getOrNull()
                ?: return@withContext SelectRouteProbe(
                    false, "no live-video route is currently selected, so there is nothing safe to re-select",
                    null, null, null,
                )
            val beforeName = runCatching { before.name?.toString() }.getOrNull() ?: "unnamed"

            var threw: String? = null
            try {
                // Idempotent by construction: this is the route the framework already has selected.
                router.selectRoute(TYPE_LIVE_VIDEO, before)
            } catch (t: Throwable) {
                threw = t::class.java.simpleName + ": " + (t.message ?: "no message")
            }

            val afterName = runCatching { router.getSelectedRoute(TYPE_LIVE_VIDEO)?.name?.toString() }
                .getOrNull() ?: "none"
            SelectRouteProbe(true, null, threw, beforeName, afterName)
        }
}
