package io.github.okexodus.openknights.server.session

/**
 * Which route of the game service answers which request (the reference's route tables, in its dispatch order).
 * The route modules' own opcode lists are kept here until each module is ported; a test checks they agree.
 */
object Routes {
    /** The acquisition family (item use, boxes, merge, shops, claims, Rebirth, Combine, Reborn). */
    val ACQUISITION: Set<Int> = linkedSetOf(
            73, 4099, 803, 801, 321, 1251, 1057, 75, 2725, 2723, 641, 83, 85, 89, 2051, 2633, 3137, 1249, 1253, 1121,
            1025, 1669, 1125, 1027, 1029, 101, 99, 2055, 2631, 87, 95)

    /** The daily systems (quests, board, sign-in, castle, training, events, …). */
    val DAILY: Set<Int> = linkedSetOf(
            1635, 1637, 1641, 3077, 1665, 1091, 1093, 481, 2541, 2371, 2373, 2375, 261, 259, 263, 265, 269, 271, 273, 275,
            423, 161, 545, 97, 2179, 739, 737, 769, 745, 749, 781, 1763, 1767, 1769, 1777, 1779, 1643, 3747, 3731, 3753,
            2113, 2115, 2117, 2123, 2119, 2121, 2125, 3723, 3725, 3727, 1089, 2539, 2369, 2377, 3073, 417, 421, 2471, 163, 777,
            753, 755, 751, 1761, 1765, 1771, 1773, 1775, 3075, 425, 771, 773, 775)

    /** The daily requests answered as queries (no transaction). */
    val DAILY_QUERIES: Set<Int> = linkedSetOf(
            1089, 2539, 2369, 2377, 3073, 417, 421, 2471, 163, 777, 753, 755, 751, 1761, 1765, 1771, 1773, 1775)

    /** The social layer: guild, friends, chat, mail. */
    val SOCIAL: Set<Int> = linkedSetOf(
            2145, 2147, 2149, 2151, 2171, 2175, 2183, 2193, 2203, 2441, 2155, 2159, 2161, 2165, 2167, 2173, 2177, 2181, 2185, 2169,
            2153, 2157, 2201, 2163, 2205, 2435, 2437, 2439, 2443, 2187, 2197, 2199, 353, 355, 359, 361, 363, 365, 387, 385,
            257, 195, 197, 199, 201, 203, 205, 207, 449)

    /** The small features of the sweep (album, Rebirth shop timer, signature, warehouse slot, totems, special events). */
    val SWEEP: Set<Int> = linkedSetOf(
            25, 77, 513, 515, 577, 1633, 1639, 1645, 1647, 1649, 1651, 1667, 1671, 2529, 3937, 3939, 3941)

    val GOALS: Set<Int> = linkedSetOf(2657)

    val CAMPAIGN: Set<Int> = linkedSetOf(129, 131, 133, 135, 137, 2785)

    /** Gear / jewelry / rune equip, runes combine, lineup, position, captain. */
    val FORMATION: Set<Int> = linkedSetOf(67, 35, 65, 79, 2625, 1217, 1219, 1221)

    /** Requests answered with nothing on purpose (no waiting sender, and any reply would be wrong). */
    val IGNORED: Map<Int, String> = linkedMapOf(
            1729 to "offline EXP / Gold reward claim",
            673 to "guide progress checkpoint")

    /** Requests of features not implemented whose screens wait: S6 102 ends the wait, nothing changes. */
    val UNIMPLEMENTED_WAITING: Map<Int, String> = linkedMapOf(
            323 to "summon lot reset (waits)",
            2403 to "one-time gift code (waits)",
            1603 to "invite reward",
            2305 to "share reward",
            2401 to "app-store rating reward",
            1825 to "inherit (waits)",
            2053 to "inherit (waits)",
            2057 to "gear enchanting (waits)",
            2059 to "gear enchanting magic (waits)",
            2061 to "gear enchanting keep (waits)",
            2635 to "jewelry enchanting (waits)",
            2637 to "jewelry enchanting magic (waits)",
            2639 to "jewelry enchanting keep (waits)",
            2081 to "returning-player leader pick (waits)",
            2531 to "totem upgrade resources",
            2533 to "totem upgrade (waits)",
            2535 to "totem inherit (waits)",
            2537 to "totem (waits)",
            2627 to "jewelry Fortify (waits)",
            483 to "title upgrade",
            779 to "Recruit Card: Castle recruit of a named player",
            1123 to "assistance hero reward",
            2275 to "Mystic Store purchase")

    /**
     * Background queries answered with the replies the live server gave while the mode held nothing for the account
     * (after initialization only).
     */
    val EMPTY_MODE_REPLIES: Map<Int, List<Pair<Int, ByteArray>>> = linkedMapOf(
            3625 to listOf(3624 to ByteArray(1)),
            1315 to listOf(1632 to ByteArray(1), 1634 to ByteArray(6)),
            1159 to listOf(1222 to ByteArray(1)),
            1601 to listOf(1728 to ByteArray(1)))

    /** Text 8069575 "You cannot challenge now": the combat refusal. */
    const val COMBAT_REFUSAL_CODE = 69575

    /** Battle starts of the modes that wait for their own combat phase: refused at once, nothing changes. */
    val COMBAT: Map<Int, String> = linkedMapOf(
            225 to "tower step",
            243 to "tower auto-battle",
            245 to "tower auto step",
            209 to "mail 'ATK' revenge fight",
            389 to "friend spar from player info",
            419 to "arena challenge",
            741 to "castle 'Fight' when recruited by another player",
            743 to "castle recruit battle",
            747 to "castle Absolve battle",
            1319 to "event-tower boss",
            1703 to "Hero's Gate battle",
            2189 to "guild war join",
            2191 to "guild Maske walk",
            2195 to "guild boss challenge",
            2343 to "labyrinth challenge",
            2599 to "demon battleground battle",
            2761 to "underground battle",
            3109 to "mine challenge",
            3617 to "constellation fight",
            3971 to "time rift battle")

    /** Screens of the battle modes that wait for their combat phase (after initialization only): the combat refusal. */
    val COMBAT_MODE: Map<Int, String> = linkedMapOf(
            2543 to "city Tower entry (waits)",
            2787 to "city Tower screen (waits)",
            239 to "city Tower data (waits)",
            2209 to "Tower courage battle info (waits)",
            227 to "tower floor / event-tower stage select",
            247 to "tower number puzzle claim (waits)",
            1317 to "event-tower boss info (waits)",
            1321 to "event-tower boss Remove CD (waits)",
            1323 to "event-tower victory claim (waits)",
            1325 to "event-tower rank (waits)",
            1327 to "event-tower rank reward (waits)",
            2337 to "labyrinth entry (waits)",
            2339 to "activity rank (waits)",
            2341 to "labyrinth start (waits)",
            2345 to "labyrinth revive (waits)",
            2349 to "labyrinth rank reward (waits)",
            2351 to "labyrinth heal (waits)",
            3649 to "Hero's Gate entry (waits)",
            1697 to "Hero's Gate chapter (waits)",
            1699 to "Hero's Gate team",
            1701 to "Hero's Gate ask for help (waits)",
            1707 to "Hero's Gate reward (waits)",
            3629 to "Hero's Gate all rewards (waits)",
            3657 to "Hero's Gate reset (waits)",
            3665 to "Hero's Gate chapter award (waits)",
            3873 to "King's Gate entry (waits)",
            3841 to "King's Gate state (waits)",
            2603 to "Demon Battleground entry (waits)",
            2597 to "Demon Battleground stage",
            2605 to "Demon Battleground draw (waits)",
            2689 to "strong-point state (waits)",
            2691 to "strong-point request",
            2753 to "Underground entry",
            2757 to "Underground team",
            2759 to "Underground lineup",
            2763 to "Underground reset",
            2849 to "World Boss (waits)",
            3105 to "mine info",
            3107 to "mine search",
            3111 to "mine set hero",
            3113 to "mine reap",
            3623 to "constellation upgrade (waits)",
            3973 to "time rift reward box",
            229 to "tower meet-player battle (waits)",
            231 to "tower top-player battle (waits)",
            241 to "tower boss battle (waits)",
            249 to "tower guild boss (waits)",
            2789 to "city Tower state (waits)",
            2243 to "tower task give up (waits)",
            2245 to "tower task (waits)",
            2211 to "courage battle rank (waits)",
            2213 to "courage battle (waits)",
            2215 to "courage battle rank reward (waits)",
            2545 to "new tower (waits)",
            2547 to "new tower room move (waits)",
            2549 to "new tower room item (waits)",
            2551 to "new tower room item (waits)",
            2553 to "new tower merchant (waits)",
            2347 to "labyrinth refresh (waits)",
            1705 to "Hero's Gate report (waits)",
            1709 to "Hero's Gate team create / ask (waits)",
            1711 to "Hero's Gate player team (waits)",
            3627 to "Hero's Gate fight record delete (waits)",
            3843 to "King's Gate (waits)",
            3845 to "King's Gate (waits)",
            3847 to "King's Gate reward (waits)",
            3849 to "King's Gate (waits)",
            3851 to "King's Gate (waits)",
            3853 to "King's Gate (waits)",
            3855 to "King's Gate (waits)",
            3865 to "King's Gate (waits)",
            3867 to "King's Gate (waits)",
            3869 to "King's Gate screen (waits)",
            3877 to "King's Gate (waits)",
            3857 to "King's Gate drag",
            3859 to "King's Gate drag",
            3861 to "King's Gate drag",
            3863 to "King's Gate room limit",
            3875 to "King's Gate reports",
            2595 to "Demon Battleground battle (waits)",
            2601 to "Demon Battleground box (waits)",
            2693 to "strong-point sign-up (waits)",
            2695 to "strong-point reward (waits)",
            2697 to "strong-point inspire (waits)",
            2699 to "strong-point battle (waits)",
            2701 to "strong-point battle (waits)",
            2703 to "strong-point battle report (waits)",
            2755 to "Underground map unlock",
            2851 to "World Boss player info (waits)",
            2853 to "World Boss reward (waits)",
            2855 to "World Boss challenge (waits)",
            2857 to "World Boss buy attempts (waits)",
            3621 to "constellation add (waits)",
            3115 to "super mine info",
            3117 to "super mine detail",
            3119 to "super mine challenge",
            3121 to "super mine set hero",
            3123 to "super mine reap",
            3125 to "super mine abandon",
            3969 to "time rift map unlock",
            3975 to "time rift buy attempts",
            2465 to "cross-server arena qualifying",
            2467 to "cross-server arena match battle",
            2469 to "cross-server arena team",
            2473 to "cross-server arena elimination",
            2475 to "cross-server arena top 16",
            2477 to "cross-server arena bet",
            2479 to "cross-server arena replay",
            2791 to "cross-server arena rank reward",
            3139 to "Labyrinth Store list (waits)",
            3141 to "Labyrinth Store paid refresh (waits)",
            3143 to "Labyrinth Store buy (waits)",
            233 to "Tower merchant purchase")
}
