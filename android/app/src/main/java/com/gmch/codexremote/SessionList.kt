package com.gmch.codexremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PanToolAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cross-project flat list, newest first (原型 codex-list-v2): one row per session with
 * project / status / time on the second line. Filters compose: project path, running-only,
 * then free-text search over title, project and path.
 */
internal data class WorkspaceProject(val key: String, val path: String, val name: String, val count: Int)

internal fun selectSessions(sessions: List<WorkspaceSession>, query: String, projectPath: String,
    onlyRunning: Boolean, newestFirst: Boolean): List<WorkspaceSession> {
    val text = query.trim()
    return sessions.asSequence()
        .filter { projectPath.isEmpty() || it.group == projectPath }
        .filter { !onlyRunning || it.status == "执行中" }
        .filter { text.isEmpty() || it.title.contains(text, true) || it.project.contains(text, true) || it.path.contains(text, true) }
        .toList()
        .let { if (newestFirst) it.sortedByDescending { row -> row.updatedAt } else it.sortedBy { row -> row.updatedAt } }
}

internal fun relativeActivityTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return ""
    val minutes = ((now - millis).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟"
        minutes < 1440 -> "${minutes / 60} 小时"
        minutes < 2880 -> "昨天"
        else -> "${minutes / 1440} 天"
    }
}

private val Amber = Color(0xff936b28)
private val RunningTint = Color(0xffe8f1e9)

/** One flat list row: title over preview (ZCode P1, when present) over project · status ·
 *  time; running rows keep a soft wash and edge bar, unread rows carry a dot before the
 *  title, pending-interaction counts and background work ride the status line. */
@Composable
internal fun SessionRow(title: String, project: String, status: String, updatedAt: Long,
                        enabled: Boolean = true, preview: String = "", pending: Int = 0,
                        unread: Boolean = false, background: Boolean = false, onClick: () -> Unit) {
    val running = status == "执行中"
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .background(if (running) RunningTint else Color.Transparent)
        .clickable(enabled = enabled, onClickLabel = "打开会话", onClick = onClick)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (running) MaterialTheme.colorScheme.primary else Color.Transparent))
        Row(Modifier.weight(1f).heightIn(min = if (preview.isBlank()) 76.dp else 88.dp)
            .padding(start = 14.dp, end = 18.dp, top = 13.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (unread) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(title, Modifier.weight(1f, fill = false), fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
                        maxLines = if (LocalDensity.current.fontScale > 1.15f) 2 else 1, overflow = TextOverflow.Ellipsis)
                }
                if (preview.isNotBlank()) Text(preview, fontSize = 11.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(project.ifBlank { "未归类项目" }, fontSize = 11.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (project.isNotEmpty() && status.isNotEmpty()) Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        status == "执行中" -> {
                            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                            Text(status, fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        status == "等待中" || status == "准备中" -> {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = Amber)
                            Text(status, fontSize = 11.sp, lineHeight = 18.sp, color = Amber)
                        }
                        status == "失败" -> {
                            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                            Text(status, fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error)
                        }
                        status.isNotEmpty() -> Text(status, fontSize = 11.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (pending > 0) {
                        Icon(Icons.Outlined.PanToolAlt, null, Modifier.size(12.dp), tint = Amber)
                        Text("待交互 $pending", fontSize = 11.sp, lineHeight = 18.sp, color = Amber)
                    }
                    if (background) Text("后台", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(relativeActivityTime(updatedAt), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
