package com.m0h31h31.bamburfidreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.util.parseColorValue

@Composable
fun ColorSwatch(
    colorValues: List<String>,
    colorType: String,
    modifier: Modifier = Modifier
) {
    val parsedColors = colorValues.mapNotNull { parseColorValue(it) }
    val colors = if (parsedColors.isNotEmpty()) {
        parsedColors
    } else {
        listOf(MaterialTheme.colorScheme.surface)
    }
    val resolvedType = when {
        colorType.isNotBlank() -> colorType
        colors.size > 1 -> "多拼色"
        else -> "单色"
    }
    val shape = RoundedCornerShape(14.dp)

    when (resolvedType) {
        "渐变色" -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(Brush.horizontalGradient(colors))
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            )
        }

        "多拼色" -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(colors.firstOrNull() ?: Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            )
        }
    }
}
