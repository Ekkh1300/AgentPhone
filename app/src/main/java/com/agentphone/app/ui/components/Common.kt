package com.agentphone.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentphone.app.domain.agent.RiskLevel

@Composable
fun AgentLogo(modifier: Modifier = Modifier, size: Int = 36) {
    val brand = listOf(Color(0xFF4338CA), Color(0xFF7C3AED), Color(0xFF06B6D4))
    Canvas(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 3.2f)),
    ) {
        val unit = this.size.minDimension / 108f
        val width = this.size.width
        val height = this.size.height
        drawRect(Brush.linearGradient(brand, start = Offset.Zero, end = Offset(width, height)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                center = Offset(width * 0.13f, height * 0.09f),
                radius = this.size.minDimension * 0.72f,
            ),
            radius = this.size.minDimension * 0.72f,
            center = Offset(width * 0.13f, height * 0.09f),
        )
        scale(unit, unit, pivot = Offset.Zero) {
            drawCircle(
                color = Color.White.copy(alpha = 0.2f),
                radius = 30.5f,
                center = Offset(54f, 54f),
                style = Stroke(width = 1.3f),
            )
            val bubble = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(30f, 34f, 78f, 72f),
                        cornerRadius = CornerRadius(14f, 14f),
                    ),
                )
            }
            val tail = Path().apply {
                moveTo(41f, 66f)
                lineTo(53f, 66f)
                lineTo(39f, 80f)
                close()
            }
            val shadow = Color(0xFF120A3A).copy(alpha = 0.24f)
            withTransform({ translate(2.2f, 3f) }) {
                drawPath(bubble, shadow)
                drawPath(tail, shadow)
            }
            drawPath(
                bubble,
                Brush.linearGradient(
                    listOf(Color.White, Color(0xFFE7EBFF)),
                    start = Offset(30f, 34f),
                    end = Offset(78f, 72f),
                ),
            )
            drawPath(tail, Color.White)
            drawCircle(Color(0xFF4C1D95), radius = 3.4f, center = Offset(46.8f, 50.8f))
            drawCircle(Color(0xFF4C1D95), radius = 3.4f, center = Offset(61.2f, 50.8f))
            drawPath(
                Path().apply {
                    moveTo(48.4f, 58.2f)
                    cubicTo(50.4f, 61.4f, 57.6f, 61.4f, 59.6f, 58.2f)
                },
                Color(0xFF4C1D95),
                style = Stroke(width = 2.3f, cap = StrokeCap.Round),
            )
            drawPath(
                Path().apply {
                    moveTo(70f, 27.4f)
                    lineTo(71.9f, 31.3f)
                    lineTo(75.8f, 33.2f)
                    lineTo(71.9f, 35.1f)
                    lineTo(70f, 39f)
                    lineTo(68.1f, 35.1f)
                    lineTo(64.2f, 33.2f)
                    lineTo(68.1f, 31.3f)
                    close()
                },
                Color(0xFFFBBF24),
            )
            drawCircle(Color(0xFFFFF7E0), radius = 1.5f, center = Offset(70f, 33.2f))
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.9f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun RiskChip(risk: RiskLevel, modifier: Modifier = Modifier) {
    val (container, content, label) = when (risk) {
        RiskLevel.READ -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "READ",
        )
        RiskLevel.WRITE -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "WRITE",
        )
        RiskLevel.DANGEROUS -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "DANGER",
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun SkeletonList(rows: Int = 6) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(rows) { index ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha))
                    .padding(16.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.55f else 0.7f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                )
            }
        }
    }
}

@Composable
fun LoadingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "dotsAlpha",
    )
    Row(modifier.alpha(alpha), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
