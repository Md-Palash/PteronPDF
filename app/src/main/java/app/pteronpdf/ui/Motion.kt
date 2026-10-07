package app.pteronpdf.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A clickable that squeezes a little under the finger and springs back. The scale is applied in a graphics layer that
 * reads the animated value at draw time, so the press animation costs no recomposition.
 */
fun Modifier.pressable(
    shape: Shape, color: Color = Color.Transparent, enabled: Boolean = true, pressedScale: Float = 0.92f, onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val k by animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 600f), label = "press",
    )
    this.graphicsLayer { scaleX = k; scaleY = k }
        .clip(shape).background(color)
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

/**
 * Fade + rise on first appearance, each item a little after the previous one. With [enabled] false the item is simply
 * there (used for rows that scroll in later, so scrolling doesn't replay the entrance).
 */
@Composable
fun Modifier.staggerIn(index: Int, enabled: Boolean = true): Modifier {
    val a = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (a.value < 1f) {
            delay(index * 45L)
            a.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28.dp.toPx() }
}
