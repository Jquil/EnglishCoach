package com.englishcoach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------- HyperOS 设计规范色板（视觉按小米 HyperOS，交互按 Material 3） ----------
val HyperBlue = Color(0xFF3482FF)        // 主交互色
val HyperBluePressed = Color(0xFF2A6BE0) // 按压加深
val XiaomiOrange = Color(0xFFFF6900)     // 圆环 / 火苗
val PageBg = Color(0xFFF4F5F7)           // 页面底色
val NavBg = Color(0xFFF0F1F4)            // 底部导航底色
val CardWhite = Color(0xFFFFFFFF)        // 卡片 / AI 气泡
val TextPrimary = Color(0xFF0E0E12)      // 主文字
val TextSecondary = Color(0x8C0E0E12)    // 次文字（55% 黑）
val ErrorRed = Color(0xFFE5484D)         // 错误（复盘）
val CorrectGreen = Color(0xFF43CF7C)     // 正确说法
val CorrectionBarBg = Color(0xFFFFF6E0)  // 黄色纠错条

/** MIUI 彩色系（场景图标方块轮换用） */
val MiuiIconColors = listOf(
    Color(0xFFFF8F1F), // MIUI 橙
    Color(0xFF43CF7C), // MIUI 绿
    Color(0xFF6A5BFF), // MIUI 紫
    Color(0xFF2FBFBE)  // MIUI 青
)

/**
 * 字体：MiSans 是小米系统自带字体，FontFamily.SansSerif 在小米设备上会落到 MiSans，
 * 其他设备落到系统黑体——与设计规范"MiSans → fallback 系统黑体"一致，且不内置字体文件以减小 APK。
 * （MVP 自用不上架，不做深色模式视觉适配，锁定浅色。）
 */
private val LightColors = lightColorScheme(
    primary = HyperBlue,
    onPrimary = Color.White,
    secondary = XiaomiOrange,
    onSecondary = Color.White,
    background = PageBg,
    onBackground = TextPrimary,
    surface = CardWhite,
    onSurface = TextPrimary,
    surfaceVariant = PageBg,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    outline = Color(0x14000000)
)

private val DarkColors = darkColorScheme(
    primary = HyperBlue,
    background = Color(0xFF17171A),
    surface = Color(0xFF1F1F23),
    onSurface = Color(0xFFECECEE)
)

/** 字号阶梯（36/20/17/15/13/11，按规范速查） */
private val AppTypography = Typography(
    displayLarge = TextStyle( // 大标题 36 Bold -.5px
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle( // 卡片标题 / 模式标签 20 Demibold
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle( // 行标题
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp
    ),
    bodyLarge = TextStyle( // 正文 17
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp
    ),
    bodyMedium = TextStyle( // 辅助说明 15
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    ),
    bodySmall = TextStyle( // 注脚 / 日历 / 分组头 13
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    ),
    labelSmall = TextStyle( // 气泡时间戳 / Tab 11-12
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    )
)

@Composable
fun EnglishCoachTheme(
    darkTheme: Boolean = false, // MVP 锁浅色（自用），深色留 P1
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
