package com.waktusolat.widget

/**
 * Approximate coordinates per JAKIM zone prefix, used only for the qibla bearing when the
 * user picked a zone manually instead of using GPS.
 *
 * Accuracy: the domestic worst case (Kuala Lumpur vs Kota Kinabalu) is under 2° of bearing,
 * so rounding to whole degrees plus an eight-way arrow stays honest. A GPS fix, when present,
 * always wins over this table.
 */
object ZoneGeo {

    private val PREFIX_COORDS: Map<String, Pair<Double, Double>> = mapOf(
        "JHR" to (1.4927 to 103.7414),   // Johor Bahru
        "KDH" to (6.1248 to 100.3678),   // Alor Setar
        "KTN" to (6.1254 to 102.2381),   // Kota Bharu
        "MLK" to (2.1896 to 102.2501),   // Melaka
        "NSN" to (2.7258 to 101.9424),   // Seremban
        "PHG" to (3.8077 to 103.3260),   // Kuantan
        "PNG" to (5.4141 to 100.3288),   // George Town
        "PRK" to (4.5975 to 101.0901),   // Ipoh
        "PLS" to (6.4414 to 100.1986),   // Kangar
        "SBH" to (5.9804 to 116.0735),   // Kota Kinabalu
        "SWK" to (1.5533 to 110.3592),   // Kuching
        "SGR" to (3.0738 to 101.5183),   // Shah Alam
        "TRG" to (5.3302 to 103.1408),   // Kuala Terengganu
        "WLY" to (3.1390 to 101.6869),   // Kuala Lumpur
        "LBN" to (5.2831 to 115.2308),   // Labuan
        "PJY" to (2.9264 to 101.6964),   // Putrajaya
    )

    /** Kuala Lumpur — the historical hardcoded fallback, kept only as a last resort. */
    val FALLBACK: Pair<Double, Double> = 3.1390 to 101.6869

    fun coordsFor(zoneCode: String?): Pair<Double, Double> {
        if (zoneCode.isNullOrBlank()) return FALLBACK
        return PREFIX_COORDS[zoneCode.take(3).uppercase()] ?: FALLBACK
    }
}
