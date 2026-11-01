package com.gmch.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Group after sorting so both project groups and their children follow recent activity. */
internal fun <T> recentProjectGroups(items: List<T>, time: (T) -> Long, project: (T) -> String): Map<String, List<T>> =
    items.sortedByDescending(time).groupBy(project)

internal fun relativeActivityTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return ""
    val minutes = ((now - millis).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分"
        minutes < 1440 -> "${minutes / 60}小时"
        else -> "${minutes / 1440}天"
    }
}

@Composable
internal fun ProjectHeading(name: String, path: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(name.ifBlank { "未归类项目" }, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CompactSessionRow(title: String, status: String, updatedAt: Long,
                               enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, fontSize = 10.sp, lineHeight = 14.sp, color = when (status) {
                    "失败" -> MaterialTheme.colorScheme.error
                    "执行中" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(relativeActivityTime(updatedAt), fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
