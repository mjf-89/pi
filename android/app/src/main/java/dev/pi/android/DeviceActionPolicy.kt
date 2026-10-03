package dev.pi.android

/** Conservative label fallback alongside the model's explicit consequential-action flag. */
object DeviceActionPolicy {
    private val consequential = Regex("\\b(send|submit|post|publish|buy|purchase|pay|checkout|transfer|delete|remove|erase|reset|uninstall|install|grant|allow)\\b", RegexOption.IGNORE_CASE)
    fun needsConfirmation(confirmAll: Boolean, modelRequested: Boolean, checkable: Boolean, label: String): Boolean =
        confirmAll || modelRequested || checkable || consequential.containsMatchIn(label.replace('_', ' '))
}
