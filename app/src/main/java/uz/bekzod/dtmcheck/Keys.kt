package uz.bekzod.dtmcheck

class Keys(val raw: String, private val l: List<String>, private val r: List<String>) {
    fun get(sec: Int, num: Int): String = (if (sec == 0) l else r)[num - 1]

    companion object {
        const val DEFAULT = "LISTENING: A C A A C A B B CLARKE 9 OFF 3RD UNIFORMS LOCKERS E F B C B C E H A B A B C B C TEMPERATURE BATTERY RAINWATER TIMBER LEGISLATION SUNLIGHT\n" +
            "READING: CAMERA VIDEOS USERS USE CHARGING PRICES I C G D H F J E A F D C H B A B D B A B C A B AMBITION ENTERPRISE PROTECTION THREAT A C"

        fun default(): Keys = parse(DEFAULT).first!!

        /** 70 ta token: avval Listening 1..35, keyin Reading 1..35. Qaytaradi: (Keys?, xato matni) */
        fun parse(raw: String): Pair<Keys?, String> {
            val toks = raw.split(Regex("[\\s,;|]+"))
                .map { it.trim().trim(':').uppercase() }
                .filter { it.isNotEmpty() && it != "LISTENING" && it != "READING" }
            if (toks.size != 70) return Pair(null, "70 ta kalit kerak (Listening 35 + Reading 35), topildi: ${toks.size}")
            return Pair(Keys(raw, toks.subList(0, 35), toks.subList(35, 70)), "")
        }
    }
}
