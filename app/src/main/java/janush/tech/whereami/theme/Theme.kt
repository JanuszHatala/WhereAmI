package janush.tech.whereami.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val WhereAmIShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

val AppButtonShape = RoundedCornerShape(10.dp)


private val DarkColorScheme = darkColorScheme(
    primary = TacticalSky,
    onPrimary = Color.White,
    secondary = TacticalEmerald,
    onSecondary = Color.White,
    tertiary = TacticalAmber,
    onTertiary = Color.White,
    background = TacticalNavy,
    onBackground = Color.White,
    surface = TacticalSlate,
    onSurface = Color.White,
    surfaceVariant = TacticalSlateLight,
    onSurfaceVariant = Color.White,
    error = TacticalRed,
    onError = Color.White
)

private val LightColorScheme = DarkColorScheme // WhereAmI is an outdoor tactical dark UI

/**
 * Tactical button color presets enforcing crisp white contrast on interactive buttons
 * (docs/DESIGN_SYSTEM.md Section 6).
 */
object AppButtonDefaults {
    @Composable
    fun primaryColors(containerColor: Color = TacticalSky): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )

    @Composable
    fun successColors(containerColor: Color = TacticalEmerald): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )

    @Composable
    fun warningColors(containerColor: Color = TacticalAmber): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )

    @Composable
    fun dangerColors(containerColor: Color = TacticalRed): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )

    @Composable
    fun secondaryColors(containerColor: Color = TacticalSlateLight): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )
}

@Composable
fun WhereAmITheme(
  darkTheme: Boolean = true,
  // Dynamic color is disabled by default to preserve tactical contrast
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = WhereAmIShapes,
    content = content
  )
}

@Composable
fun WhereIAmTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) = WhereAmITheme(darkTheme, dynamicColor, content)


