package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.LocalRippleConfiguration

@Composable
fun ExpressivePowerButtonGroup(
    isOn: Boolean,
    onStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Shape morphing: when ON is active, it gets extra rounded.
    val onLeftCorner by animateDpAsState(
        targetValue = if (isOn) 28.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "onLeftCorner"
    )
    val onRightCorner by animateDpAsState(
        targetValue = if (isOn) 4.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "onRightCorner"
    )
    val offLeftCorner by animateDpAsState(
        targetValue = if (!isOn) 4.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "offLeftCorner"
    )
    val offRightCorner by animateDpAsState(
        targetValue = if (!isOn) 28.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "offRightCorner"
    )

    // Animated colors
    val onBgColor by animateColorAsState(
        targetValue = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "onBg"
    )
    val onTextColor by animateColorAsState(
        targetValue = if (isOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "onText"
    )
    val offBgColor by animateColorAsState(
        targetValue = if (!isOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offBg"
    )
    val offTextColor by animateColorAsState(
        targetValue = if (!isOn) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "offText"
    )

    val onInteractionSource = remember { MutableInteractionSource() }
    val offInteractionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(28.dp)
            )
            .padding(4.dp)
    ) {
        // ON Button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = onLeftCorner,
                        bottomStart = onLeftCorner,
                        topEnd = onRightCorner,
                        bottomEnd = onRightCorner
                    )
                )
                .background(onBgColor)
                .clickable(
                    interactionSource = onInteractionSource,
                    indication = null,
                    onClick = { onStateChanged(true) }
                )
                .joyfulPress(onInteractionSource),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "POWER ON",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = onTextColor
                )
            )
        }

        // OFF Button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = offLeftCorner,
                        bottomStart = offLeftCorner,
                        topEnd = offRightCorner,
                        bottomEnd = offRightCorner
                    )
                )
                .background(offBgColor)
                .clickable(
                    interactionSource = offInteractionSource,
                    indication = null,
                    onClick = { onStateChanged(false) }
                )
                .joyfulPress(offInteractionSource),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "POWER OFF",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = offTextColor
                )
            )
        }
    }
}
