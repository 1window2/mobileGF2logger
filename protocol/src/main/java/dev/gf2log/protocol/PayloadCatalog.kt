package dev.gf2log.protocol

data class PayloadCategory(
    val payloadType: Int,
    val name: String,
    val tag: String,
    val description: String,
    val isRequired: Boolean = false,
)

object PayloadCatalog {
    val categories: List<PayloadCategory> = listOf(
        PayloadCategory(
            payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            name = "PLATOON MEMBERS",
            tag = "PLATOON",
            description = "Platoon members, merit totals, scores, and last-login times.",
            isRequired = true,
        ),
        PayloadCategory(
            payloadType = Gfl2PayloadDecoder.TYPE_WEAPONS,
            name = "WEAPONS",
            tag = "WEAPONS",
            description = "Owned weapons and their upgrade data.",
        ),
        PayloadCategory(
            payloadType = Gfl2PayloadDecoder.TYPE_ATTACHMENTS,
            name = "ATTACHMENTS",
            tag = "ATTACHMENTS",
            description = "Owned weapon attachments and their attributes.",
        ),
        PayloadCategory(
            payloadType = Gfl2PayloadDecoder.TYPE_COMMON_KEYS,
            name = "COMMON KEYS",
            tag = "KEYS",
            description = "Owned Common Keys. The uid is an inventory-instance ID, not a player UID.",
        ),
        PayloadCategory(
            payloadType = Gfl2PayloadDecoder.TYPE_FORMATIONS,
            name = "FORMATIONS",
            tag = "FORMATIONS",
            description = "Saved team formations and their equipped items.",
        ),
    )

    fun find(payloadType: Int?): PayloadCategory? =
        categories.firstOrNull { it.payloadType == payloadType }

    fun tag(payloadType: Int?): String =
        find(payloadType)?.tag ?: payloadType?.let { "TYPE $it" } ?: "UNKNOWN"
}
