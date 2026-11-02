package com.gmch.codexremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** Both engines adapt data and actions into these components; layout never depends on engine. */
internal data class WorkspaceSession(val id: String, val title: String, val path: String, val project: String,
    val status: String, val updatedAt: Long, val group: String = path,
    val preview: String = "", val pendingInteractions: Int = 0,
    val unread: Boolean = false, val backgroundWork: Boolean = false)
internal fun SessionInfo.workspace(cached: Boolean = false) = WorkspaceSession(id, title, cwd, project,
    if (cached) "" else status, updatedAt * 1000)
internal fun ZSession.workspace(projects: List<ZProject>, fresh: Boolean = true, live: List<ZLiveSessionStatus> = emptyList(),
    meta: Map<String, ZListMeta> = emptyMap()) = WorkspaceSession(id, title, path,
    projects.find { it.id == project }?.name ?: path.trimEnd('/').substringAfterLast('/'),
    zcodeListStatus(this, fresh, live), updatedAt, "$project:$identity:$path",
    meta[id]?.preview.orEmpty(), meta[id]?.pending ?: 0,
    meta[id]?.unread ?: false, meta[id]?.backgroundWork ?: false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScaffold(title: String, subtitle: String = "", chat: Boolean = false, tab: Int = 0, busy: Boolean = false,
    back: () -> Unit = {}, refresh: () -> Unit = {}, selectTab: (Int) -> Unit = {},
    engine: Pair<String, (String) -> Unit>? = null,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets.navigationBars,
        topBar = { Column {
            TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), expandedHeight = if (compact) 48.dp else 64.dp,
                title = { Column {
                    if (!compact && subtitle.isNotBlank()) Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, fontSize = if (chat || compact) 18.sp else 22.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }, navigationIcon = { if (chat) IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话") } },
                actions = {
                    if (engine != null) EngineTabs(engine.first, engine.second, small = true)
                    IconButton(refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新") }
                    actions()
                })
            // Reserve the same height when loading, so titles and content never jump.
            Box(Modifier.fillMaxWidth().height(2.dp)) { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } }, bottomBar = { if (!chat) WorkspaceNavBar(tab, selectTab) }) { padding -> Column(Modifier.fillMaxSize().padding(padding).imePadding()) { content() } }
}

@Composable
internal fun WorkspaceNavBar(tab: Int, selectTab: (Int) -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.navigationBarsPadding().height(if (compact) 56.dp else 64.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
        // Keep the connection route ID stable for saved pages from older builds.
        listOf(Triple(0, "会话", Icons.Outlined.Forum), Triple(2, "连接", Icons.Outlined.Dns)).forEach { (id, label, icon) ->
            NavigationBarItem(tab == id, { selectTab(id) }, icon = { Icon(icon, label, Modifier.size(22.dp)) },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
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

/**
 * Shared list page for both engines (原型 codex-list-v2): a two-row top — engine switch /
 * search / create over project filter / count / list menu — then a flat cross-project list.
 * Filters and scroll position are rememberSaveable per engine instance; offline keeps the
 * cached rows readable without status or live marks, with one reconnect banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceListPage(engine: String, onEngine: (String) -> Unit,
    sessions: List<WorkspaceSession>, connected: Boolean, busy: Boolean,
    canCreate: Boolean, canOpen: Boolean,
    open: (String) -> Unit, create: () -> Unit, reconnect: () -> Unit, refresh: () -> Unit,
    addProject: (() -> Unit)?, error: String?, dismissError: () -> Unit, detail: (String, String) -> Unit,
    selectTab: (Int) -> Unit, more: (() -> Unit)? = null, topExtra: @Composable () -> Unit = {}) {
    var search by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var projectPath by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var onlyRunning by rememberSaveable { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchingWithKeyboard = showSearch && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val compactSearch = searchingWithKeyboard && LocalConfiguration.current.screenHeightDp < 480
    LaunchedEffect(showSearch) { if (showSearch) searchFocus.requestFocus() }
    val criteria = listOf(search, projectPath, newestFirst, onlyRunning)
    var appliedCriteria by remember { mutableStateOf(criteria) }
    LaunchedEffect(criteria) {
        // Apply the reset on the next measure, overriding LazyColumn's stable-key
        // preservation when sorting changes the item provider in the same frame.
        // Initial/restored filters keep their saved position.
        if (appliedCriteria != criteria) {
            list.requestScrollToItem(0)
            appliedCriteria = criteria
        }
    }
    // A stored selection that no longer exists must not dead-end the list into an empty filter.
    val activeProject = projectPath.takeIf { group -> sessions.any { it.group == group } } ?: ""
    val filtered = selectSessions(sessions, search, activeProject, onlyRunning, newestFirst)
    val projects = remember(sessions) {
        sessions.groupBy { it.group }.map { (group, rows) ->
            val row = rows.first()
            WorkspaceProject(group, row.path, row.project.ifBlank { row.path.trimEnd('/').substringAfterLast('/') }, rows.size)
        }.sortedWith(compareByDescending<WorkspaceProject> { it.count }.thenBy { it.name })
    }
    val filtersActive = search.isNotBlank() || activeProject.isNotEmpty() || onlyRunning
    BackHandler(enabled = showSearch) { showSearch = false; search = "" }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = { if (!searchingWithKeyboard) WorkspaceNavBar(tab = 0, selectTab = selectTab) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().height(if (compactSearch) 48.dp else 56.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (showSearch) {
                    IconButton({ showSearch = false; search = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "退出搜索") }
                    Row(Modifier.weight(1f).height(40.dp).padding(start = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(search, { search = it }, Modifier.weight(1f).focusRequester(searchFocus), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box { if (search.isEmpty()) Text("搜索会话或项目", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
                            })
                        IconButton({ search = "" }, Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp)) }
                    }
                } else {
                    EngineTabs(engine, onEngine)
                    Spacer(Modifier.weight(1f))
                    IconButton({ showSearch = true }, enabled = sessions.isNotEmpty()) { Icon(Icons.Outlined.Search, "搜索会话") }
                    IconButton(create, enabled = canCreate) {
                        Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(17.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, "新建会话", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            if (!compactSearch) Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp)
                .drawBehind { drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), 1f) },
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "选择项目") { showProjects = true }
                    .semantics { contentDescription = "筛选项目" }.padding(horizontal = 0.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    val label = projects.firstOrNull { it.key == activeProject }?.name
                        ?: activeProject.trimEnd('/').substringAfterLast('/')
                    Text(if (activeProject.isEmpty()) "全部项目" else label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${filtered.size} 个会话", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton({ showMenu = true }) { Icon(Icons.Outlined.MoreVert, "列表菜单", Modifier.size(20.dp)) }
                    DropdownMenu(showMenu, { showMenu = false }) {
                        DropdownMenuItem({ Text("刷新") }, { showMenu = false; refresh() }, enabled = connected && !busy,
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("最近活动优先") }, { showMenu = false; newestFirst = true },
                            trailingIcon = { if (newestFirst) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
                        DropdownMenuItem({ Text("较早活动优先") }, { showMenu = false; newestFirst = false },
                            trailingIcon = { if (!newestFirst) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("只看执行中") }, { showMenu = false; onlyRunning = !onlyRunning },
                            trailingIcon = { if (onlyRunning) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!connected) OfflineBanner(busy, reconnect)
            WorkspaceError(error, dismissError, detail)
            topExtra()
            PullToRefreshBox(isRefreshing = busy, onRefresh = { if (connected && !busy) refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    if (filtered.isEmpty()) item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(when {
                                filtersActive -> "没有匹配的会话"
                                sessions.isNotEmpty() -> "没有匹配的会话"
                                busy -> "正在加载会话…"
                                !connected -> "连接后加载会话"
                                error != null -> "会话加载失败"
                                else -> "暂无会话"
                            }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (filtersActive) TextButton({ search = ""; projectPath = ""; onlyRunning = false }) { Text("清除筛选") }
                            if (error != null && !busy) TextButton(refresh, enabled = connected) { Text("重试") }
                        }
                    }
                    items(filtered, key = { it.id }) { row ->
                        SessionRow(row.title, row.project, row.status, row.updatedAt, enabled = canOpen,
                            preview = row.preview, pending = row.pendingInteractions,
                            unread = row.unread, background = row.backgroundWork) { open(row.id) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                    if (more != null) item { TextButton(more, Modifier.fillMaxWidth(), enabled = connected && !busy) { Text("加载更多会话") } }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
    if (showProjects) ProjectPickerSheet(projects, activeProject, { projectPath = it; showProjects = false },
        addProject?.let { add -> { showProjects = false; add() } },
        enabled = connected && !busy, close = { showProjects = false })
}

@Composable
private fun OfflineBanner(busy: Boolean, reconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(Color(0xfff8f0df))
        .padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(14.dp), tint = Color(0xff805c22))
        Text("离线 · 显示上次内容", Modifier.padding(start = 7.dp), fontSize = 11.sp, color = Color(0xff805c22))
        Spacer(Modifier.weight(1f))
        TextButton(reconnect, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("重新连接", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Engine switch keeps one-tap access without owning a whole row (原型 toolbar row). */
@Composable
internal fun EngineTabs(engine: String, onEngine: (String) -> Unit, small: Boolean = false) {
    Row(Modifier.height(48.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("codex" to "Codex", "zcode" to "ZCode").forEach { (id, label) ->
            val active = id == engine
            Box(Modifier.height(48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                .selectable(active, role = Role.Tab) { if (!active) onEngine(id) }
                .padding(horizontal = if (small) 9.dp else 14.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = if (small) 12.sp else 14.sp, maxLines = 1,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Project filter as a bottom panel: names over paths, counts, and the directory picker at the end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickerSheet(projects: List<WorkspaceProject>, selected: String,
    choose: (String) -> Unit, addProject: (() -> Unit)?, enabled: Boolean, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选择项目", Modifier.weight(1f).padding(start = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                IconButton(close) { Icon(Icons.Outlined.Close, "关闭项目列表") }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item { SheetOption(selected.isEmpty(), onClick = { choose("") }) {
                    Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.GridView, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("全部项目", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("跨项目查看最近活动", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${projects.sumOf { it.count }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                items(projects, key = { it.key }) { (key, path, name, count) ->
                    SheetOption(selected == key, onClick = { choose(key) }) {
                        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (addProject != null) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(addProject, Modifier.fillMaxWidth(), enabled = enabled) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加项目")
                }
            }
        }
    }
}

@Composable
private fun SheetOption(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)
        .background(if (selected) Color(0xffe8f1e9) else Color.Transparent, RoundedCornerShape(12.dp))
        .clickable(onClickLabel = if (selected) "已选中" else null, onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        content()
        if (selected) Icon(Icons.Outlined.Check, "已选中", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ChatComposer(draft: String, change: (String) -> Unit, sending: Boolean, canSend: Boolean,
    insert: Boolean, changeInsert: (Boolean) -> Unit, send: () -> Unit) {
    val compact = WindowInsets.ime.getBottom(LocalDensity.current) > 0 || LocalConfiguration.current.screenHeightDp < 480
    var editing by remember { mutableStateOf(false) }
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val mode = if (insert) "插入" else "排队"
            Box(Modifier.width(64.dp).height(48.dp).focusProperties { canFocus = false }
                .semantics { contentDescription = "发送方式"; stateDescription = mode }
                .clickable(role = Role.Button, onClickLabel = if (insert) "切换为排队" else "切换为插入") { changeInsert(!insert) },
                contentAlignment = Alignment.Center) {
                Surface(Modifier.fillMaxWidth().height(28.dp), shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Text(mode, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.SwapVert, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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

/**
 * Shared compact entry for pending interactions on both engines: one line under the run
 * header with the pending count; full content and answer controls open in a bottom sheet,
 * so nothing is stacked over the timeline.
 */
@Composable
internal fun InteractionBanner(label: String, count: Int, onClick: () -> Unit) {
    Surface(color = Color(0xfffdeccd), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "查看待交互内容", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PanToolAlt, null, Modifier.size(14.dp), tint = Color(0xff8a5a00))
            Spacer(Modifier.width(6.dp))
            Text("待交互 $count · $label", Modifier.weight(1f), fontSize = 12.sp, color = Color(0xff6b4700),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Color(0xff8a5a00))
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
    diff: String = "", removeQueued: ((QueuedMessage) -> Unit)? = null,
    turnSummary: TurnDiffSummary? = null, onTurnDiff: (() -> Unit)? = null,
    editQueued: ((QueuedMessage, String) -> Unit)? = null, promoteQueued: ((QueuedMessage) -> Unit)? = null) {
    val list = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var jump by remember { mutableStateOf<String?>(null) }
    var editingQueue by remember(sessionId) { mutableStateOf<QueuedMessage?>(null) }
    var saveQueueEdit by remember(sessionId) { mutableStateOf<((QueuedMessage, String) -> Unit)?>(null) }
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
    val tail = (if (hasMore) 1 else 0) + maxOf(1, rows.size) +
        (if (diff.isNotBlank() || turnSummary != null) 1 else 0)
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
                    QueuedItemActions(row.queued, connected && !busy, removeQueued, promoteQueued,
                        onEdit = if (editQueued != null) { { saveQueueEdit = editQueued; editingQueue = it } } else null)
                }
            } }
            if (turnSummary != null) item(key = "turn-diff") {
                TurnDiffBadge(turnSummary, enabled = connected && !busy, onClick = onTurnDiff)
            } else if (diff.isNotBlank()) item { OutlinedButton({ detail("当前执行差异", diff) }, Modifier.fillMaxWidth()) { Text("查看本轮修改") } }
            item(key = "tail") { Spacer(Modifier.height(1.dp)) }
        }
        if (!follow) TextButton({ jump = null; follow = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("回到最新") }
    }
    editingQueue?.let { queued ->
        var value by remember(queued.id) { mutableStateOf(queued.text) }
        AlertDialog(onDismissRequest = { editingQueue = null }, title = { Text("编辑排队消息") },
            text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), maxLines = 5, label = { Text("排队内容") }) },
            confirmButton = { TextButton({ saveQueueEdit?.invoke(queued, value); editingQueue = null },
                enabled = value.isNotBlank() && editQueued != null) { Text("保存") } },
            dismissButton = { TextButton({ editingQueue = null }) { Text("取消") } })
    }
}

/** Compact inline actions under a queued row; absent actions (older bridges) hide entirely. */
@Composable
private fun QueuedItemActions(queued: QueuedMessage, enabled: Boolean,
    remove: ((QueuedMessage) -> Unit)?, promote: ((QueuedMessage) -> Unit)?,
    onEdit: ((QueuedMessage) -> Unit)?) {
    if (remove == null && promote == null && onEdit == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (remove != null) TextButton({ remove(queued) }, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("移出队列", fontSize = 11.sp) }
        if (promote != null) TextButton({ promote(queued) }, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("立即发送", fontSize = 11.sp) }
        if (onEdit != null) TextButton({ onEdit(queued) }, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("编辑", fontSize = 11.sp) }
    }
}

/**
 * Authoritative turn-change badge: CLI-aggregated counters with the official client's
 * shape (+N −M · files · reverted), plus the turn's active work time while running.
 * Tap loads the per-file detail; disabled only means "not clickable right now".
 */
@Composable
private fun TurnDiffBadge(summary: TurnDiffSummary, enabled: Boolean, onClick: (() -> Unit)?) {
    val duration = turnDurationLabel(summary.activeMs)
    Surface(onClick = { onClick?.invoke() }, enabled = enabled && onClick != null,
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(top = 2.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(turnDiffBadgeLabel(summary), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                style = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            if (summary.running && duration.isNotBlank())
                Text(duration, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, "查看更改明细", Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
