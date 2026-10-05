package com.sangam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sangam.app.R

/** Sangam = confluence. Two rivers (indigo: what you need, marigold: what others offer) meeting in a green "match". */
object River {
    val mist = Color(0xFFF4F6FA)
    val paper = Color(0xFFFFFFFF)
    val ink = Color(0xFF1E2A5A)
    val inkSoft = Color(0xFF5A6488)
    val line = Color(0xFFDCE1EE)
    val indigo = Color(0xFF3949AB)
    val marigold = Color(0xFFF2A100)
    val confluence = Color(0xFF2E7D6B)
    val alert = Color(0xFFC62828)
}

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, width: Float, opsz: Float) = Font(
    R.font.bricolage, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width), FontVariation.Setting("opsz", opsz)),
)

val Display = FontFamily(bricolage(800, 85f, 72f), bricolage(700, 90f, 48f))
val Body = FontFamily(bricolage(400, 100f, 14f), bricolage(600, 100f, 14f))

private val type = Typography(
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 44.sp, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 28.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight(600), fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight(600), fontSize = 12.sp),
)

@Composable
fun SangamTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(
        primary = River.ink, onPrimary = Color.White, secondary = River.marigold, background = River.mist,
        surface = River.paper, onSurface = River.ink, onBackground = River.ink, outline = River.line,
    ),
    typography = type,
    content = content,
)

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(River.paper).border(1.dp, River.line, RoundedCornerShape(14.dp)).padding(16.dp)) { content() }
}

@Composable
fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled, shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = River.ink)) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
fun Secondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    OutlinedButton(onClick, modifier.height(46.dp), enabled = enabled, shape = RoundedCornerShape(23.dp)) { Text(text, style = MaterialTheme.typography.labelLarge, color = River.ink) }

@Composable
fun Hint(text: String, color: Color = River.inkSoft) = Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

/**
 * The confluence bar: indigo (they can help you) and marigold (you can help them) flow into one line.
 * Two strong rivers mean a mutual match.
 */
@Composable
fun ConfluenceBar(theyHelpMe: Float, iHelpThem: Float) {
    fun pct(x: Float) = (x.coerceIn(0f, 0.6f) / 0.6f)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(River.line), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth(pct(theyHelpMe)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(River.indigo))
            }
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(River.line), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(pct(iHelpThem)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(River.marigold))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("They can help you ${(pct(theyHelpMe) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = River.indigo, modifier = Modifier.weight(1f))
            Text("You can help them ${(pct(iHelpThem) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = River.marigold)
        }
    }
}

@Composable
fun Chip(text: String, color: Color = River.inkSoft) = Text(
    text, style = MaterialTheme.typography.labelMedium, color = color,
    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp),
)
