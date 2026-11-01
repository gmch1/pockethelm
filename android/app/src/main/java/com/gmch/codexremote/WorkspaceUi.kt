package com.gmch.codexremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/** Both engines adapt data and actions into these components; layout never depends on engine. */
internal data class WorkspaceSession(val id: String, val title: String, val path: String, val project: String,
    val status: String, val updatedAt: Long, val group: String = path)
internal fun SessionInfo.workspace() = WorkspaceSession(id, title, cwd, project, status, updatedAt * 1000)
internal fun ZSession.workspace(projects: List<ZProject>, fresh: Boolean = true, live: List<ZLiveSessionStatus> = emptyList()) = WorkspaceSession(id, title, path,
    projects.find { it.id == project }?.name ?: path.trimEnd('/').substringAfterLast('/'),
    zcodeListStatus(this, fresh, live), updatedAt, "$project:$identity:$path")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScaffold(title: String, subtitle: String, chat: Boolean, tab: Int, busy: Boolean,
    back: () -> Unit, refresh: () -> Unit, selectTab: (Int) -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets.navigationBars,
        topBar = { Column {
            TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), expandedHeight = if (compact) 48.dp else 64.dp,
                title = { Column {
                    if (!compact) Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, fontSize = if (chat || compact) 18.sp else 26.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }, navigationIcon = { if (chat) IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话") } },
                actions = { IconButton(refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新") }; actions() })
            // Reserve the same height when loading, so titles and content never jump.
            Box(Modifier.fillMaxWidth().height(2.dp)) { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } }, bottomBar = {
            if (!chat) NavigationBar(containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding().height(if (compact) 56.dp else 64.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
                listOf("会话" to Icons.Outlined.Forum, "任务" to Icons.Outlined.Checklist, "连接" to Icons.Outlined.Dns).forEachIndexed { i, pair ->
                    NavigationBarItem(tab == i, { selectTab(i) }, icon = { Icon(pair.second, pair.first, Modifier.size(22.dp)) },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }

        }) { padding -> Column(Modifier.fillMaxSize().padding(padding).imePadding()) { content() } }
}

@Composable
internal fun WorkspaceError(error: String?, dismiss: () -> Unit, detail: (String, String) -> Unit) {
    error ?: return
    Surface(color = Color(0xfff9ebe5), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton({ detail("连接与执行信息", error) }) { Icon(Icons.Outlined.Info, "详细信息") }
            IconButton(dismiss) { Icon(Icons.Outlined.Close, "关闭错误") }
        }
    }
}

@Composable
internal fun WorkspaceSessions(sessions: List<WorkspaceSession>, connected: Boolean, busy: Boolean, cached: Boolean,
    environment: String, error: String?, canCreate: Boolean, open: (String) -> Unit, create: () -> Unit,
    connection: () -> Unit, refresh: () -> Unit, more: (() -> Unit)? = null, addProject: (() -> Unit)? = null,
    tasks: Boolean = false, canOpen: Boolean = true) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    var search by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var project by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("全部") }
    val list = rememberLazyListState()
    LaunchedEffect(list) { snapshotFlow { Triple(search, project, status) }.drop(1).collect { list.scrollToItem(0) } }
    val filtered = sessions.filter { (project.isEmpty() || it.group == project) &&
        (!tasks || status == "全部" || (!cached && it.status == status)) &&
        (it.title.contains(search, true) || it.path.contains(search, true) || it.project.contains(search, true)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!compact) Surface(shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().clickable(onClick = connection)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp))
                Text(environment, Modifier.weight(1f).padding(start = 9.dp), fontSize = 12.sp)
                Text(if (connected) "已连接" else "离线 · 缓存", fontSize = 10.sp)
            }
        }
        if (!compact || showSearch) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 12.dp),
            placeholder = { Text("搜索会话或项目", fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                if (compact) IconButton({ showSearch = false; search = "" }) { Icon(Icons.Outlined.Close, "关闭搜索") }
            })
        if (!compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SelectionChip(project.isEmpty(), { project = "" }, "全部项目")
            sessions.distinctBy { it.group }.forEach { p -> SelectionChip(project == p.group, { project = p.group }, p.project) }
        }
        if (tasks) Text(if (cached) if (connected) "正在同步任务状态" else "离线 · 显示上次列表" else
            listOf("执行中", "已完成", "失败").joinToString(" · ") { label -> "$label ${sessions.count { it.status == label }}" },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tasks) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "执行中", "已完成", "失败", "已中断").forEach { label ->
                SelectionChip(status == label, { status = label }, label)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact && !showSearch) IconButton({ showSearch = true }) { Icon(Icons.Outlined.Search, "搜索会话") }
            Text("${if (tasks) "最近执行" else "最近会话"} · ${filtered.size} 个${if (cached) if (connected) " · 正在同步" else " · 离线缓存" else ""}",
                Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (addProject != null) TextButton(addProject, enabled = connected && !busy) { Text("添加项目") }
            FilledTonalIconButton(create, enabled = canCreate, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, "新建会话", Modifier.size(20.dp))
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when {
                        sessions.isNotEmpty() -> "暂无匹配的会话"
                        busy -> "正在加载会话…"
                        !connected -> "连接后加载会话"
                        error != null -> "会话加载失败"
                        else -> "暂无会话"
                    }, fontSize = 13.sp)
                    if (error != null && !busy) TextButton(refresh) { Text("重试") }
                }
            }
            recentProjectGroups(filtered, { it.updatedAt }, { it.group }).forEach { (group, rows) ->
                item(key = "project-$group") { ProjectHeading(rows.first().project, rows.first().path, rows.size) }
                items(rows, key = { it.id }) { row -> CompactSessionRow(row.title, if (cached) "" else row.status,
                    row.updatedAt, enabled = canOpen) { open(row.id) } }
            }
            if (more != null) item { TextButton(more, Modifier.fillMaxWidth(), enabled = connected && !busy) { Text("加载更多会话") } }
            item { Spacer(Modifier.height(8.dp)) }
        }

    }
}

@Composable
internal fun ChatComposer(draft: String, change: (String) -> Unit, sending: Boolean, canSend: Boolean,
    insert: Boolean, changeInsert: (Boolean) -> Unit, send: () -> Unit) {
    val compact = WindowInsets.ime.getBottom(LocalDensity.current) > 0 || LocalConfiguration.current.screenHeightDp < 480
    var editing by remember { mutableStateOf(false) }
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceTabs(listOf("排队", "插入"), if (insert) 1 else 0, { changeInsert(it == 1) },
                Modifier.width(112.dp), preserveEditorFocus = true, small = true)
            TextField(draft, change, Modifier.weight(1f).onFocusChanged { editing = it.isFocused }.heightIn(max = if (compact) 88.dp else 160.dp),
                placeholder = { Text("描述任务，或补充要求…", fontSize = 13.sp) }, maxLines = if (compact) 2 else 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            FilledIconButton(send, enabled = canSend, modifier = Modifier.size(48.dp)) {
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Outlined.Send, "发送消息")
            }
        }
    }
}

@Composable
internal fun ChatRunHeader(run: AgentRun, label: String, cached: Boolean, connected: Boolean,
    stoppable: Boolean, canStop: Boolean, stop: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (cached) Text("上次内容 · ${if (connected) "正在同步" else "离线可阅读"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            RunStatusRow(run, agentRunLabel(run, label), Modifier.weight(1f))
            if (stoppable) TextButton(stop, enabled = canStop) { Text("停止") }
        }
    }
}

@Composable
internal fun ChatSettingsBar(summary: String, quota: String, settings: () -> Unit, showQuota: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(settings, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
            Text(summary, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(showQuota, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(quota, Modifier.widthIn(max = 120.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ChatTimeline(sessionId: String, agent: String, items: List<TimelineItem>, outgoing: List<OutgoingMessage>, queue: List<QueuedMessage>,
    hasMore: Boolean, loading: Boolean, error: String?, connected: Boolean, busy: Boolean,
    modifier: Modifier, history: () -> Unit, retry: () -> Unit, detail: (String, String) -> Unit,
    diff: String = "", removeQueued: ((QueuedMessage) -> Unit)? = null) {
    val list = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var jump by remember { mutableStateOf<String?>(null) }
    val rows = remember(items, outgoing, queue) { readingRows(buildChatRows(items, outgoing, queue)) }
    val questions = remember(rows) { rows.filter { it.isUserRow } }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    val compact = ime > 0 || LocalConfiguration.current.screenHeightDp < 480
    LaunchedEffect(list) { list.interactionSource.interactions.collectLatest { interaction ->
        when (interaction) {
            is DragInteraction.Start -> follow = false
            is DragInteraction.Stop, is DragInteraction.Cancel -> {
                withFrameNanos { }; snapshotFlow { list.isScrollInProgress }.first { !it }; follow = !list.canScrollForward
            }
        }
    } }
    val tail = (if (hasMore) 1 else 0) + maxOf(1, rows.size) + (if (diff.isNotBlank()) 1 else 0)
    LaunchedEffect(follow, tail, rows.lastOrNull(), ime) { if (follow && rows.isNotEmpty()) list.scrollToItem(tail) }
    LaunchedEffect(jump, rows, hasMore) {
        val index = rows.indexOfFirst { it.id == jump }
        if (jump != null && index >= 0) { list.scrollToItem(index + if (hasMore) 1 else 0); jump = null }
    }
    Column(modifier) {
        if (questions.isNotEmpty() || hasMore) UserMessageBar(questions, hasMore, loading, connected, compact,
            { follow = false; history() }, { follow = false; jump = it })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compact) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 14.dp)) {
            if (hasMore) item { TextButton({ follow = false; history() }, Modifier.fillMaxWidth(), enabled = connected && !busy && !loading) {
                Text(if (loading) "加载中…" else "加载更早的消息")
            } }
            when (timelineView(rows.size, loading, error)) {
                TimelineView.Loading -> item { Text("正在加载会话内容…", fontSize = 12.sp) }
                TimelineView.Error -> item { Column {
                    Text("会话内容加载失败：$error", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(retry, enabled = connected && !busy) { Text("重试") }
                } }
                TimelineView.Empty -> item { Text("开始这次工作，输入任务后在开发机执行。", fontSize = 12.sp) }
                TimelineView.Content -> {}
            }
            items(rows, key = { it.key }) { row -> when {
                row.activity.isNotEmpty() -> ActivitySummary(row.activity, detail)
                row.item != null -> TimelineCard(row.item, agentName = agent, detail = detail)
                row.outgoing != null -> OutgoingCard(row.outgoing, detail)
                row.queued != null -> Column {
                    OutgoingCard(OutgoingMessage(sessionId, row.queued.clientId, row.queued.text, OutgoingState.QUEUED, "已排队"), detail)
                    if (removeQueued != null) TextButton({ removeQueued(row.queued) }, enabled = connected && !busy) { Text("移出队列") }
                }
            } }
            if (diff.isNotBlank()) item { OutlinedButton({ detail("当前执行差异", diff) }, Modifier.fillMaxWidth()) { Text("查看本轮修改") } }
            item(key = "tail") { Spacer(Modifier.height(1.dp)) }
        }
        if (!follow) TextButton({ jump = null; follow = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("回到最新") }
    }
}
