package app.pteronpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.theme.LocalPteron

/** Full-width bar pinned to the top (not floating). It paints under the status bar. */
@Composable
fun FixedTopBar(content: @Composable RowScope.() -> Unit) {
    val c = LocalPteron.current
    Column(Modifier.fillMaxWidth().background(c.bar).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

val TopBarHeight = 56.dp

/** Where a card sits in a stacked group: only the outer corners of the group are rounded. */
enum class CardPos { Top, Middle, Bottom, Single }

fun cardShape(pos: CardPos, r: Dp = 26.dp): Shape = when (pos) {
    CardPos.Top -> RoundedCornerShape(topStart = r, topEnd = r, bottomStart = 0.dp, bottomEnd = 0.dp)
    CardPos.Middle -> RoundedCornerShape(0.dp)
    CardPos.Bottom -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = r, bottomEnd = r)
    CardPos.Single -> RoundedCornerShape(r)
}

/** One card of the top/middle/bottom stack on the main settings page. */
@Composable
fun CategoryCard(icon: PIcon, title: String, subtitle: String, pos: CardPos, onClick: () -> Unit) {
    val c = LocalPteron.current
    Row(
        Modifier.fillMaxWidth().clip(cardShape(pos)).background(c.bar).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            PIconView(icon, c.onBar, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
        }
        PIconView(PIcon.Chevron, c.onBar.copy(alpha = 0.5f), Modifier.size(20.dp))
    }
}

/** Stack of cards with a hairline gap so they read as one symmetrical block. */
@Composable
fun CardStack(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** The single, fully rounded card on a sub-page; its options are separated by thin lines. */
@Composable
fun OptionsCard(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPteron.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.bar), content = content)
}

@Composable
fun OptionDivider() {
    val c = LocalPteron.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(c.divider))
}

@Composable
fun OptionRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalPteron.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = c.onBar)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** On/off switch on the right of a card row, in the theme's darker shade when on. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalPteron.current
    OptionRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = c.onBar.copy(alpha = 0.55f), uncheckedTrackColor = c.chip,
                uncheckedBorderColor = c.onBar.copy(alpha = 0.3f),
            ),
        )
    }
}

/** Selected = the theme's darker shade, others sit on the card shade. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, fontFamily: androidx.compose.ui.text.font.FontFamily? = null, onClick: () -> Unit) {
    val c = LocalPteron.current
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.accent else c.chip)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.divider, RoundedCornerShape(16.dp)))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontFamily = fontFamily, color = if (selected) c.onAccent else c.onBar)
    }
}
