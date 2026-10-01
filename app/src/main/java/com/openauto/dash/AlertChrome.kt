package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/*
 * The alerts' surfaces in the look chosen in Settings, Look. The five designs
 * ([AlertStyle]) keep their size and place; what follows the look is their
 * corners, their finish (glass, a chrome bezel, a neon tube...) and the face
 * of their big type, so an alert reads as part of the dashboard it pops over.
 * Every finish stays opaque: an alert has to read over any app.
 */

/** How a look dresses an alert. */
internal enum class AlertFinish {
    /** A solid card and a hairline: the calm colour-only looks. */
    PLAIN,
    /** Glass looks: a sheen over the card and a catch of light along the top. */
    GLASS,
    /** Glowing looks at night (Neon, Sporty, Cyber): a lit top edge, the rim in the alert's colour. */
    LIT,
    /** Orbit: a rim running from accent to accent, a soft light inside. */
    HALO,
    /** Cockpit: leather in a turned-chrome bezel, square corners. */
    CHROME,
    /** Horizon: the card fading into the sky, a sunset line along the bottom. */
    SCENE,
    /** Tape Deck: a black display glass in a neon tube, square corners. */
    NEON,
    /** Canvas: the rail's smoked glass. */
    SMOKED
}

/** The active look's finish. With effects off every look is plain; its corners and type stay. */
internal val alertFinish: AlertFinish
    get() {
        if (DashColors.Effects == DashEffects.NONE) return AlertFinish.PLAIN
        return when (DashColors.Skin) {
            DashSkin.ORBIT -> AlertFinish.HALO
            DashSkin.COCKPIT -> AlertFinish.CHROME
            DashSkin.HORIZON -> AlertFinish.SCENE
            DashSkin.TAPE_DECK -> AlertFinish.NEON
            DashSkin.CANVAS -> AlertFinish.SMOKED
            DashSkin.STANDARD -> when {
                DashColors.Glass -> AlertFinish.GLASS
                // The theme's own glow, before the effects setting scales it.
                DashColors.Glow / DashColors.Effects.scale >= 0.6f -> AlertFinish.LIT
                else -> AlertFinish.PLAIN
            }
        }
    }

/** Looks drawn in hard edges: their pill is a small-cornered plate, not a capsule. */
internal val alertSquare: Boolean get() = DashColors.Skin == DashSkin.COCKPIT || DashColors.Skin == DashSkin.TAPE_DECK

/** [style]'s outline in the active look's corners; the side panel is square against the screen edge. */
internal fun alertShape(style: AlertStyle): Shape {
    val shapes = skinChrome().shapes
    return when (style) {
        AlertStyle.PILL -> if (alertSquare) shapes.small else DashShape.Pill
        AlertStyle.CARD, AlertStyle.FULL -> shapes.large
        AlertStyle.BANNER -> shapes.medium
        AlertStyle.PANEL -> RectangleShape
        AlertStyle.BUBBLE, AlertStyle.ICON -> CircleShape
    }
}

private fun elevationOf(style: AlertStyle): Dp = when (style) {
    AlertStyle.PILL -> 6.dp
    AlertStyle.CARD -> 10.dp
    AlertStyle.BANNER -> 8.dp
    AlertStyle.PANEL -> 12.dp
    AlertStyle.FULL -> 16.dp
    AlertStyle.BUBBLE -> 10.dp
    AlertStyle.ICON -> 0.dp
}

/**
 * An alert's surface in [style], dressed in the active look. [tone] is the
 * alert's own colour (amber for the doors, red for the belt...): the pill's
 * rim, and the light of the glowing finishes. A banner spans the top with a
 * margin and a panel fills its window; the surface holds its touches, so
 * nothing behind it is pressed through it.
 */
@Composable
internal fun AlertSurface(
    style: AlertStyle,
    modifier: Modifier = Modifier,
    tone: Color = DashColors.Accent,
    content: @Composable () -> Unit
) {
    val finish = alertFinish
    val shape = alertShape(style)
    val card = DashColors.Card.copy(alpha = 1f)
    val fill: Brush = when (finish) {
        AlertFinish.CHROME -> Brush.verticalGradient(listOf(DashColors.CardHi.copy(alpha = 1f), card))
        AlertFinish.SCENE -> Brush.verticalGradient(listOf(card, lerp(card, DashColors.Background, 0.7f)))
        AlertFinish.NEON -> SolidColor(TapeDeckGlass)
        AlertFinish.SMOKED -> SolidColor(DashColors.Card.copy(alpha = 0.94f))
        else -> SolidColor(card)
    }
    // The side panel's edge is drawn with the finish's lights instead (only its inner side shows).
    val rim: Brush? = when {
        style == AlertStyle.PANEL -> null
        finish == AlertFinish.CHROME -> Brush.linearGradient(*CockpitChromeStops)
        finish == AlertFinish.HALO -> Brush.linearGradient(listOf(DashColors.Accent, DashColors.Accent2))
        finish == AlertFinish.NEON || finish == AlertFinish.LIT -> SolidColor(tone)
        style == AlertStyle.PILL -> SolidColor(tone.copy(alpha = 0.6f))
        else -> SolidColor(DashColors.Line)
    }
    val rimWidth = when (finish) {
        AlertFinish.CHROME -> 3.dp
        AlertFinish.NEON, AlertFinish.HALO -> 1.5.dp
        else -> 1.dp
    }
    // Glowing looks cast their own colour at night instead of a shadow.
    val glows = !DashColors.Light && (finish == AlertFinish.NEON || finish == AlertFinish.LIT || finish == AlertFinish.HALO)
    val cast = if (glows) tone else DefaultShadowColor
    val size = when (style) {
        AlertStyle.BANNER -> Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        AlertStyle.PANEL -> Modifier.fillMaxSize()
        else -> Modifier
    }
    Box(
        // The caller's taps inside the clip, so their ripple keeps the corners.
        modifier = size
            .shadow(elevationOf(style), shape, ambientColor = cast, spotColor = cast)
            .clip(shape)
            .then(modifier)
            .background(fill)
            .finishLights(finish, style, shape, tone)
            .then(if (rim != null) Modifier.border(rimWidth, rim, shape) else Modifier)
            .pointerInput(Unit) {},
        propagateMinConstraints = true
    ) {
        CompositionLocalProvider(LocalContentColor provides DashColors.TextPrimary) {
            MaterialTheme(typography = alertTypography(finish)) { content() }
        }
    }
}

/** The short bar that leads a banner, in the alert's colour and the look's corners. */
@Composable
internal fun AlertStripe(tone: Color) {
    val shape = if (alertSquare) skinChrome().shapes.small else DashShape.Pill
    val neon = alertFinish == AlertFinish.NEON && !DashColors.Light
    Box(
        Modifier.width(4.dp).height(40.dp)
            .then(if (neon) Modifier.shadow(6.dp, shape, ambientColor = tone, spotColor = tone) else Modifier)
            .background(tone, shape)
    )
}

/** The big type in the look's face: condensed numerals, engraved caps, display lettering. */
@Composable
private fun alertTypography(finish: AlertFinish): Typography {
    val type = MaterialTheme.typography
    val condensed = DashColors.Font == DashFont.CONDENSED
    return remember(type, finish, condensed) {
        var t = type
        // Only the big type: the condensed cut has one weight, and smaller titles would lose theirs.
        if (condensed) t = t.copy(
            displaySmall = t.displaySmall.copy(fontFamily = CondensedFamily),
            headlineMedium = t.headlineMedium.copy(fontFamily = CondensedFamily),
            headlineSmall = t.headlineSmall.copy(fontFamily = CondensedFamily)
        )
        when (finish) {
            // The dash's engraved caps.
            AlertFinish.CHROME -> t.copy(labelLarge = t.labelLarge.copy(fontFamily = CondensedFamily, letterSpacing = 0.18.em))
            // The deck's display lettering.
            AlertFinish.NEON -> t.copy(
                labelLarge = t.labelLarge.copy(fontFamily = FontFamily.Monospace),
                displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Monospace),
                headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Monospace)
            )
            else -> t
        }
    }
}

/**
 * What each finish draws over the fill: the glass sheen and its catch of
 * light, a lit top edge, an inner glow, the chrome's inner shadow, the
 * sunset line. A side panel gets its finish's edge along its inner side.
 */
private fun Modifier.finishLights(finish: AlertFinish, style: AlertStyle, shape: Shape, tone: Color): Modifier {
    val light = DashColors.Light
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val line = DashColors.Line
    return drawWithCache {
        val w = size.width
        val h = size.height
        val outline = shape.createOutline(size, layoutDirection, this)
        val catch = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = if (light) 0.9f else 0.35f), Color.Transparent),
            startX = w * 0.12f, endX = w * 0.88f
        )
        val sheen = if (light) {
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent, accent.copy(alpha = 0.05f)))
        } else {
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f), accent.copy(alpha = 0.08f)))
        }
        val wash = Brush.verticalGradient(listOf(tone.copy(alpha = if (light) 0.06f else 0.12f), Color.Transparent), endY = h * 0.5f)
        val topEdge = Brush.horizontalGradient(listOf(accent, accent2))
        val halo = Brush.radialGradient(
            listOf(tone.copy(alpha = if (light) 0.08f else 0.16f), Color.Transparent),
            center = Offset(w / 2f, 0f), radius = maxOf(w, h) * 0.7f
        )
        val sunset = Brush.horizontalGradient(listOf(Color.Transparent, accent2, Color.Transparent))
        val sunGlow = Brush.radialGradient(
            listOf(accent2.copy(alpha = if (light) 0.10f else 0.18f), Color.Transparent),
            center = Offset(w / 2f, h), radius = maxOf(w * 0.6f, 1f)
        )
        // A panel's edge: across its inner side, which is the start in either direction.
        val edgeW = when (finish) {
            AlertFinish.CHROME -> 4.dp.toPx()
            AlertFinish.NEON, AlertFinish.HALO, AlertFinish.LIT -> 2.dp.toPx()
            else -> 1.dp.toPx()
        }
        val ltr = layoutDirection == LayoutDirection.Ltr
        val edgeX = if (ltr) 0f else w - edgeW
        val glowX = if (ltr) 0f else w - edgeW * 4f
        val edge: Brush = when (finish) {
            AlertFinish.CHROME -> Brush.verticalGradient(*CockpitChromeStops)
            AlertFinish.HALO -> Brush.verticalGradient(listOf(accent, accent2))
            AlertFinish.NEON, AlertFinish.LIT -> SolidColor(tone)
            else -> SolidColor(line)
        }
        onDrawWithContent {
            when (finish) {
                AlertFinish.GLASS -> drawRect(sheen)
                AlertFinish.LIT -> drawRect(wash)
                AlertFinish.HALO -> drawRect(halo)
                AlertFinish.SCENE -> drawRect(sunGlow)
                // The tube's light on the inside of the glass (the outer half is clipped away);
                // a side panel is lit from its inner edge only.
                AlertFinish.NEON -> if (style != AlertStyle.PANEL) {
                    drawOutline(outline, tone.copy(alpha = if (light) 0.10f else 0.18f), style = Stroke(12.dp.toPx()))
                    drawOutline(outline, tone.copy(alpha = if (light) 0.16f else 0.30f), style = Stroke(4.dp.toPx()))
                }
                // The bezel's shadow on the leather.
                AlertFinish.CHROME -> if (style != AlertStyle.PANEL) {
                    drawOutline(outline, Color.Black.copy(alpha = if (light) 0.14f else 0.45f), style = Stroke(12.dp.toPx()))
                }
                else -> Unit
            }
            drawContent()
            when (finish) {
                AlertFinish.GLASS, AlertFinish.SMOKED -> drawLine(catch, Offset(w * 0.12f, 1f), Offset(w * 0.88f, 1f), 1.5f)
                AlertFinish.LIT -> if (style != AlertStyle.PANEL) drawRect(topEdge, size = Size(w, 3.dp.toPx()))
                AlertFinish.SCENE -> drawRect(sunset, Offset(w * 0.1f, h - 2.dp.toPx()), Size(w * 0.8f, 2.dp.toPx()))
                else -> Unit
            }
            if (style == AlertStyle.PANEL) {
                if (finish == AlertFinish.NEON && !light) drawRect(tone.copy(alpha = 0.25f), Offset(glowX, 0f), Size(edgeW * 4f, h))
                drawRect(edge, Offset(edgeX, 0f), Size(edgeW, h))
            }
        }
    }
}
