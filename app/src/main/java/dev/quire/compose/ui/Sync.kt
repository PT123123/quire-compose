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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.KeyboardType
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
            // 刷新并立即同步 — the reference app's one toolbar action: a burst of
            // discovery, and a cycle with every peer that is due.
            IconButton(onClick = vm::openSync) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新并立即同步", tint = colors.textSecondary)
            }
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

    // Entering the page is what starts the engine and the announcements. It is
    // idempotent on the Rust side, so coming back to the page is free.
    LaunchedEffect(Unit) { vm.openSync() }

    var address by remember { mutableStateOf("") }
    var name by remember(sync.selfName) { mutableStateOf(sync.selfName) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item(key = "banner") { SyncBanner(sync) }
        sync.unsyncable?.let { why ->
            item(key = "gate") { SyncGate(why) }
        }
        item(key = "self") { SyncSelf(sync) }

        item(key = "peers-header") { SyncSection("已配对的设备") }
        if (sync.peers.isEmpty()) {
            item(key = "peers-empty") {
                SyncEmpty("还没有配对的设备 —— 和另一台设备互相配对之后，可以手动或按间隔自动同步。")
            }
        }
        items(sync.peers, key = { "peer-${it.id}" }) { row -> SyncDevice(row = row, vm = vm) }

        item(key = "found-header") { SyncSection("已发现的设备") }
        if (sync.discovered.isEmpty()) {
            item(key = "found-empty") {
                SyncEmpty("没听到别的设备 —— 确认两台设备都开着这一页，并且连在同一个局域网。")
            }
        }
        items(sync.discovered, key = { "found-${it.id}" }) { row -> SyncDevice(row = row, vm = vm) }

        item(key = "add") {
            SyncAdd(
                value = address,
                onValueChange = { address = it },
                onSubmit = {
                    vm.syncAddPeer(address)
                    address = ""
                },
            )
        }
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
                "还没开始 —— 停留在本页面即会开启广播发现"
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
 * A library with databases or attachments cannot be carried by this shell, and a
 * snapshot that dropped them would make a peer's merge read the absence as a
 * deletion — so the engine is not started at all and the reason is on screen.
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
            text = listOf(
                row.address.ifEmpty { "没有地址" },
                if (row.paired) "已配对" else "未配对",
                if (row.lastSync.isEmpty()) "从未同步" else "上次同步 ${row.lastSync}",
            ).joinToString("  ·  "),
            style = QuireType.caption,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.paired) {
                TextButton(onClick = { vm.syncNow(row.id) }) { Text("立即同步", color = colors.accentText) }
            } else {
                TextButton(onClick = { vm.syncPair(row.id) }) { Text("发起配对", color = colors.accentText) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.syncForget(row.id) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (row.paired) "忘记这台设备" else "移除",
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

/** 按地址添加: the door for a network where the announcement cannot get through. */
@Composable
private fun SyncAdd(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text("按地址添加", style = QuireType.caption) },
            placeholder = { Text("192.168.1.20 或 192.168.1.20:${5878}", style = QuireType.caption) },
            textStyle = QuireType.ui,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSubmit, enabled = value.isNotBlank()) { Text("配对") }
    }
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
            // The reference app's three presets, plus 手动: Wi-Fi 期间按 10 秒 /
            // 1 分 / 5 分 / 30 分，或者只在我按的时候。
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
 * Not a disclaimer for its own sake: this shell cannot carry databases or
 * attachments, so a library that has them refuses to sync at all, and the line
 * has to be somewhere a user will read it.
 */
@Composable
private fun SyncFoot() {
    val colors = LocalQuireColors.current
    Text(
        text = "同步搬运：页面、正文、笔记与任务。这个版本还不同步数据库和附件 —— " +
            "带这两样的资料库会拒绝同步（在桌面端处理它，或先移除）。",
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
