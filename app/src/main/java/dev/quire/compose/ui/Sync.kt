package dev.quire.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.SyncLogRow
import dev.quire.compose.bridge.SyncRow
import dev.quire.compose.bridge.SyncState

/**
 * 同步: this device, the devices it can see, and the two verbs that matter.
 *
 * Its own destination rather than a section of Settings, because that is what it
 * is in the app this shell is ported from — a page with a device list, a status
 * banner and its own settings — and because a global setting is a row on a screen
 * nobody visits, while "is my phone talking to my laptop" is a question asked by
 * looking.
 *
 * **Opening the page is what puts this device on the LAN.** The session starts
 * the engine on the first request, so nothing listens and no threads exist until
 * this screen has been shown; that is also why the banner says what it says.
 *
 * The badge on each row, the pairing verbs and the interval presets are the
 * reference app's; the protocol underneath is `quire-core`'s own, which is
 * smaller than that app's (no cloud sync, no pairing codes, no per-device
 * statistics), so this page is that app's page narrowed to what this shell can
 * actually do — and it says so at the bottom rather than pretending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncBar(vm: QuireViewModel, onOpenDrawer: () -> Unit) {
    val colors = LocalQuireColors.current
    val sync = vm.view?.sync ?: SyncState.Empty
    TopAppBar(
        title = {
            Text(
                text = "同步",
                style = QuireType.ui.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "页面列表", tint = colors.textSecondary)
            }
        },
        actions = {
            if (sync.busy) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            // The bar carries no refresh button: the round the button asked for
            // is a pull away — the page is a list like 笔记 and 任务, and the
            // same pull-to-refresh hangs off it. What is left here is the
            // spinner, which says "it is working" while a round runs.
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.textPrimary,
        ),
    )
}

@Composable
fun SyncPage(vm: QuireViewModel) {
    val sync = vm.view?.sync ?: SyncState.Empty

    // Already running: the app sent `syncOpen` at launch (ADR-0032). This is the
    // page's own read, and it pumps the engine — so it is not merely opening a
    // screen any more, only refreshing what the app has been doing since the
    // user arrived. Idempotent on the Rust side either way.
    LaunchedEffect(Unit) { vm.openSync() }

    var name by remember(sync.selfName) { mutableStateOf(sync.selfName) }

    // The pull is the page's one refresh verb now that the bar has no button:
    // the same gesture 笔记 and 任务 give, asking for the page's own read again
    // — a burst of discovery and the peers table re-read (idempotent on the
    // Rust side, like the LaunchedEffect above). While a round runs, `busy`
    // holds the gesture off rather than queueing a second one.
    val listState = rememberLazyListState()
    QuirePullRefresh(
        state = listState,
        busy = sync.busy,
        onRefresh = vm::openSync,
        modifier = Modifier.fillMaxSize().imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item(key = "banner") { SyncBanner(sync) }
            sync.unsyncable?.let { why ->
                item(key = "gate") { SyncGate(why) }
            }
            item(key = "self") { SyncSelf(sync) }

            item(key = "peers-header") { SyncSection("本网络上的设备") }
            if (sync.devices.isEmpty()) {
                item(key = "peers-empty") {
                    SyncEmpty("没听到别的设备 —— 打开另一台设备就会自动同步，不需要任何设置。")
                }
            }
            items(sync.devices, key = { "device-${it.id}" }) { row -> SyncDevice(row = row, vm = vm) }

            item(key = "settings") {
                SyncSettings(
                    sync = sync,
                    name = name,
                    onName = { name = it },
                    vm = vm,
                )
            }

            item(key = "log-header") { SyncSection("最近记录") }
            if (sync.log.isEmpty()) {
                item(key = "log-empty") { SyncEmpty("还没有记录。") }
            }
            // Newest first, and a handful of them: the log is a reassurance, not a
            // console (the reference app has a whole page for that).
            itemsIndexed(sync.log.asReversed().take(12), key = { index, _ -> "log-$index" }) { _, line ->
                SyncLogLine(line)
            }
            item(key = "foot") { SyncFoot() }
        }
    }
}

@Composable
private fun SyncBanner(sync: SyncState) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (sync.running) colors.accent else colors.textMuted),
        )
        Text(
            text = if (sync.running) {
                "广播发现运行中 —— 同一局域网内的设备会自动出现在下面（UDP ${sync.discoveryPort} / HTTP ${sync.port}）"
            } else {
                "还没启动 —— 打开应用即会自动开始，无需任何设置"
            },
            style = QuireType.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
    if (sync.status.isNotEmpty()) {
        Text(
            text = sync.status,
            style = QuireType.caption,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
}

/**
 * The one thing this build refuses to do, said before it can be tried.
 *
 * A library whose own folder holds attachment rows cannot be carried by this shell:
 * there is no way for it to send the bytes, and a snapshot that named a row with no
 * file behind it would be renumbered out from under the blocks that point at it — so
 * the engine is not started at all and the reason is on screen. A database does not
 * stop anything: its rows never enter this device's snapshot and an inbound peer's
 * rows are dropped at the door, which is the same silence both ends already agree on.
 */
@Composable
private fun SyncGate(why: String) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.calloutBackground)
            .border(1.dp, colors.danger.copy(alpha = 0.4f), RoundedCornerShape(Radius.sm))
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("这个资料库暂时不能同步", style = QuireType.ui, color = colors.danger)
        Text(why, style = QuireType.caption, color = colors.textSecondary)
    }
}

@Composable
private fun SyncSelf(sync: SyncState) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("本机", style = QuireType.caption, color = colors.textMuted)
        Text(
            text = sync.selfName.ifEmpty { "（未命名）" },
            style = QuireType.ui.copy(fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
        )
        Text(
            text = listOf(sync.selfAddress.ifEmpty { "未获取到局域网地址（检查 Wi-Fi）" }, "ID: ${sync.selfId}")
                .joinToString("  ·  "),
            style = QuireType.caption,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SyncSection(label: String) {
    val colors = LocalQuireColors.current
    Text(
        text = label,
        style = QuireType.caption,
        color = colors.textMuted,
        modifier = Modifier.padding(start = Spacing.lg, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SyncEmpty(text: String) {
    val colors = LocalQuireColors.current
    Text(
        text = text,
        style = QuireType.caption,
        color = colors.textMuted,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )
}

/** One device: what it is, where it is, and the verbs that fit its state. */
@Composable
private fun SyncDevice(row: SyncRow, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .padding(start = Spacing.md, end = 4.dp, top = Spacing.sm, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = row.name.ifEmpty { "（未命名设备）" },
                style = QuireType.ui.copy(fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SyncBadge(
                label = if (row.online) "在线" else "离线",
                tint = if (row.online) colors.accentText else colors.textMuted,
            )
            SyncBadge(label = kindLabel(row.kind), tint = colors.textMuted)
        }
        Text(
            // The address and the last round, and nothing about pairing: a device
            // on this list is synced with by definition now (ADR-0029), so a
            // "已配对" badge on every row said nothing and 发起配对 on some rows
            // said the opposite.
            text = listOf(
                row.address.ifEmpty { "没有地址" },
                if (row.lastSync.isEmpty()) "还没同步过" else "上次同步 ${row.lastSync}",
            ).joinToString("  ·  "),
            style = QuireType.caption,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.syncNow(row.id) }) { Text("立即同步", color = colors.accentText) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.syncForget(row.id) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "不再与这台设备同步",
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncBadge(label: String, tint: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = QuireType.caption,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        maxLines = 1,
    )
}

/** 设置: the timer, and what this device is called on the wire. */
@Composable
private fun SyncSettings(sync: SyncState, name: String, onName: (String) -> Unit, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SyncSection("设置")

        Text("自动同步", style = QuireType.caption, color = colors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            // The presets are what to *ask for*; the line under them says what
            // the timer is actually doing, which on Wi-Fi is a faster number
            // than any of these (ADR-0032). Without that line a chip reading
            // 「30 分」 over a 10-second timer is the page contradicting itself.
            for ((label, seconds) in listOf("10 秒" to 10L, "1 分" to 60L, "5 分" to 300L, "30 分" to 1800L)) {
                SyncChoice(
                    label = label,
                    selected = sync.auto && sync.interval == seconds,
                    onClick = {
                        vm.syncSetInterval(seconds)
                        vm.syncSetAuto(true)
                    },
                )
            }
            SyncChoice(
                label = "仅手动",
                selected = !sync.auto,
                onClick = { vm.syncSetAuto(false) },
            )
        }

        // What the timer is doing, not what was asked for. On Wi-Fi the answer
        // is the desktop's 10 秒 and the chips above are a ceiling rather than
        // the number in force; off Wi-Fi it is the chosen interval, and the
        // reason it is not the aggressive one is the sentence's whole job.
        val showingInterval = sync.effectiveInterval != sync.interval
        if (sync.auto && (showingInterval || !sync.onWifi)) {
            Text(
                text = if (sync.onWifi) {
                    "已在 Wi-Fi 下，实际每 ${sync.effectiveInterval} 秒同步一次"
                } else {
                    "未连接 Wi-Fi，按所选间隔每 ${sync.effectiveInterval} 秒同步一次"
                },
                style = QuireType.caption,
                color = colors.textMuted,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                singleLine = true,
                label = { Text("本机别名", style = QuireType.caption) },
                textStyle = QuireType.ui,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.syncSetName(name) }, enabled = name != sync.selfName) { Text("保存") }
        }
    }
}

@Composable
private fun SyncChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) colors.surfaceSelected else colors.card)
            .border(1.dp, if (selected) colors.accent else colors.border, RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = QuireType.caption,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SyncLogLine(line: SyncLogRow) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = if (line.ok) "✓" else "✕",
            style = QuireType.caption,
            color = if (line.ok) colors.accentText else colors.danger,
        )
        Text(
            text = line.at,
            style = QuireType.caption,
            color = colors.textMuted,
            maxLines = 1,
        )
        Text(
            text = if (line.peer.isEmpty()) line.message else "${line.peer}  ${line.message}",
            style = QuireType.caption,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What crosses, and what does not.
 *
 * Not a disclaimer for its own sake: the page has to say where a table is edited,
 * because the rows a user cannot see on this screen are the ones the other device
 * keeps to itself. They stay out of both directions on purpose — see the module
 * note in the bridge's `sync.rs`.
 */
@Composable
private fun SyncFoot() {
    val colors = LocalQuireColors.current
    Text(
        text = "同步搬运：页面、正文、笔记与任务，以及表格本身。" +
            "表格的内容与图片附件不在本机同步：表格请回桌面端编辑，" +
            "本机资料库里存有附件时不会同步。",
        style = QuireType.caption,
        color = colors.textMuted,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

/** A device kind as the reader's word for it. */
private fun kindLabel(kind: String): String = when (kind) {
    "android" -> "安卓"
    "windows" -> "Windows"
    "macos" -> "macOS"
    "linux" -> "Linux"
    else -> "其他"
}
