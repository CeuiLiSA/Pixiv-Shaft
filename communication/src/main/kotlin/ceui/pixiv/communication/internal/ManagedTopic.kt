package ceui.pixiv.communication.internal

/**
 * Seal memory synchronously, then return the work that may resume external code. The owning scope
 * seals ALL topics before running any notifications, so a reentrant close observer cannot publish
 * into a not-yet-closed sibling topic. Neither phase performs I/O.
 */
internal interface ManagedTopic {
    fun seal(): () -> Unit
}
