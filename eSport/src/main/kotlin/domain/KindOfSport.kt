package com.competra.domain


sealed class KindOfSport(val name: String) {

    data object Orienteering: KindOfSport("Orienteering")

    data object CrossCountrySki: KindOfSport("CrossCountrySki")

    data object TrailRunning : KindOfSport("TrailRunning")

    companion object {
        val all = listOf(Orienteering, CrossCountrySki, TrailRunning)

        fun fromName(name: String): KindOfSport? =
            all.find { it.name == name }

        fun fromSerializedName(value: String): KindOfSport? = when (value) {
            "orienteering" -> Orienteering
            "cross_country_ski" -> CrossCountrySki
            "trail_running" -> TrailRunning
            else -> null
        }

    }

}