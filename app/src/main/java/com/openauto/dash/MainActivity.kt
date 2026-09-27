package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Entry point for Dashwheel — the launcher/home surface. Runs edge-to-edge
 * in immersive fullscreen (status/navigation bars hidden), showing the dashboard.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * When the Home key was last pressed while this launcher was the home
         * app (a new HOME intent to the running activity). The dashboard
         * answers by closing what is open and going back to the middle page.
         */
        val homePressed = MutableStateFlow(0L)

        /** Bumped to open the full app drawer, e.g. from a learned steering wheel button. */
        val openAppsRequested = MutableStateFlow(0L)

        /**
         * True while an app window exists, docked on its tile or parked aside,
         * on a unit that cannot keep the bar off over it ([FreeformBar]):
         * Android then shows the status bar whatever the dashboard asks. Asking
         * to hide it anyway (as the dashboard did whenever it had the focus, i.e.
         * with the window parked aside) made the head unit draw its own flat white
         * bar; not asking lets the bar show the dashboard's colours through it.
         * Read by every pop-up window too (immersiveWindow).
         */
        @Volatile
        var statusBarForced = false
    }

    // Whether the launcher is sharing the screen (split-screen / freeform). The
    // dashboard collapses to a single widget in this state. configChanges keeps
    // the activity alive across the transition, so we drive it via a Compose
    // state updated from onMultiWindowModeChanged.
    private val inMultiWindow = mutableStateOf(false)

    // The language picked in the launcher, if any, over the system's.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on and turn it on while the vehicle is running.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        inMultiWindow.value = isInMultiWindowMode
        // Upright or wide, as chosen in Settings (Auto leaves the unit's own way).
        ScreenShape.load(this)
        ScreenShape.apply(this)
        enableImmersiveFullscreen()
        FeedbackStore.load(this)
        AlertStyleStore.load(this)
        // Rebuilt after a language change: the fault codes' advice follows it.
        AiMechanic.followLanguage(this)
        // Nothing the first frame needs waits behind these: they read their
        // own preferences and the package list, so they start once the
        // dashboard has drawn (posted from the first frame's pre-draw pass).
        window.decorView.doOnPreDraw {
            window.decorView.post {
                // Dials the paired phone whenever its hotspot is around, and shows its calls.
                PhoneLink.start(this)
                PhoneCallOverlay.start(this)
                // Calls on the head unit's own Bluetooth, the ROM pop-ups the driver
                // chose to replace, and the door alert that replaces one of them.
                HeadUnitPhone.start(this)
                RomPopups.start(this)
                DoorAlertOverlay.start(this)
                AlertVoice.start(this)
                // The ignition as the unit announces it, and the car's own data from the CAN box
                // with the radar / climate alerts built on it.
                CarPower.start(this)
                // The unit's own keys (wheel and panel), straight from its key service, and
                // what else it tells: headlights, the phone, CarPlay / Android Auto.
                HeadUnitKeys.start(this)
                UnitSignals.start(this)
                // The tyres from the TPMS sensors, and their warnings.
                Tyres.start(this)
                TyreAlertOverlay.start(this)
                // The seat belt reminder, and no system bars over docked app windows.
                BeltAlertOverlay.start(this)
                DockedNavBar.start(this)
                FreeformBar.start(this)
                CarBox.start(this)
                RadarOverlay.start(this)
                ClimateOverlay.start(this)
                // A new version runs JIT-only until it is compiled ahead of time.
                CompileAfterUpdate.schedule(this)
            }
        }

        setContent {
            // An app window (docked or parked aside) forces the status bar on,
            // unless the head unit can keep it off.
            val barForced = FreeformBar.forced.collectAsState().value
            val barHeldOff = FreeformBar.holding.collectAsState().value
            LaunchedEffect(barForced, barHeldOff) {
                statusBarForced = barForced
                enableImmersiveFullscreen()
            }
            // The screen's shape as it stands (the display's, not the window's:
            // split-screen halves are tall). The grid and the saved layout
            // depend on it, so the dashboard is rebuilt when it turns.
            val config = LocalConfiguration.current
            val vertical = remember(config.orientation, config.screenWidthDp, config.screenHeightDp) {
                ScreenShape.displayIsVertical(this)
            }
            ScreenShape.vertical = vertical
            OpenAutoDashTheme {
                key(vertical) {
                    AutomotiveDashboard(inSplitMode = inMultiWindow.value)
                }
            }
        }
    }

    // singleTop: the Home key re-delivers the HOME intent here instead of
    // starting a second copy, both from the launcher itself and from any app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            homePressed.value = System.currentTimeMillis()
        }
    }

    // A tap on the dashboard may open an app fullscreen on purpose: the Maps
    // tile must not take that for its own launch gone wrong (PipAnchor).
    // It also hands the unit's keys back to this screen when an app inside a
    // tile took them (EmbeddedApp).
    // A touch that lands on an app inside a tile goes on to that app straight
    // from here, the dashboard let go of it (EmbeddedApp.routeTouch).
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (EmbeddedApp.routeTouch(ev)) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            PipAnchor.noteUserTouch()
            EmbeddedApp.touchStarts()
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && EmbeddedApp.takeTouch(ev)) {
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) EmbeddedApp.touchEnds()
        return handled
    }

    // Every hardware key the head unit delivers here first — including
    // whatever a steering wheel or remote sends. SteeringWheelStore either
    // captures it for the learning screen, runs the action it's learned to,
    // or (unmapped) leaves it to Android's own handling.
    // RestrictedApi: a lint false positive, it flags ComponentActivity's own
    // override of this public Activity method.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SteeringWheelStore.onKeyEvent(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        inMultiWindow.value = isInMultiWindowMode
    }

    // Some head-unit ROMs don't reliably deliver onMultiWindowModeChanged, so
    // also re-check on resume and on the config change that entering split fires.
    override fun onResume() {
        super.onResume()
        inMultiWindow.value = isInMultiWindowMode
        FreeformBar.dashboardInFront = true
        // Home pressed over an app that never stopped the dashboard (a window): no onStart then.
        EmbeddedApp.dashboardBack()
    }

    override fun onPause() {
        FreeformBar.dashboardInFront = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        EmbeddedApp.dashboardShown(this)
    }

    override fun onStop() {
        EmbeddedApp.dashboardShown(null)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        inMultiWindow.value = isInMultiWindowMode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide the system bars whenever focus changes: they can reappear
        // after a transient swipe or returning from another app, and a pop-up
        // window taking the focus makes Android drop this window's hide flags
        // (the bars then showed as a strip until the pop-up closed).
        enableImmersiveFullscreen()
        // Focus changes accompany entering/leaving split on some ROMs.
        inMultiWindow.value = isInMultiWindowMode
        if (hasFocus) EmbeddedApp.dashboardBack()
    }

    internal fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // With a docked window in focus the hide request below is not read; the
        // window's own flag is. Only meanwhile: it keeps the keyboard from resizing the window.
        if (FreeformBar.holding.value) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (statusBarForced) {
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * Follows the dashboard's dark or light version (the Auto / Dark / Light
 * switch; Auto tracks the car's light sensor): a high-contrast dark scheme at
 * night for glare-free reading, a bright scheme by day so the UI stays legible
 * in sunlight.
 */
@Composable
fun OpenAutoDashTheme(content: @Composable () -> Unit) {
    // Material's own scheme follows the dashboard palette, so a stock
    // component (a progress ring, a switch, a menu) never falls back to
    // Material's purple in the middle of a theme.
    val surface = DashColors.Card.copy(alpha = 1f)
    val colorScheme = if (!DashColors.Light) {
        darkColorScheme(
            primary = DashColors.Accent,
            onPrimary = DashColors.OnAccent,
            secondary = DashColors.Secondary,
            onSecondary = DashColors.OnAccent,
            tertiary = DashColors.Accent2,
            background = DashColors.Background,
            onBackground = DashColors.TextPrimary,
            surface = surface,
            onSurface = DashColors.TextPrimary,
            surfaceVariant = DashColors.CardHi,
            onSurfaceVariant = DashColors.TextSecondary,
            outline = DashColors.Muted,
            error = DashColors.Critical
        )
    } else {
        lightColorScheme(
            primary = DashColors.Accent,
            onPrimary = DashColors.OnAccent,
            secondary = DashColors.Secondary,
            onSecondary = DashColors.OnAccent,
            tertiary = DashColors.Accent2,
            background = DashColors.Background,
            onBackground = DashColors.TextPrimary,
            surface = surface,
            onSurface = DashColors.TextPrimary,
            surfaceVariant = DashColors.CardHi,
            onSurfaceVariant = DashColors.TextSecondary,
            outline = DashColors.Muted,
            error = DashColors.Critical
        )
    }
    // The status bar is see-through (themes.xml), so the dashboard's background
    // shows behind it: its icons go dark on a day theme, white on a night one.
    val view = LocalView.current
    val light = DashColors.Light
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = light
        }
    }
    // The driving type scale (DashType.kt): nothing under 14 sp, labels at 16 sp.
    // The skin's corners for every stock dialog and menu (SkinChrome).
    MaterialTheme(colorScheme = colorScheme, typography = DashTypography, shapes = skinChrome().shapes, content = content)
}
