package com.local.stardbzzz.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlin.math.roundToInt

private val GlassDialogScrim = Color.Black.copy(alpha = 0.32f)
private val GlassDialogShape = RoundedCornerShape(28.dp)
private val GlassMenuShape = RoundedCornerShape(16.dp)
private val GlassDialogMargin = 16.dp
private val MenuMargin = 8.dp

/**
 * A [Scaffold] whose top bar is glass over the page. [content] gets the padding for the top bar (apply it inside a
 * list as content padding, so the list scrolls under the bar) and the page recording: record the page with
 * [glassContent] and leave out anything that is glass itself. Without liquid glass it is the plain Material scaffold.
 */
@Composable
fun GlassScaffold(
    topBar: @Composable (modifier: Modifier, colors: TopAppBarColors) -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (padding: PaddingValues, page: LayerBackdrop?) -> Unit,
) {
    val page = rememberGlassBackdrop()
    Scaffold(
        modifier = modifier,
        topBar = {
            if (page == null) {
                topBar(Modifier, TopAppBarDefaults.topAppBarColors())
            } else {
                topBar(
                    Modifier.edgeGlass(page, GlassEdge.Bottom, GlassStyle.Panel),
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            }
        },
        contentWindowInsets = contentWindowInsets,
    ) { padding -> content(padding, page) }
}

/**
 * An [AlertDialog] as glass over the app, laid out like the Material one; the Material dialog while liquid glass is
 * off.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val backdrop = LocalAppBackdrop.current
    if (backdrop == null || LocalGlassOverlay.current == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
        return
    }
    GlassOverlay {
        GlassDialogFrame(
            backdrop = backdrop,
            onDismissRequest = onDismissRequest,
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
        ) {
            Column(Modifier.padding(24.dp)) {
                icon?.let {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.secondary) {
                        Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { it() }
                    }
                }
                title?.let {
                    ProvideTextStyle(
                        MaterialTheme.typography.headlineSmall.copy(
                            textAlign = if (icon == null) TextAlign.Start else TextAlign.Center,
                        ),
                    ) {
                        Box(
                            Modifier.padding(bottom = 16.dp)
                                .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally),
                        ) { it() }
                    }
                }
                // The text in the color of the title: the gray of a dialog is hard to read on the glass.
                text?.let {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) { it() }
                    }
                }
                FlowRow(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
private fun GlassDialogFrame(
    backdrop: LayerBackdrop,
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
    content: @Composable () -> Unit,
) {
    // Takes back even when it does not dismiss, like a dialog window.
    BackHandler { if (dismissOnBackPress) onDismissRequest() }
    ClearFocusOnOpen()
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = shown, modifier = Modifier.matchParentSize(), enter = fadeIn()) {
            // Takes the taps even when they do not dismiss, so the page under it does not get them.
            Box(
                Modifier.fillMaxSize().background(GlassDialogScrim).clickable(interactionSource = null, indication = null) {
                    if (dismissOnClickOutside) onDismissRequest()
                },
            )
        }
        // Kept off the screen edges and no wider than a Material dialog.
        Box(
            Modifier.matchParentSize().safeDrawingPadding().padding(GlassDialogMargin),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visibleState = shown, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
                Surface(
                    modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
                        .glass(backdrop, GlassDialogShape, GlassStyle.Sheet),
                    shape = GlassDialogShape,
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    content = content,
                )
            }
        }
    }
}

/**
 * A [DropdownMenu] as glass over the app, below (or above, without room) the box it is called in; the Material menu
 * while liquid glass is off.
 */
@Composable
fun BoxScope.GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalAppBackdrop.current
    val overlay = LocalGlassOverlay.current
    if (backdrop == null || overlay == null) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
        return
    }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Spacer(Modifier.matchParentSize().onGloballyPositioned { anchor = it })
    if (!expanded) return
    GlassOverlay {
        BackHandler(onBack = onDismissRequest)
        ClearFocusOnOpen()
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismissRequest))
        val host = overlay.coordinates
        val anchorCoordinates = anchor
        if (host == null || anchorCoordinates == null || !host.isAttached || !anchorCoordinates.isAttached) {
            return@GlassOverlay
        }
        val anchorTopLeft = host.localPositionOf(anchorCoordinates, Offset.Zero)
        val anchorSize = anchorCoordinates.size
        val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
        // The app draws edge to edge: the menu stays clear of the status, navigation and cutout areas.
        val safeInsets = WindowInsets.safeDrawing
        // The overlay layer lies at the host's origin, so host coordinates are layout coordinates here.
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                AnimatedVisibility(
                    visibleState = shown,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f, transformOrigin = TransformOrigin(0f, 0f)),
                ) {
                    Column(
                        Modifier.glass(backdrop, GlassMenuShape, GlassStyle.Sheet)
                            .clip(GlassMenuShape)
                            .padding(vertical = 8.dp)
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 112.dp, max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            content()
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val margin = MenuMargin.roundToPx()
            val gap = 4.dp.roundToPx()
            val maxWidth = constraints.maxWidth
            val maxHeight = constraints.maxHeight
            // The area the menu may use: the safe area less the margin.
            val left = safeInsets.getLeft(this, layoutDirection) + margin
            val right = maxWidth - safeInsets.getRight(this, layoutDirection) - margin
            val top = safeInsets.getTop(this) + margin
            val bottom = maxHeight - safeInsets.getBottom(this) - margin
            val measurable = measurables.firstOrNull()
                ?: return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
            val placeable = measurable.measure(
                Constraints(
                    maxWidth = (right - left).coerceAtLeast(0),
                    maxHeight = (bottom - top).coerceAtLeast(0),
                ),
            )
            layout(maxWidth, maxHeight) {
                val x = anchorTopLeft.x.roundToInt()
                    .coerceAtMost(right - placeable.width)
                    .coerceAtLeast(left)
                val anchorTop = anchorTopLeft.y.roundToInt()
                val below = anchorTop + anchorSize.height + gap
                val above = anchorTop - gap - placeable.height
                val y = when {
                    below >= top && below + placeable.height <= bottom -> below
                    above >= top -> above
                    else -> (bottom - placeable.height).coerceAtLeast(top)
                }
                placeable.place(x, y)
            }
        }
    }
}

/**
 * Takes the focus off the page when a dialog or menu opens (a dialog window would take it), so typing no longer goes
 * to the search field under it and the keyboard closes.
 */
@Composable
private fun ClearFocusOnOpen() {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }
}
