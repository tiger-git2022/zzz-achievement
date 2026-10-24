package com.local.stardbzzz.ui.glass

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect

/*
 * Color fringes at the edge of the drawer, the dialogs and the menus, as in EhViewer (GlassFringe.kt there). The
 * fringes of the library lens follow how far its edge bends the content, a few pixels for the gentle bend of a sheet,
 * and could not be seen. Here red, green and blue are taken a little apart across the edge, by [spread] at the edge,
 * fading over [band] into the glass.
 */
fun BackdropEffectScope.edgeFringe(band: Float, spread: Float) {
    // RuntimeShader is Android 13+; below that the glass keeps its blur and lens without fringes.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || band <= 0f || spread <= 0f) return
    val radii = cornerRadii() ?: return
    runtimeShaderEffect("EdgeFringe", EdgeFringeShader, "content") {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("cornerRadii", radii)
        setFloatUniform("band", band)
        setFloatUniform("spread", spread)
    }
}

/** The corner radii of the glass, top left, top right, bottom right, bottom left; null for another shape. */
private fun BackdropEffectScope.cornerRadii(): FloatArray? {
    val shape = shape as? CornerBasedShape ?: return null
    val max = size.minDimension / 2f
    val ltr = layoutDirection == LayoutDirection.Ltr
    fun radius(start: Boolean, top: Boolean): Float {
        val corner = when {
            top && start -> shape.topStart
            top -> shape.topEnd
            start -> shape.bottomStart
            else -> shape.bottomEnd
        }
        return corner.toPx(size, this).coerceIn(0f, max)
    }
    return floatArrayOf(radius(ltr, true), radius(!ltr, true), radius(!ltr, false), radius(ltr, false))
}

private const val EdgeFringeShader = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float band;
uniform float spread;

float radiusAt(float2 c, float4 r) {
    if (c.x >= 0.0) {
        if (c.y <= 0.0) return r.y;
        return r.z;
    }
    if (c.y <= 0.0) return r.x;
    return r.w;
}

float sdRoundedRect(float2 c, float2 halfSize, float radius) {
    float2 cornerCoord = abs(c) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 outward(float2 c, float2 halfSize, float radius) {
    float2 cornerCoord = abs(c) - (halfSize - float2(radius));
    if (cornerCoord.x > 0.0 && cornerCoord.y > 0.0) {
        return sign(c) * normalize(cornerCoord);
    }
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(c) * float2(gradX, 1.0 - gradX);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 c = (coord + offset) - halfSize;
    float radius = radiusAt(c, cornerRadii);
    float sd = sdRoundedRect(c, halfSize, radius);
    if (-sd >= band) {
        return content.eval(coord);
    }
    float t = 1.0 - clamp(-sd / band, 0.0, 1.0);
    float2 shift = outward(c, halfSize, min(radius * 1.5, min(halfSize.x, halfSize.y))) * spread * t * t;
    // Every sample lies inward, inside the glass.
    half4 red = content.eval(coord);
    half4 green = content.eval(coord - shift * 0.5);
    half4 blue = content.eval(coord - shift);
    return half4(red.r, green.g, blue.b, green.a);
}
"""
