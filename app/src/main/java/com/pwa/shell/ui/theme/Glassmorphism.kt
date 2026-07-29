package com.pwa.shell.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Applies a frosted glass (Glassmorphism) visual effect to a Modifier.
 * Combines translucent background gradient, soft ambient drop shadow, and a subtle glowing border.
 */
@Composable
fun Modifier.glassmorphic(
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 8.dp,
    borderWidth: Dp = 1.dp,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
): Modifier {
    val glassGradient = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0x38334155), // Slate 700 22%
                Color(0x1F1E293B), // Slate 800 12%
                Color(0x2D0F172A)  // Slate 900 18%
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xF0FFFFFF), // Pure white 94%
                Color(0xD9F8FAFC), // Slate 50 85%
                Color(0xCCF1F5F9)  // Slate 100 80%
            )
        )
    }

    val borderGradient = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0x55FFFFFF), // Translucent white top-left highlight
                Color(0x1AFFFFFF),
                Color(0x0DFFFFFF)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0x99FFFFFF),
                Color(0x40CBD5E1),
                Color(0x20CBD5E1)
            )
        )
    }

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = if (isDark) Color(0x66000000) else Color(0x1F0F172A),
            spotColor = if (isDark) Color(0xAA000000) else Color(0x3D0284C7)
        )
        .clip(shape)
        .background(glassGradient)
        .border(borderWidth, borderGradient, shape)
}

/**
 * Creates a subtle glass card background with specular highlight.
 */
@Composable
fun Modifier.glassmorphicCard(
    shape: Shape = RoundedCornerShape(20.dp),
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
): Modifier {
    val backgroundBrush = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x2B334155),
                Color(0x1A1E293B)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xE6FFFFFF),
                Color(0xCCF1F5F9)
            )
        )
    }

    val borderBrush = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x40FFFFFF),
                Color(0x10FFFFFF)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x80FFFFFF),
                Color(0x33CBD5E1)
            )
        )
    }

    return this
        .clip(shape)
        .background(backgroundBrush)
        .border(1.dp, borderBrush, shape)
}

/**
 * A denser glass surface for menus and drawers that contain text.
 *
 * Decorative glass can stay translucent, but interactive overlays need a stable
 * contrast floor so content behind them does not compete with labels and icons.
 */
@Composable
fun Modifier.glassmorphicOverlay(
    shape: Shape = RoundedCornerShape(22.dp),
    elevation: Dp = 14.dp,
    borderWidth: Dp = 1.dp,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val backgroundBrush = Brush.linearGradient(
        colors = if (isDark) {
            listOf(
                surfaceVariant.copy(alpha = 0.96f),
                surface.copy(alpha = 0.94f)
            )
        } else {
            listOf(
                surface.copy(alpha = 0.97f),
                surfaceVariant.copy(alpha = 0.94f)
            )
        }
    )
    val borderBrush = Brush.linearGradient(
        colors = if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.30f),
                Color.White.copy(alpha = 0.12f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.80f),
                MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
            )
        }
    )

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = if (isDark) Color.Black.copy(alpha = 0.55f)
            else Color.Black.copy(alpha = 0.16f),
            spotColor = if (isDark) Color.Black.copy(alpha = 0.72f)
            else Color.Black.copy(alpha = 0.22f)
        )
        .clip(shape)
        .background(backgroundBrush)
        .border(borderWidth, borderBrush, shape)
}
