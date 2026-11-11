package app.pteronpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.theme.LocalPteron
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** One open PDF as the tab bar shows it. [id] is stable for as long as the PDF is open. */
data class TabItem(val id: Int, val name: String, val dirty: Boolean)

/** Height the tab bar takes under the top bar (the reader leaves this much room above its pages). */
val TabsBarHeight = 46.dp

private class PillDrag(val tab: TabItem, val grab: Offset, val size: IntSize, val origin: Offset, start: Offset) {
    var pos by mutableStateOf(start)        // finger, in screen coordinates
    var fade by mutableFloatStateOf(1f)     // 1 = whole, 0 = gone (when dropped on the close button)
}

/**
 * The open PDFs as pills. Tap one to switch to it. Press and hold one: a close button rises at the bottom, the pill lifts and
 * follows the finger, and letting go over the close button closes that PDF (letting go anywhere else puts it back).
 * It draws over the whole screen but only the pills, the close button and the lifted pill take touches.
 */
@Composable
fun TabsBar(tabs: List<TabItem>, activeId: Int, shown: Boolean, top: Dp, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    val c = LocalPteron.current
    val dens = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    var drag by remember { mutableStateOf<PillDrag?>(null) }
    val zone = remember { floatArrayOf(0f, 0f) }                         // close button centre, screen coordinates
    val reach = with(dens) { 78.dp.toPx() }
    val over by remember { derivedStateOf { drag?.let { it.fade == 1f && hypot(it.pos.x - zone[0], it.pos.y - zone[1]) < reach } ?: false } }

    // a newly opened PDF's pill is brought into view
    var count by remember { mutableIntStateOf(tabs.size) }
    LaunchedEffect(tabs.size) {
        if (tabs.size > count) scroll.animateScrollTo(scroll.maxValue, tween(300, easing = FastOutSlowInEasing))
        count = tabs.size
    }
    LaunchedEffect(over) { if (over) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            shown && tabs.size > 1,
            enter = slideInVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 2 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                for (t in tabs) key(t.id) {
                    Pill(
                        t, t.id == activeId, hidden = { drag?.tab?.id == t.id },
                        onTap = { onSelect(t.id) },
                        onLift = { d -> drag = d; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        dragOf = { drag },
                        onDrop = {
                            val d = drag ?: return@Pill
                            scope.launch {
                                if (over) {
                                    // into the close button: shrink away, then close
                                    animate(1f, 0f, animationSpec = tween(150)) { v, _ -> d.fade = v }
                                    drag = null; onClose(d.tab.id)
                                } else {
                                    // back where it came from
                                    val from = d.pos; val to = d.origin + d.grab
                                    animate(0f, 1f, animationSpec = tween(200, easing = FastOutSlowInEasing)) { v, _ -> d.pos = lerp(from, to, v) }
                                    drag = null
                                }
                            }
                        },
                    )
                }
            }
        }

        // the close button, risen from the bottom while a pill is held
        val rise by animateFloatAsState(if (drag != null) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "closeRise")
        val grow by animateFloatAsState(if (over) 1.22f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "closeGrow")
        if (rise > 0.01f) Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp)
                .onGloballyPositioned { val p = it.positionInRoot(); zone[0] = p.x + it.size.width / 2f; zone[1] = p.y + it.size.height / 2f }
                .graphicsLayer {
                    alpha = rise; translationY = (1f - rise) * 90.dp.toPx(); scaleX = grow; scaleY = grow
                }
                .size(64.dp).shadow(if (over) 12.dp else 6.dp, CircleShape)
                .background(if (over) Color(0xFFE5484D) else c.bar, CircleShape),
            contentAlignment = Alignment.Center,
        ) { PIconView(PIcon.Close, if (over) Color.White else c.onBar, Modifier.size(26.dp), "Close PDF") }

        // the lifted pill
        drag?.let { d ->
            Box(
                Modifier.graphicsLayer {
                    translationX = d.pos.x - d.grab.x; translationY = d.pos.y - d.grab.y
                    val k = (if (over) 0.86f else 1.06f) * (0.3f + 0.7f * d.fade)
                    scaleX = k; scaleY = k; alpha = d.fade
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(d.grab.x / d.size.width, d.grab.y / d.size.height)
                }.shadow(10.dp, RoundedCornerShape(18.dp)),
            ) { PillFace(d.tab, true, Modifier) }
        }
    }
}

@Composable
private fun PillFace(t: TabItem, active: Boolean, modifier: Modifier) {
    val c = LocalPteron.current
    val bg by animateColorAsState(if (active) c.accent else c.bar, tween(200), label = "pillBg")
    val fg by animateColorAsState(if (active) c.onAccent else c.onBar, tween(200), label = "pillFg")
    Row(
        modifier.background(bg, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.dirty) Box(Modifier.padding(end = 6.dp).size(6.dp).background(fg, CircleShape))
        Text(
            t.name.removeSuffix(".pdf"), color = fg, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}

@Composable
private fun Pill(
    t: TabItem, active: Boolean, hidden: () -> Boolean, onTap: () -> Unit,
    onLift: (PillDrag) -> Unit, dragOf: () -> PillDrag?, onDrop: () -> Unit,
) {
    val geo = remember { floatArrayOf(0f, 0f, 0f, 0f) }       // top-left in screen coordinates, size in px
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 350f)) }
    Box(
        Modifier
            .onGloballyPositioned { val p = it.positionInRoot(); geo[0] = p.x; geo[1] = p.y; geo[2] = it.size.width.toFloat(); geo[3] = it.size.height.toFloat() }
            .graphicsLayer {
                val a = appear.value
                scaleX = 0.7f + 0.3f * a; scaleY = scaleX; alpha = if (hidden()) 0f else a
            }
            .shadow(if (active) 0.dp else 2.dp, RoundedCornerShape(18.dp))
            .pointerInput(t.id) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(t.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        val origin = Offset(geo[0], geo[1])
                        onLift(PillDrag(t, o, IntSize(geo[2].toInt().coerceAtLeast(1), geo[3].toInt().coerceAtLeast(1)), origin, origin + o))
                    },
                    onDrag = { change, amount -> change.consume(); dragOf()?.let { it.pos += amount } },
                    onDragEnd = onDrop, onDragCancel = onDrop,
                )
            },
    ) { PillFace(t, active, Modifier) }
}
