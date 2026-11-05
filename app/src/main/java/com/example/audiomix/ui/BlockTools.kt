package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.R

/** Werkzeugzeile für den ausgewählten Block. */
@Composable
fun BlockToolsBar(
    stretchMode: Boolean,
    clipMuted: Boolean,
    onMute: () -> Unit,
    onSplit: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onStretch: () -> Unit,
    onStretchCancel: () -> Unit,
    onStretchConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.rowBg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (stretchMode) {
            GradientSymbol(check = false, onClick = onStretchCancel)
            GradientSymbol(check = true, onClick = onStretchConfirm)
        } else {
            Icon(
                painter = painterResource(if (clipMuted) R.drawable.ic_speaker_muted else R.drawable.ic_speaker),
                contentDescription = "Block stummschalten",
                tint = if (clipMuted) Color(0xFFE8607A) else colors.onBackground,
                modifier = Modifier.size(28.dp).clickable(onClick = onMute)
            )
            Icon(
                painter = painterResource(R.drawable.ic_scissors),
                contentDescription = "Teilen",
                tint = colors.onBackground,
                modifier = Modifier.size(28.dp).clickable(onClick = onSplit)
            )
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = "Kopieren",
                tint = colors.onBackground,
                modifier = Modifier.size(28.dp).clickable(onClick = onCopy)
            )
            Text(
                text = "Ausschneiden",
                color = colors.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onCut)
            )
            Text(
                text = "Dehnen",
                color = colors.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onStretch)
            )
        }
    }
}

/** X oder Haken mit rosa Farbverlauf und schwarzem Rahmen. */
@Composable
private fun GradientSymbol(check: Boolean, onClick: () -> Unit) {
    Canvas(modifier = Modifier.size(40.dp).clickable(onClick = onClick)) {
        val brush = Brush.linearGradient(
            listOf(Color(0xFFFFB3C7), Color(0xFFE8607A), Color(0xFF8E2C75)),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (check) {
                moveTo(w * 0.15f, h * 0.55f)
                lineTo(w * 0.40f, h * 0.80f)
                lineTo(w * 0.85f, h * 0.22f)
            } else {
                moveTo(w * 0.20f, h * 0.20f)
                lineTo(w * 0.80f, h * 0.80f)
                moveTo(w * 0.80f, h * 0.20f)
                lineTo(w * 0.20f, h * 0.80f)
            }
        }
        drawPath(
            path,
            Color.Black,
            style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path,
            brush,
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
