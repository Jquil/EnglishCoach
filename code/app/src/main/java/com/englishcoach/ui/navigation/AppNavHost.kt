package com.englishcoach.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.englishcoach.ui.chat.ChatScreen
import com.englishcoach.ui.debug.DebugSpeechScreen
import com.englishcoach.ui.home.HomeScreen
import com.englishcoach.ui.nce.NceRepeatScreen
import com.englishcoach.ui.settings.ApiKeysSettingsScreen
import com.englishcoach.ui.settings.PronunciationSettingsScreen
import com.englishcoach.ui.settings.SettingsScreen
import com.englishcoach.ui.stats.StatsScreen
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.NavBg
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextSecondary
import com.englishcoach.ui.word.VocabularyScreen
import com.englishcoach.ui.word.WordRepeatScreen

/** 底部 Tab 定义（Material 3 Navigation Bar，视觉 HyperOS） */
private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector
)

// 4 Tab：口语（关卡练习）/ 单词（生词本占位）/ 统计 / 设置
private val bottomTabs = listOf(
    TabItem("home", "口语", Icons.Outlined.Home, Icons.Filled.Home),
    TabItem("word", "单词", Icons.Outlined.MenuBook, Icons.Filled.MenuBook),
    TabItem("stats", "统计", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    TabItem("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
)

private val topBarRoutes = bottomTabs.map { it.route }.toSet()

/**
 * 页面导航：4 个 Tab + 对话页 + 单词跟读页 + 调试页（从设置页进入）。
 * 对话/跟读页不带底栏（沉浸练习），返回用顶栏 ←。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = PageBg,
        bottomBar = {
            if (currentRoute in topBarRoutes) {
                NavigationBar(containerColor = NavBg) {
                    bottomTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.iconSelected else tab.icon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label, fontSize = 12.sp) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = HyperBlue,
                                selectedTextColor = HyperBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = HyperBlue.copy(alpha = 0.12f) // indicator pill 蓝 12% 底
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    // 纯 NCE 276 关轨道：卡点传统一关卡号（1..276）
                    // v10.4：滑动切词内嵌首页卡片，首页不再跳转单词跟读页（生词本页入口保留）
                    onOpenLesson = { lessonNo -> navController.navigate("nce_lesson/$lessonNo") }
                )
            }
            composable("word") {
                VocabularyScreen(
                    onGoPractice = {
                        navController.navigate("home") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // 快捷跟读（生词本页显式入口）：无指定单词 → WordRepeatViewModel 走 lastIndex 记忆
                    onOpenWordRepeat = { navController.navigate("repeat?word=") }
                )
            }
            composable("stats") { StatsScreen() }
            composable("settings") {
                SettingsScreen(
                    onOpenDebug = { navController.navigate("debug") },
                    // 二级页路由：无参数（无需转义），返回即回一级设置页
                    onOpenPronunciation = { navController.navigate("settings/pronunciation") },
                    onOpenKeys = { navController.navigate("settings/keys") }
                )
            }
            composable("settings/pronunciation") {
                PronunciationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/keys") {
                ApiKeysSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("debug") { DebugSpeechScreen() }
            composable("chat/{scenarioId}") { entry ->
                val scenarioId = entry.arguments?.getString("scenarioId").orEmpty()
                ChatScreen(
                    scenarioId = scenarioId,
                    onBack = { navController.popBackStack() }
                )
            }
            // 单词跟读页（可选 query 参数 word：带词 = 点谁开谁；不带 = lastIndex 记忆位置）
            composable(
                route = "repeat?word={word}",
                arguments = listOf(
                    androidx.navigation.navArgument("word") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                val word = android.net.Uri.decode(entry.arguments?.getString("word").orEmpty())
                WordRepeatScreen(
                    wordText = word,
                    onBack = { navController.popBackStack() }
                )
            }
            // NCE 逐句跟读页（沉浸练习，不带底栏）
            composable("nce_lesson/{no}") { entry ->
                val lessonNo = entry.arguments?.getString("no")?.toIntOrNull() ?: 1
                NceRepeatScreen(
                    lessonNo = lessonNo,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
