package com.cookbook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
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
 * A compact, designed empty state for *in-list* blanks — a no-matches result inside a scrolling
 * screen where PULSE's full-screen [design.pulse.ui.components.EmptyState] can't be used (it fills
 * its parent). Warm/amber-led per Cookbook's channel semantics (the tinted glyph reads on the heat
 * primary), theme-aware in light and dark via [MaterialTheme.colorScheme]. Optional [onCtaClick]
 * renders a text button under the message.
 */
@Composable
fun InlineEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    ctaLabel: String? = null,
    onCtaClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (ctaLabel != null && onCtaClick != null) {
            androidx.compose.material3.TextButton(
                onClick = onCtaClick,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(ctaLabel, color = MaterialTheme.colorScheme.primary)
            }
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
