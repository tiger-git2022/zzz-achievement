package com.local.stardbzzz.ui.glass

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Liquid glass, the same look and numbers as the EhViewer and Mihon forks (Kyant's backdrop library).
 *
 * A glass part refracts content recorded with [glassContent]. A part can never refract content it is drawn in (it
 * would draw itself), so there are three recordings:
 * - a page records its list; the top bar and the floating filter panel refract it;
 * - the main screen records the page with its top bar; the category drawer refracts it;
 * - [GlassHost] records the whole app; dialogs and menus refract it. They are drawn in the app window above that
 *   recording (see [GlassOverlay]), not in dialog or popup windows, which cannot see the app below them.
 *
 * The bending needs Android 13, the blur Android 12. On older systems the glass is only tinted, so it is tinted more.
 */

/**
 * The 设置 → 液态玻璃 switch (on by default) and the glass of the drawer, the dialogs and the menus, set like in
 * EhViewer (Settings → EH there, same ranges and defaults): the blur in tenths of a dp, the tint in percent and the
 * color fringes at the edge from 0 (none) to 100.
 */
object GlassSettings {
    private const val PREFS = "ui"
    private const val KEY_ENABLED = "liquid_glass"
    private const val KEY_SHEET_BLUR = "sheet_glass_blur"
    private const val KEY_SHEET_TINT = "sheet_glass_tint"
    private const val KEY_SHEET_FRINGE = "sheet_glass_fringe"
    const val DEFAULT_SHEET_BLUR = 25
    const val DEFAULT_SHEET_TINT = 40
    const val DEFAULT_SHEET_FRINGE = 50

    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = state
    private val sheetBlurState = MutableStateFlow(DEFAULT_SHEET_BLUR)
    val sheetBlur: StateFlow<Int> = sheetBlurState
    private val sheetTintState = MutableStateFlow(DEFAULT_SHEET_TINT)
    val sheetTint: StateFlow<Int> = sheetTintState
    private val sheetFringeState = MutableStateFlow(DEFAULT_SHEET_FRINGE)
    val sheetFringe: StateFlow<Int> = sheetFringeState

    fun init(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = preferences
        state.value = preferences.getBoolean(KEY_ENABLED, true)
        sheetBlurState.value = preferences.getInt(KEY_SHEET_BLUR, DEFAULT_SHEET_BLUR)
        sheetTintState.value = preferences.getInt(KEY_SHEET_TINT, DEFAULT_SHEET_TINT)
        sheetFringeState.value = preferences.getInt(KEY_SHEET_FRINGE, DEFAULT_SHEET_FRINGE)
    }

    fun setEnabled(enabled: Boolean) {
        state.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun setSheetBlur(value: Int) = setInt(sheetBlurState, KEY_SHEET_BLUR, value)

    fun setSheetTint(value: Int) = setInt(sheetTintState, KEY_SHEET_TINT, value)

    fun setSheetFringe(value: Int) = setInt(sheetFringeState, KEY_SHEET_FRINGE, value)

    private fun setInt(flow: MutableStateFlow<Int>, key: String, value: Int) {
        val clamped = value.coerceIn(0, 100)
        flow.value = clamped
        prefs?.edit()?.putInt(key, clamped)?.apply()
    }
}

/** The glass of the drawer, the dialogs and the menus as set in [GlassSettings]. */
@Immutable
data class SheetGlassTuning(val blur: Dp, val tint: Float, val fringe: Float) {
    companion object {
        val Default = SheetGlassTuning(
            blur = (GlassSettings.DEFAULT_SHEET_BLUR / 10f).dp,
            tint = GlassSettings.DEFAULT_SHEET_TINT / 100f,
            fringe = GlassSettings.DEFAULT_SHEET_FRINGE / 100f,
        )
    }
}

val LocalSheetGlassTuning = staticCompositionLocalOf { SheetGlassTuning.Default }

/** Whether liquid glass is on; the Material look is kept everywhere while it is off. */
val LocalGlassEnabled = staticCompositionLocalOf { false }

/** The whole app, for dialogs and menus. Null while liquid glass is off. */
val LocalAppBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** How a part of glass looks. */
enum class GlassStyle(val tintAlpha: Float) {
    /** A small control: clear glass bending strongly at its edges. */
    Control(0.2f),

    /** A bar or panel with text (the top bar, the filter panel): a little blur so text over the list stays readable. */
    Panel(0.2f),

    /**
     * A drawer, a dialog or a menu: blurred glass bending gently over a wide edge, with a deeper shadow. The blur, the
     * tint and the color fringes are set in the settings (see [SheetGlassTuning]); this tint is unused.
     */
    Sheet(0.4f),
}

// A lit edge in dark themes only: in light themes it read as a white ring, a soft shadow outlines the glass instead.
private val DarkGlassEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.6f)))
private val DarkSheetEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.5f)))
private val LightGlassShadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.12f))
private val SheetShadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.3f))

// Without blur (before Android 12) the glass is only its tint: a light tint left text over the list unreadable.
private const val UnblurredTint = 0.85f

/** Recording space for glass while liquid glass is on, else null. Drawn on the page color, so no gap shows through. */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop? {
    if (!LocalGlassEnabled.current) return null
    val background = MaterialTheme.colorScheme.surface
    val onDraw: ContentDrawScope.() -> Unit = remember(background) {
        {
            drawRect(background)
            drawContent()
        }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

/** Records this content for the glass in front of it; nothing without a [backdrop]. */
fun Modifier.glassContent(backdrop: LayerBackdrop?): Modifier = if (backdrop == null) this else layerBackdrop(backdrop)

/**
 * Glass behind this part, refracting [backdrop]; nothing without one. [shape] must be a rounded rectangle (the lens
 * takes no other shape).
 */
@Composable
fun Modifier.glass(backdrop: Backdrop?, shape: Shape, style: GlassStyle): Modifier {
    if (backdrop == null) return this
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val tuning = LocalSheetGlassTuning.current
    val styleAlpha = if (style == GlassStyle.Sheet) tuning.tint else style.tintAlpha
    val alpha = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) maxOf(styleAlpha, UnblurredTint) else styleAlpha
    // Light themes tint white: tinted with the gray surface colors, the glass was darker than the page.
    val tint = if (dark) colors.surfaceContainerHigh.copy(alpha = alpha) else Color.White.copy(alpha = alpha)
    val edge = when {
        !dark -> null
        style == GlassStyle.Sheet -> DarkSheetEdge
        else -> DarkGlassEdge
    }
    val shadow = when {
        style == GlassStyle.Sheet -> SheetShadow
        dark -> Shadow.Default
        else -> LightGlassShadow
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            when (style) {
                GlassStyle.Control -> {
                    blur(1.5f.dp.toPx())
                    lens(12f.dp.toPx(), 20f.dp.toPx(), chromaticAberration = true)
                }
                GlassStyle.Panel -> {
                    blur(4f.dp.toPx())
                    lens(16f.dp.toPx(), 28f.dp.toPx(), chromaticAberration = true)
                }
                // A wide gentle bend at the edge with the blur and the color fringes set in the settings (up to
                // 12 dp apart over the outer 24 dp), like the drawers and dialogs of EhViewer.
                GlassStyle.Sheet -> {
                    if (tuning.blur > 0.dp) blur(tuning.blur.toPx())
                    lens(25f.dp.toPx(), 10f.dp.toPx())
                    edgeFringe(24f.dp.toPx(), tuning.fringe * 12f.dp.toPx())
                }
            }
        },
        highlight = edge?.let { { it } },
        shadow = { shadow },
        onDrawSurface = { drawRect(tint) },
    )
}

/** The side of a bar or drawer that faces the page: the only side whose glass edge shows. */
enum class GlassEdge { Top, Bottom, Start, End }

// How far the glass reaches off the screen: more than the bend of the lens.
private val GlassBleed = 48.dp

/**
 * Glass for a part along a side of the screen (a top bar, a drawer), like `edgeGlass` in EhViewer: it keeps [corner]s
 * at both ends of [edge], the side facing the page, and the side facing away reaches off the screen, so its lit edge,
 * shadow and bend lie outside and the part melts into the screen there.
 */
@Composable
fun Modifier.edgeGlass(backdrop: Backdrop?, edge: GlassEdge, style: GlassStyle, corner: Dp = 24.dp): Modifier {
    if (backdrop == null) return this
    val shape = when (edge) {
        GlassEdge.Bottom -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
        GlassEdge.Top -> RoundedCornerShape(topStart = corner, topEnd = corner)
        GlassEdge.Start -> RoundedCornerShape(topStart = corner, bottomStart = corner)
        GlassEdge.End -> RoundedCornerShape(topEnd = corner, bottomEnd = corner)
    }
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    return layout { measurable, constraints ->
        val bleed = GlassBleed.roundToPx()
        val ltr = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr
        // Only the side facing away from the edge reaches off the screen.
        left = if ((edge == GlassEdge.End && ltr) || (edge == GlassEdge.Start && !ltr)) bleed else 0
        right = if ((edge == GlassEdge.Start && ltr) || (edge == GlassEdge.End && !ltr)) bleed else 0
        top = if (edge == GlassEdge.Bottom) bleed else 0
        bottom = if (edge == GlassEdge.Top) bleed else 0
        val placeable = measurable.measure(constraints.offset(left + right, top + bottom))
        layout(placeable.width - left - right, placeable.height - top - bottom) { placeable.place(-left, -top) }
    }.glass(backdrop, shape, style).layout { measurable, constraints ->
        // The content keeps the bounds of the part.
        val placeable = measurable.measure(constraints.offset(-left - right, -top - bottom))
        layout(placeable.width + left + right, placeable.height + top + bottom) { placeable.place(left, top) }
    }
}

/** A selected item or chip on glass: a light in the glass instead of a solid block. */
@Composable
fun glassSelectedColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

/** Parts drawn above the app recording: dialogs and menus as glass. See [GlassOverlay]. */
@Stable
class GlassOverlayState {
    internal val entries = mutableStateListOf<GlassOverlayEntry>()

    /** Where the overlay layer lies, to place menus next to their anchor. */
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
        internal set
}

internal class GlassOverlayEntry {
    var content by mutableStateOf<@Composable () -> Unit>({}, referentialEqualityPolicy())
}

val LocalGlassOverlay = staticCompositionLocalOf<GlassOverlayState?> { null }

/**
 * The root of the app: records it for the dialogs and menus, and draws them above it while liquid glass is on.
 */
@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val enabled by GlassSettings.enabled.collectAsState()
    val sheetBlur by GlassSettings.sheetBlur.collectAsState()
    val sheetTint by GlassSettings.sheetTint.collectAsState()
    val sheetFringe by GlassSettings.sheetFringe.collectAsState()
    val tuning = remember(sheetBlur, sheetTint, sheetFringe) {
        SheetGlassTuning(blur = (sheetBlur / 10f).dp, tint = sheetTint / 100f, fringe = sheetFringe / 100f)
    }
    val overlay = remember { GlassOverlayState() }
    CompositionLocalProvider(
        LocalGlassEnabled provides enabled,
        LocalGlassOverlay provides overlay,
        LocalSheetGlassTuning provides tuning,
    ) {
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { overlay.coordinates = it }) {
                Box(Modifier.fillMaxSize().glassContent(backdrop)) { content() }
                overlay.entries.forEach { entry -> key(entry) { entry.content() } }
            }
        }
    }
}

/**
 * Draws [content] above the whole app, in the app window, while this is in the composition. Without a [GlassHost]
 * it is drawn in place.
 */
@Composable
fun GlassOverlay(content: @Composable () -> Unit) {
    val overlay = LocalGlassOverlay.current
    if (overlay == null) {
        content()
        return
    }
    val entry = remember { GlassOverlayEntry() }
    SideEffect { entry.content = content }
    DisposableEffect(overlay, entry) {
        overlay.entries += entry
        onDispose { overlay.entries -= entry }
    }
}
