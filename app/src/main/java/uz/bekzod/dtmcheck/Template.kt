package uz.bekzod.dtmcheck

class Pt(val x: Float, val y: Float)

/** sec: 0 = Listening, 1 = Reading. opts != null -> doira (bubble); box != null -> yozma katak qatori */
class Q(val sec: Int, val num: Int, val opts: List<Pair<Char, Pt>>?, val box: IntArray?) {
    val key get() = "$sec-$num"
    val isText get() = box != null
    val name get() = (if (sec == 0) "L" else "R") + num
}

/** Koordinatalar: javoblar varaqasi 1080x1525 ga keltirilgandagi piksellar */
object Template {
    const val W = 1080
    const val H = 1525
    val all: List<Q> = build()

    private fun o(letters: String, x0: Float, step: Float, y: Float) =
        letters.mapIndexed { i, c -> c to Pt(x0 + step * i, y) }

    private fun build(): List<Q> {
        val l = ArrayList<Q>()
        fun b(s: Int, n: Int, letters: String, x0: Float, y: Int, step: Float = 21.7f) {
            l.add(Q(s, n, o(letters, x0, step, y.toFloat()), null))
        }
        fun t(s: Int, n: Int, x0: Int, x1: Int, y: Int) {
            l.add(Q(s, n, null, intArrayOf(x0, y - 13, x1, y + 13)))
        }
        // ---------- LISTENING ----------
        val y14 = listOf(484, 506, 527, 549)
        for ((i, y) in y14.withIndex()) { b(0, 1 + i, "ABC", 196f, y, 21.5f); b(0, 5 + i, "ABC", 304f, y, 22f) }
        listOf(649, 682, 716, 749, 782, 815).forEachIndexed { i, y -> t(0, 9 + i, 181, 548, y) }
        listOf(856, 877, 897, 919).forEachIndexed { i, y -> b(0, 15 + i, "ABCDEF", 195f, y) }
        listOf(963, 985, 1006, 1028, 1049).forEachIndexed { i, y -> b(0, 19 + i, "ABCDEFGH", 195f, y) }
        listOf(1093, 1114, 1133).forEachIndexed { i, y -> b(0, 24 + i, "ABC", 196f, y, 21.5f) }
        listOf(1093, 1113, 1133).forEachIndexed { i, y -> b(0, 27 + i, "ABC", 304f, y, 22f) }
        listOf(1235, 1268, 1301, 1334, 1368, 1401).forEachIndexed { i, y -> t(0, 30 + i, 181, 548, y) }
        // ---------- READING ----------
        listOf(535, 568, 601, 634, 667, 700).forEachIndexed { i, y -> t(1, 1 + i, 658, 1025, y) }
        listOf(746, 768, 790, 811, 833, 855, 876, 898).forEachIndexed { i, y -> b(1, 7 + i, "ABCDEFGHIJ", 672f, y) }
        listOf(942, 964, 985, 1006, 1028, 1049).forEachIndexed { i, y -> b(1, 15 + i, "ABCDEFGH", 672f, y) }
        listOf(1094, 1115, 1137, 1157).forEachIndexed { i, y -> b(1, 21 + i, "ABCD", 672f, y) }
        listOf(1094, 1115, 1136, 1156, 1177).forEachIndexed { i, y -> b(1, 25 + i, "ABC", 804f, y) }
        listOf(1278, 1311, 1345, 1378).forEachIndexed { i, y -> t(1, 30 + i, 658, 1025, y) }
        listOf(1418, 1440).forEachIndexed { i, y -> b(1, 34 + i, "ABCD", 672f, y) }
        return l
    }
}
