package com.cookbook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cookbook.ui.theme.CookbookTheme
import design.pulse.ui.components.PulseButton

/** The brand mark: an open book silhouette over the heat (orange→amber) hero gradient. */
@Composable
fun BrandLogo(size: Dp = 76.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(CookbookTheme.colors.heroGradient),
        contentAlignment = Alignment.Center,
    ) {
        // Two "pages": rounded rectangles splayed side by side.
        Box(
            modifier = Modifier
                .size(width = size * 0.46f, height = size * 0.34f),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = size * 0.22f, height = size * 0.34f)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(Color.White.copy(alpha = 0.92f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = size * 0.22f, height = size * 0.34f)
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(Color.White.copy(alpha = 0.92f)),
            )
        }
    }
}

/**
 * The primary call-to-action used across the auth flows. A thin alias over the PULSE
 * [PulseButton] (hero-gradient block with press-scale), so the whole app shares one button voice.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PulseButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}

/** Full-width variant convenience. */
@Composable
fun PrimaryButtonFullWidth(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PrimaryButton(text, onClick, modifier.fillMaxWidth(), enabled)
