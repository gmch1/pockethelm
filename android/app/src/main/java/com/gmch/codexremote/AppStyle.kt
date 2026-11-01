package com.gmch.codexremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Explicit green/neutral roles keep Material defaults from introducing purple accents. */
@Composable
internal fun PocketHelmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xff226f50), onPrimary = Color.White,
        primaryContainer = Color(0xffdeeee3), onPrimaryContainer = Color(0xff143d2c),
        secondary = Color(0xff426451), onSecondary = Color.White,
        secondaryContainer = Color(0xffe5efe7), onSecondaryContainer = Color(0xff203c2b),
        tertiary = Color(0xff49675e), onTertiary = Color.White,
        tertiaryContainer = Color(0xffe0eee7), onTertiaryContainer = Color(0xff244037),
        background = Color(0xfffbfcf8), onBackground = Color(0xff182720),
        surface = Color(0xfffbfcf8), onSurface = Color(0xff182720),
        surfaceVariant = Color(0xffe8eee8), onSurfaceVariant = Color(0xff58685e),
        surfaceTint = Color(0xff226f50), surfaceBright = Color(0xfffbfcf8), surfaceDim = Color(0xffdce4dc),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff1f5ef),
        surfaceContainer = Color(0xffedf2eb), surfaceContainerHigh = Color(0xffe6ede4),
        surfaceContainerHighest = Color(0xffe0e8de),
        outline = Color(0xff7a8c80), outlineVariant = Color(0xffd8e2da),
        inverseSurface = Color(0xff26352c), inverseOnSurface = Color(0xfff1f5ef), inversePrimary = Color(0xffa0d7b5),
        error = Color(0xffa8362d), onError = Color.White,
        errorContainer = Color(0xfffbe9e5), onErrorContainer = Color(0xff70251f)
    ), content = content)
}

/** In-place tabs: no popup, focus request, keyboard hide/show, or TextField replacement. */
@Composable
internal fun ChoiceTabs(labels: List<String>, selected: Int, select: (Int) -> Unit,
    modifier: Modifier = Modifier, preserveEditorFocus: Boolean = false, small: Boolean = false) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.selectableGroup().padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .focusProperties { canFocus = !preserveEditorFocus }
                    .selectable(active, role = Role.Tab, onClick = { select(index) })
                    .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = if (small) 12.sp else 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun SelectionChip(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean = true) {
    FilterChip(selected, onClick, enabled = enabled,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary),
        border = FilterChipDefaults.filterChipBorder(enabled, selected,
            selectedBorderColor = MaterialTheme.colorScheme.primary, selectedBorderWidth = 1.dp))
}
