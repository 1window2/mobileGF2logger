package dev.gf2log.app

/** Android application package IDs whose traffic GF2logger may route through its VPN. */
object SupportedGamePackages {
    const val HAOPLAY = "com.haoplay.game.and.exilium"
    const val DARKWINTER = "com.Sunborn.SnqxExilium.Glo"

    val all: List<String> = listOf(HAOPLAY, DARKWINTER)
}
