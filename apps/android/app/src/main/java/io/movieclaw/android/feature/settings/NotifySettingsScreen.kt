package io.movieclaw.android.feature.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.model.PushRegistrationBody
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.notify.NotifyPrefs
import io.movieclaw.android.core.notify.NotifyScheduler
import io.movieclaw.android.core.push.BackgroundGuide
import io.movieclaw.android.core.push.PushStreamController
import io.movieclaw.android.core.push.PushStreamKeepAlive
import io.movieclaw.android.core.push.PushStreamPrefs
import io.movieclaw.android.core.push.PushStreamState
import io.movieclaw.android.core.push.PushStreamStatus
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.app.Application
import retrofit2.HttpException

/**
 * 「通知」设置（iOS 是「账号 → 通知」，但那条链路走 APNs + 服务端云中继；
 * 本项目**不动服务端**，所以这里是纯客户端实现：系统本机每 15 分钟检查一次
 * 下载 / 入库 / 今日预告的变化，只在状态真的翻转时发本地通知）。
 *
 * 界面上如实写明机制与代价（最长 15 分钟延迟、依赖系统调度、不做服务端推送），
 * 4 个开关对应 4 条通知渠道（下载 / 入库 / 订阅更新 / 系统与任务）。
 *
 * 另有「实时通知（保持连接）」（core/push）：前台服务一直连着自己的服务器，服务器的通知即时送达，
 * 不依赖谷歌 / 厂商推送；代价是一条常驻的静音通知和多一点耗电，界面上同样如实写明。
 */
data class NotifySettingsState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val download: Boolean = true,
    val arrival: Boolean = true,
    val subscription: Boolean = true,
    val task: Boolean = true,
    /** 实时通知（保持连接） */
    val stream: Boolean = false,
    val streamBusy: Boolean = false,
    val testing: Boolean = false,
    /** 开关 / 测试通知的结果提示 */
    val streamMessage: String? = null,
    /** 刚开启：弹出后台运行引导 */
    val showGuide: Boolean = false,
)

@HiltViewModel
class NotifySettingsViewModel @Inject constructor(
    private val app: Application,
    private val session: SessionRepository,
    private val apiFactory: ApiFactory,
) : ViewModel() {
    private val prefs = NotifyPrefs(app)
    private val streamPrefs = PushStreamPrefs(app)
    private val _ui = MutableStateFlow(NotifySettingsState())
    val ui = _ui.asStateFlow()
    val streamStatus = PushStreamState.status

    init {
        viewModelScope.launch {
            val s = prefs.read()
            _ui.value = NotifySettingsState(
                loaded = true,
                enabled = s.enabled,
                download = s.download,
                arrival = s.arrival,
                subscription = s.subscription,
                task = s.task,
                stream = streamPrefs.enabled(),
            )
            // 已开启却没排程（重装 / 清数据 / 系统撤销）时补上
            if (s.enabled) NotifyScheduler.schedule(app)
            PushStreamController.startIfEnabled(app)
        }
    }

    /** 当前账号的 API（没登录时 null） */
    private fun api() = session.ui.value.origin?.let { origin ->
        session.requestIdentity(origin)?.let { apiFactory.forIdentity(origin, it) }
    }

    /**
     * 开：先在服务器登记成实时通知设备（顺带确认服务器版本支持），再起前台服务、弹后台引导。
     * 关：停服务，告诉服务器不再写发件箱（失败也照样关，服务器那边最多攒几条用不上的）。
     */
    fun setStream(on: Boolean) {
        if (_ui.value.streamBusy) return
        _ui.value = _ui.value.copy(streamBusy = true, streamMessage = null)
        viewModelScope.launch {
            val api = api()
            if (on) {
                val failure = when {
                    api == null -> "请先登录账号"
                    else -> runCatching {
                        val permission = if (NotificationManagerCompat.from(app).areNotificationsEnabled()) "authorized" else "denied"
                        api.pushRegister(PushRegistrationBody(permission = permission, stream = true)).dataOrThrow()
                    }.fold(
                        onSuccess = { view ->
                            if (view.channelName == PushStreamState.STREAM_CHANNEL) null
                            else "服务器版本不支持实时通知，请先更新 MovieClaw 服务器"
                        },
                        onFailure = { serverMessage(it) },
                    )
                }
                if (failure != null) {
                    _ui.value = _ui.value.copy(streamBusy = false, stream = false, streamMessage = failure)
                    return@launch
                }
                streamPrefs.setEnabled(true)
                PushStreamKeepAlive.schedule(app)
                PushStreamController.start(app)
                _ui.value = _ui.value.copy(streamBusy = false, stream = true, showGuide = true)
            } else {
                streamPrefs.setEnabled(false)
                PushStreamKeepAlive.cancel(app)
                PushStreamController.stop(app)
                _ui.value = _ui.value.copy(streamBusy = false, stream = false)
                api?.let { runCatching { it.pushRegister(PushRegistrationBody(permission = "authorized", stream = false)) } }
            }
        }
    }

    fun sendTest() {
        if (_ui.value.testing) return
        _ui.value = _ui.value.copy(testing = true, streamMessage = null)
        viewModelScope.launch {
            val api = api()
            val message = if (api == null) "请先登录账号" else runCatching { api.pushTest().dataOrThrow() }.fold(
                onSuccess = { view ->
                    if (view.results.isEmpty()) "这个账号还没有能收通知的设备"
                    else view.results.joinToString("\n") { r ->
                        "${r.deviceName}：${r.message.ifBlank { r.result }}"
                    }
                },
                onFailure = { serverMessage(it) },
            )
            _ui.value = _ui.value.copy(testing = false, streamMessage = message)
        }
    }

    fun dismissGuide() {
        _ui.value = _ui.value.copy(showGuide = false)
    }

    fun openGuide() {
        _ui.value = _ui.value.copy(showGuide = true)
    }

    /** 服务器的中文报错（如「刚发过一条测试通知，请稍等几秒再试」）优先 */
    private fun serverMessage(e: Throwable): String {
        if (e is HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val message = body?.let { Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            if (!message.isNullOrBlank()) return message
            if (e.code() == 404) return "服务器版本不支持实时通知，请先更新 MovieClaw 服务器"
        }
        return friendlyMessage(e)
    }

    fun setEnabled(on: Boolean) {
        _ui.value = _ui.value.copy(enabled = on)
        viewModelScope.launch {
            prefs.setEnabled(on)
            if (on) NotifyScheduler.schedule(app) else NotifyScheduler.cancel(app)
        }
        // 首轮基线交给 Worker 自己落：开启后第一次只记状态不轰炸
    }

    fun setChannels(download: Boolean, arrival: Boolean, subscription: Boolean, task: Boolean) {
        _ui.value = _ui.value.copy(
            download = download, arrival = arrival, subscription = subscription, task = task,
        )
        viewModelScope.launch { prefs.setChannel(download, arrival, subscription, task) }
    }
}

@Composable
fun NotifySettingsScreen(
    onBack: () -> Unit,
    vm: NotifySettingsViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 用户拒了权限就保持开关不变（下次点开还会再问一次），并在下面那行提示里说明
        if (!granted) vm.setEnabled(false)
    }

    val streamPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // 没给通知权限也照样开：连接保持着，服务器在「我的设备」里提示「系统通知已关闭」
        vm.setStream(true)
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(title = "通知", onBack = onBack) { }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            NotifyRow(
                title = "开启通知",
                subtitle = "在本机检查下载、入库与今日更新，有变化时提醒",
                checked = state.enabled,
                onChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        !NotificationManagerCompat.from(context).areNotificationsEnabled()
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setEnabled(on)
                    }
                },
            )
            if (state.enabled) {
                NotifyRow(
                    title = "下载",
                    subtitle = "下载完成或失败时",
                    checked = state.download,
                    onChange = { vm.setChannels(it, state.arrival, state.subscription, state.task) },
                )
                NotifyRow(
                    title = "入库",
                    subtitle = "整理完成、新内容进库时",
                    checked = state.arrival,
                    onChange = { vm.setChannels(state.download, it, state.subscription, state.task) },
                )
                NotifyRow(
                    title = "订阅更新",
                    subtitle = "订阅的作品今天有可看的时",
                    checked = state.subscription,
                    onChange = { vm.setChannels(state.download, state.arrival, it, state.task) },
                )
                NotifyRow(
                    title = "系统与任务",
                    subtitle = "下载失败等需要处理的提醒",
                    checked = state.task,
                    onChange = { vm.setChannels(state.download, state.arrival, state.subscription, it) },
                )
            }
            Spacer(Modifier.height(18.dp))
            StreamSection(vm = vm, state = state, onEnable = { on ->
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    !NotificationManagerCompat.from(context).areNotificationsEnabled()
                ) {
                    streamPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.setStream(on)
                }
            })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showGuide) {
        BackgroundGuideDialog(onDismiss = vm::dismissGuide)
    }
}

/** 实时通知（保持连接）：开关、连接状态、测试通知、后台运行引导 */
@Composable
private fun StreamSection(
    vm: NotifySettingsViewModel,
    state: NotifySettingsState,
    onEnable: (Boolean) -> Unit,
) {
    val status by vm.streamStatus.collectAsState()
    NotifyRow(
        title = "实时通知（保持连接）",
        subtitle = "一直连着你的 MovieClaw 服务器，入库、下载、系统提醒即时送达，不经过谷歌或手机厂商的推送。" +
            "需要常驻一条静音通知，会多用一点电；断网后自动重连，期间的通知连上后补发。",
        checked = state.stream,
        onChange = { if (!state.streamBusy) onEnable(it) },
    )
    if (state.stream) {
        val (dot, text) = when (val s = status) {
            is PushStreamStatus.Connected -> Accent to "已连接"
            PushStreamStatus.Connecting -> TextMuted to "正在连接…"
            is PushStreamStatus.Retrying -> TextMuted to "未连接：${s.reason}，会自动重试"
            PushStreamStatus.Off -> TextMuted to "未运行（可能被系统清理了，按下方引导放行后台运行）"
        }
        Text(
            text,
            style = McType.caption,
            color = dot,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = vm::sendTest, enabled = !state.testing) {
                Text(if (state.testing) "发送中…" else "发送测试通知", color = Accent)
            }
            TextButton(onClick = vm::openGuide) { Text("后台运行设置", color = Accent) }
        }
    }
    state.streamMessage?.let {
        Text(
            it,
            style = McType.caption,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** 后台运行引导：国产系统要手动放行，否则熄屏后连接会被断开 */
@Composable
private fun BackgroundGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(BackgroundGuide.ignoringBatteryOptimizations(context)) }
    // 从系统弹窗 / 设置页回来时刷新「已允许」
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        ignoring = BackgroundGuide.ignoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("让通知保持连接") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (BackgroundGuide.isVivo()) "vivo / iQOO 默认会清理后台应用，请依次放行下面几项：" else "很多手机默认会清理后台应用，请依次放行下面几项：",
                    style = McType.caption,
                    color = TextMuted,
                )
                BackgroundGuide.steps().forEachIndexed { index, step ->
                    Spacer(Modifier.height(8.dp))
                    Text("${index + 1}. ${step.title}", style = McType.subheadline)
                    Text(
                        if (index == 0 && ignoring) "已允许" else step.detail,
                        style = McType.caption,
                        color = TextFaint,
                    )
                }
            }
        },
        confirmButton = {
            Column {
                if (!ignoring) {
                    TextButton(onClick = {
                        BackgroundGuide.requestIgnoreBatteryOptimizations(context)
                        ignoring = BackgroundGuide.ignoringBatteryOptimizations(context)
                    }) { Text("忽略电池优化", color = Accent) }
                }
                TextButton(onClick = { BackgroundGuide.openVendorBackgroundSettings(context) }) {
                    Text("打开自启动 / 后台设置", color = Accent)
                }
                TextButton(onClick = { BackgroundGuide.openAppDetails(context) }) {
                    Text("打开应用详情", color = Accent)
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
    )
}

@Composable
private fun NotifyRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = McType.subheadline)
            Text(subtitle, style = McType.caption, color = TextFaint)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}
