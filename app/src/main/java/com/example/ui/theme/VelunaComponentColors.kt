package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun velunaFilterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
    labelColor = MaterialTheme.colorScheme.onSurface,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
fun velunaFilterChipBorder(selected: Boolean): BorderStroke = BorderStroke(
    width = if (selected) 1.5.dp else 1.dp,
    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
)

@Composable
fun velunaInputChipColors(): SelectableChipColors = InputChipDefaults.inputChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
fun velunaOutlinedFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTrailingIconColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)

@Composable
fun velunaPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun velunaOutlinedButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
)

@Composable
fun velunaOutlinedButtonBorder(enabled: Boolean = true): BorderStroke = BorderStroke(
    width = 1.2.dp,
    color = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun velunaSliderColors(enabled: Boolean = true): SliderColors = SliderDefaults.colors(
    thumbColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    activeTrackColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
)

@Composable
fun velunaNavBarItemColors(): NavigationBarItemColors = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun velunaNavRailItemColors(): NavigationRailItemColors = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)
