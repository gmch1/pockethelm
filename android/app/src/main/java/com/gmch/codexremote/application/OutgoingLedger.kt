package com.gmch.codexremote.application

import com.gmch.codexremote.*

/** Shared in-memory projection of durable receipts. Only settled failures may be
 * trimmed; transport loss never turns an uncertain/accepted submission into failure. */
internal class OutgoingLedger(private val changed: (OutgoingKey) -> Unit) {
    val records = LinkedHashMap<OutgoingKey, MutableList<OutgoingMessage>>()
    fun add(key: OutgoingKey, message: OutgoingMessage) {
        val list = records.getOrPut(key) { mutableListOf() }
        list.removeAll { it.clientId == message.clientId }; list.add(message)
        while (list.size > 20) {
            val index = list.indexOfFirst { it.state == OutgoingState.FAILED }
            if (index < 0) break
            list.removeAt(index)
        }
        changed(key)
    }
    fun update(key: OutgoingKey, id: String, transform: (OutgoingMessage) -> OutgoingMessage) {
        val list = records[key] ?: return
        val index = list.indexOfFirst { it.clientId == id }
        if (index >= 0) { list[index] = transform(list[index]); changed(key) }
    }
    fun remove(key: OutgoingKey, id: String?) {
        if (id.isNullOrBlank()) return
        if (records[key]?.removeAll { it.clientId == id } == true) changed(key)
    }
    fun uncertain(key: OutgoingKey, id: String? = null) {
        val list = records[key] ?: return
        var modified = false
        list.replaceAll { item ->
            if ((id == null || item.clientId == id) && item.state == OutgoingState.SENDING) {
                modified = true; item.copy(state = OutgoingState.UNCERTAIN)
            } else item
        }
        if (modified) changed(key)
    }
    fun reconcile(key: OutgoingKey, messageIds: Set<String>, queueIds: Set<String>) {
        val list = records[key] ?: return
        val remaining = reconcileOutgoingList(list, messageIds, queueIds)
        if (remaining.size != list.size) { records[key] = remaining.toMutableList(); changed(key) }
    }
}
