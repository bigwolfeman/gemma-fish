package com.gemmatranslator.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.VolumeUp
import com.gemmatranslator.model.TranslationMode

@Composable
fun ModeToggle(
    mode: TranslationMode,
    onModeChange: (TranslationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "trackColor",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(26.dp),
        color = trackColor,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ModeTab(
                label = "Earbud",
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = "Earbud mode",
                    )
                },
                selected = mode == TranslationMode.EARBUD,
                onClick = { onModeChange(TranslationMode.EARBUD) },
                modifier = Modifier.weight(1f),
            )
            ModeTab(
                label = "Speaker",
                icon = {
                    Icon(
                        imageVector = Icons.Filled.VolumeUp,
                        contentDescription = "Speaker mode",
                    )
                },
                selected = mode == TranslationMode.SPEAKER,
                onClick = { onModeChange(TranslationMode.SPEAKER) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeTab(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "contentColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.95f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabScale",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(selectedColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                icon()
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1B2E)
@Composable
private fun ModeToggleEarbudPreview() {
    com.gemmatranslator.ui.theme.GemmaTranslatorTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            ModeToggle(
                mode = TranslationMode.EARBUD,
                onModeChange = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1B2E)
@Composable
private fun ModeToggleSpeakerPreview() {
    com.gemmatranslator.ui.theme.GemmaTranslatorTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            ModeToggle(
                mode = TranslationMode.SPEAKER,
                onModeChange = {},
            )
        }
    }
}
