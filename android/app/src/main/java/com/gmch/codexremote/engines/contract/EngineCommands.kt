package com.gmch.codexremote.engines.contract

/** Protocol support, not authorization or a promise that an action is currently available. */
internal enum class EngineOperation { Create, Send, Interrupt, Rename, CommandQuery }
internal enum class MessageDelivery { Auto, Queue, Steer }
internal enum class ReceiptDelivery { Accepted, Queued, Steered }
internal data class EngineChoice(val origin: EngineOrigin, val provider: String = "", val model: String,
    val level: String = "", val guard: String = "", val kind: String = "", val label: String = model)
internal data class EngineCreateRequest(val commandId: String, val workspacePath: String,
    val title: String = "", val projectId: String = "", val choice: EngineChoice? = null)
internal data class EngineMessage(val session: EngineSessionRef, val commandId: String, val text: String,
    val delivery: MessageDelivery = MessageDelivery.Auto, val expectedRunId: String = "", val choice: EngineChoice? = null,
    val attachments: List<EngineAttachment> = emptyList())
internal data class EngineAttachment(val session: EngineSessionRef, val reference: String,
    val fileName: String, val mime: String, val bytes: Long)
internal data class EngineReceipt(val commandId: String, val delivery: ReceiptDelivery,
    val session: EngineSessionRef, val runId: String = "")
internal interface EngineCreatedSession { val session: EngineSession }
internal data class EngineSessionSnapshot(val session: EngineSessionRef, val title: String,
    val history: HistoryPage, val running: Boolean?, val runId: String = "", val canInterrupt: Boolean = false,
    val queue: List<EngineQueuedMessage> = emptyList(), val selection: EngineChoice? = null)
internal data class EngineQueuedMessage(val id: String, val clientId: String, val text: String)
internal class EngineRejectedException(val reason: String, message: String) : Exception(message)
internal class EngineCreationUnconfirmed(val session: EngineSessionRef?, cause: Throwable) : Exception(cause.message, cause)

internal enum class PendingKind { Create, Send, Interrupt }
internal data class PendingOperation(val origin: EngineOrigin, val id: String, val kind: PendingKind,
    val session: EngineSessionRef? = null, val workspacePath: String = "", val projectId: String = "", val text: String = "")

internal enum class CommandResolutionState { Accepted, Rejected, Unknown }
internal data class CommandResolution(val state: CommandResolutionState, val session: EngineSessionRef? = null,
    val delivery: ReceiptDelivery = ReceiptDelivery.Accepted, val reason: String = "")

internal fun EngineChoice.sameSelection(other: EngineChoice, checkGuard: Boolean = true) =
    origin == other.origin && provider == other.provider && model == other.model && level == other.level && (!checkGuard || guard == other.guard)
