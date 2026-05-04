package com.gemmatranslator.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ModeTab(
                label = "Earbud",
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = "Earbud mode",
                        modifier = Modifier.padding(end = 4.dp),
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
                        modifier = Modifier.padding(end = 4.dp),
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
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabContent",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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

@Preview(showBackground = true)
@Composable
private fun ModeTogglePreview() {
    com.gemmatranslator.ui.theme.GemmaTranslatorTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ModeToggle(
                mode = TranslationMode.EARBUD,
                onModeChange = {},
            )
        }
    }
}
