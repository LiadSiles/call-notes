package com.callnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Building blocks of the "quiet ink" layout: big page titles, grouped white cards with
 * inset hairlines, rows with a tinted icon tile, whole-row switches. Screens compose these
 * instead of styling text and spacing ad hoc.
 */

/** Large page title (tabs), with an optional line under it and actions at the end. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 28.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        actions()
    }
}

/** Title bar of a sub-page: back arrow and a title. */
@Composable
fun SubPageBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp))
    }
}

/** Small muted label above a card group. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** White rounded card holding rows; put [RowDivider] between rows. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** Hairline between rows of a [CardGroup], inset past the icon tile. */
@Composable
fun RowDivider(inset: Boolean = true) {
    HorizontalDivider(
        Modifier.padding(start = if (inset) 68.dp else 18.dp, end = 18.dp),
        thickness = 0.8.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Rounded square with a tinted background and an icon: tells categories apart at a glance. */
@Composable
fun IconTile(icon: ImageVector, tint: Pair<Color, Color>, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32).dp)).background(tint.first),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint.second, modifier = Modifier.size((size * 0.55).dp))
    }
}

/** Row that opens something: icon tile, title, one-line summary of the current state, chevron. */
@Composable
fun NavRow(
    icon: ImageVector,
    tint: Pair<Color, Color>,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    attention: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (attention) {
            Box(Modifier.padding(end = 6.dp).size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.error))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** A setting with a switch; the whole row toggles it. [body] is one or two short lines. */
@Composable
fun SwitchRow(
    title: String,
    body: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 60.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

/** A plain row: title, optional body, optional trailing content. */
@Composable
fun InfoRow(title: String, body: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

/** Muted explanatory note under a group, with a small info glyph. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Big status card at the top of a page: tinted, icon, headline, line, optional action. */
@Composable
fun HeroCard(
    icon: ImageVector,
    title: String,
    body: String,
    good: Boolean,
    onClick: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (good) cs.primaryContainer else cs.errorContainer
    val fg = if (good) cs.onPrimaryContainer else cs.onErrorContainer
    Surface(
        shape = MaterialTheme.shapes.large,
        color = bg,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.78f))
                }
                if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = fg.copy(alpha = 0.6f))
            }
            footer()
        }
    }
}

/**
 * A setting with a few fixed values, shown as a scrollable row of chips under a title.
 * [label] names each value; [enabled] false greys the row out.
 */
@Composable
fun <T> ChipChoice(
    title: String,
    body: String?,
    options: List<T>,
    value: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onChoose: (T) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp, bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        }
        Row(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                androidx.compose.material3.FilterChip(
                    selected = value == option,
                    enabled = enabled,
                    onClick = { if (value != option) onChoose(option) },
                    label = { Text(label(option)) },
                    shape = MaterialTheme.shapes.small,
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}
