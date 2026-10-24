package com.englishcoach

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.englishcoach.notification.ReminderScheduler
import com.englishcoach.ui.navigation.AppNavHost
import com.englishcoach.ui.theme.EnglishCoachTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 唯一 Activity，承载全部 Compose UI */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EnglishCoachApp

        // 断签温和召回（StreakCalculator 内部保证"只发一次"）
        lifecycleScope.launch {
            ReminderScheduler.maybeNotifyRecall(this@MainActivity)
        }
        // 每日提醒排程（设置页改时间/开关时也会重排，这里保证启动时恢复）
        lifecycleScope.launch {
            val settings = app.settingsStore.settings.first()
            ReminderScheduler.scheduleDaily(this@MainActivity, settings.reminderTime, settings.reminderEnabled)
        }

        enableEdgeToEdge()
        setContent {
            EnglishCoachTheme {
                PermissionGate {
                    AppNavHost()
                }
            }
        }
    }
}

/**
 * 运行时权限申请（RECORD_AUDIO / POST_NOTIFICATIONS）。
 * 先用 Material 3 AlertDialog 说明用途，再弹系统授权框；拒绝也不崩溃（文字模式仍可用）。
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    var showExplanation by rememberSaveable { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 拒绝也不做惩罚：语音用不了时 UI 有提示，通知收不到而已
    }

    if (showExplanation) {
        val micGranted = ContextCompat.checkSelfPermission(
            androidx.compose.ui.platform.LocalContext.current, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // 只在还没授权时才解释（首次启动）
        if (!micGranted) {
            AlertDialog(
                onDismissRequest = { showExplanation = false },
                title = { Text("开启麦克风与通知") },
                text = {
                    Text(
                        "麦克风：用于识别你说的英语（核心功能）。\n" +
                            "通知：用于每日练习提醒和打卡召回。\n" +
                            "不授权也能用文字输入模式练习。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showExplanation = false
                        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        launcher.launch(permissions.toTypedArray())
                    }) { Text("允许", color = Color(0xFF3482FF)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExplanation = false }) { Text("暂不", color = Color(0x8C0E0E12)) }
                }
            )
        }
    }
    content()
}
