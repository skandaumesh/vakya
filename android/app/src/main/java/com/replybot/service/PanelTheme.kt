package com.replybot.service

/**
 * Background choices for the chat panel and bubble ring. "auto" follows the phone's dark
 * mode (White by day, Navy at night). Colours are ARGB; Glass is see-through.
 * Keep the names in sync with the extension's content.css themes.
 */
data class PanelTheme(
    val bg: Int,
    val fg: Int,
    val muted: Int,
    val chipBg: Int,
    val border: Int,
    val accent: Int,
    /** Text on an accent-filled pill. */
    val onAccent: Int,
) {
    companion object {
        val WHITE = PanelTheme(0xFFFFFFFF.toInt(), 0xFF0E1A33.toInt(), 0xFF6E809F.toInt(), 0xFFF8FAFC.toInt(),
            0xFFECEFF4.toInt(), 0xFF0E1A33.toInt(), 0xFFFFFFFF.toInt())
        val NAVY = PanelTheme(0xFF0E1A33.toInt(), 0xFFF8FAFC.toInt(), 0xFF94A3B8.toInt(), 0xFF1F2A44.toInt(),
            0xFF33415C.toInt(), 0xFFF5C86B.toInt(), 0xFF0E1A33.toInt())
        val BLACK = PanelTheme(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF9CA3AF.toInt(), 0xFF141414.toInt(),
            0xFF262626.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val ROSE = PanelTheme(0xFFFFF1F3.toInt(), 0xFF4A1D2B.toInt(), 0xFF9F6B7A.toInt(), 0xFFFFFFFF.toInt(),
            0xFFF9D6DE.toInt(), 0xFFE11D48.toInt(), 0xFFFFFFFF.toInt())
        val MINT = PanelTheme(0xFFECFDF5.toInt(), 0xFF064E3B.toInt(), 0xFF5F8F7E.toInt(), 0xFFFFFFFF.toInt(),
            0xFFCDEFE0.toInt(), 0xFF059669.toInt(), 0xFFFFFFFF.toInt())
        /** Navy at ~88%, cards lighter and see-through, so the chat shows behind. */
        val GLASS = PanelTheme(0xE00E1A33.toInt(), 0xFFFFFFFF.toInt(), 0xFFCBD5E1.toInt(), 0x59FFFFFF.toInt(),
            0x40FFFFFF.toInt(), 0xFFF5C86B.toInt(), 0xFF0E1A33.toInt())

        /** Menu order: value to label. */
        val CHOICES = listOf(
            "auto" to "Auto", "white" to "White", "navy" to "Navy", "black" to "Black",
            "rose" to "Rose", "mint" to "Mint", "glass" to "Glass",
        )

        fun resolve(name: String, night: Boolean): PanelTheme = when (name) {
            "white" -> WHITE
            "navy" -> NAVY
            "black" -> BLACK
            "rose" -> ROSE
            "mint" -> MINT
            "glass" -> GLASS
            else -> if (night) NAVY else WHITE
        }
    }
}
