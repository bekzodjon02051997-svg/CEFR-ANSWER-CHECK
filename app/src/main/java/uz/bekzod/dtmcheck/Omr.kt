package uz.bekzod.dtmcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Calib(val ox: Float, val oy: Float, val sc: Float) {
    fun x(v: Float) = (v - 540f) * sc + 540f + ox
    fun y(v: Float) = (v - 762f) * sc + 762f + oy
}

class Sheet(val warped: Mat, val ink: BooleanArray)

class Report(val lc: Int, val rc: Int, val wrong: List<String>, val ok: Map<String, Boolean>)

class Votes {
    private val m = HashMap<String, ArrayDeque<String>>()
    fun add(r: Map<String, String>) {
        for ((k, v) in r) {
            val d = m.getOrPut(k) { ArrayDeque() }
            d.addLast(v)
            if (d.size > 5) d.removeFirst()
        }
    }
    fun clear() = m.clear()
    fun current(): Map<String, String> = m.mapValues { (_, d) ->
        d.groupingBy { it }.eachCount().entries
            .maxWith(compareBy({ it.value }, { d.lastIndexOf(it.key) })).key
    }
}

object Omr {
    const val W = Template.W
    const val H = Template.H
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val disk: List<IntArray> = run {
        val r = ArrayList<IntArray>()
        for (dy in -7..7) for (dx in -7..7) if (dx * dx + dy * dy <= 49) r.add(intArrayOf(dx, dy))
        r
    }

    // ------------------------------------------------------------ varaqni topish
    fun findSheet(frame: Mat): Sheet? {
        val fw = frame.cols(); val fh = frame.rows()
        val sc = 640.0 / max(fw, fh)
        val small = Mat(); val gray = Mat(); val edges = Mat()
        Imgproc.resize(frame, small, Size(fw * sc, fh * sc), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(gray, edges, 40.0, 120.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val cs = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, cs, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        var best: Array<Point>? = null
        var bestArea = 0.0
        val minArea = small.cols() * small.rows() * 0.25
        for (c in cs) {
            val a = Imgproc.contourArea(c)
            if (a < minArea || a <= bestArea) continue
            val c2 = MatOfPoint2f(*c.toArray())
            val ap = MatOfPoint2f()
            Imgproc.approxPolyDP(c2, ap, 0.03 * Imgproc.arcLength(c2, true), true)
            if (ap.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*ap.toArray()))) {
                best = ap.toArray(); bestArea = a
            }
        }
        small.release(); gray.release(); edges.release()
        val cands = ArrayList<Array<Point>>()
        if (best != null) cands.add(order(Array(4) { Point(best[it].x / sc, best[it].y / sc) }))
        cands.add(fallbackQuad(fw, fh))
        for (q in cands) {
            val s = warp(frame, q)
            if (s != null) return s
        }
        return null
    }

    private fun fallbackQuad(fw: Int, fh: Int): Array<Point> {
        var gw = 0.88 * fw
        var gh = gw * 1.4125
        if (gh > 0.96 * fh) { gh = 0.96 * fh; gw = gh / 1.4125 }
        val x0 = (fw - gw) / 2; val y0 = (fh - gh) / 2
        return arrayOf(Point(x0, y0), Point(x0 + gw, y0), Point(x0 + gw, y0 + gh), Point(x0, y0 + gh))
    }

    private fun order(p: Array<Point>): Array<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    private fun dist(a: Point, b: Point) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    private fun warp(frame: Mat, q0: Array<Point>): Sheet? {
        var q = q0
        if (dist(q[0], q[1]) > dist(q[0], q[3])) q = arrayOf(q[3], q[0], q[1], q[2])
        val src = MatOfPoint2f(*q)
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(W - 1.0, 0.0), Point(W - 1.0, H - 1.0), Point(0.0, H - 1.0))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        var out = Mat()
        Imgproc.warpPerspective(frame, out, m, Size(W.toDouble(), H.toDouble()))
        var ink = computeInk(out)
        var l = density(ink, 30, 62, 40, 1480)
        var r = density(ink, 1018, 1050, 40, 1480)
        if (r > l) {
            val o2 = Mat()
            Core.rotate(out, o2, Core.ROTATE_180)
            out.release(); out = o2
            ink = computeInk(out)
            l = density(ink, 30, 62, 40, 1480)
            r = density(ink, 1018, 1050, 40, 1480)
        }
        // chap tomondagi qora belgilar (timing marks) bo'lishi shart
        if (l < 0.06 || r > 0.04) { out.release(); return null }
        return Sheet(out, ink)
    }

    private fun density(ink: BooleanArray, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        var c = 0; var n = 0
        for (y in y0 until y1 step 2) for (x in x0 until x1) { n++; if (ink[y * W + x]) c++ }
        return c.toFloat() / max(n, 1)
    }

    /** "Siyoh" = qora/ko'k/kulrang ish; bosma qizil chiziqlar va harflar hisobga olinmaydi */
    private fun computeInk(m: Mat): BooleanArray {
        val buf = ByteArray(W * H * 4)
        m.get(0, 0, buf)
        val hist = IntArray(256)
        var total = 0
        var i = 0
        while (i < W * H) {
            val g = gray(buf, i); hist[g]++; total++; i += 7
        }
        var acc = 0; var p90 = 255
        for (g in 0..255) { acc += hist[g]; if (acc >= 0.9 * total) { p90 = g; break } }
        val gt = p90 * 0.5f; val rt = p90 * 0.25f
        val ink = BooleanArray(W * H)
        for (k in 0 until W * H) {
            val r = buf[k * 4].toInt() and 255
            val g = buf[k * 4 + 1].toInt() and 255
            val b = buf[k * 4 + 2].toInt() and 255
            val gr = (r * 30 + g * 59 + b * 11) / 100
            ink[k] = gr < gt && (r - max(g, b)) < rt
        }
        return ink
    }

    private fun gray(buf: ByteArray, k: Int): Int {
        val r = buf[k * 4].toInt() and 255
        val g = buf[k * 4 + 1].toInt() and 255
        val b = buf[k * 4 + 2].toInt() and 255
        return (r * 30 + g * 59 + b * 11) / 100
    }

    // ------------------------------------------------------------ o'qish
    fun read(sheet: Sheet, c: Calib): Map<String, String> {
        val res = HashMap<String, String>()
        for (q in Template.all) {
            if (q.opts != null) res[q.key] = readBubble(sheet.ink, q, c)
            else res[q.key] = readText(sheet.ink, q.box!!, c)
        }
        return res
    }

    private fun fillAt(ink: BooleanArray, cx: Int, cy: Int): Float {
        var cnt = 0; var n = 0
        for (d in disk) {
            val x = cx + d[0]; val y = cy + d[1]
            if (x < 0 || y < 0 || x >= W || y >= H) continue
            n++; if (ink[y * W + x]) cnt++
        }
        return if (n == 0) 0f else cnt.toFloat() / n
    }

    private fun readBubble(ink: BooleanArray, q: Q, c: Calib): String {
        val fills = q.opts!!.map { (_, p) ->
            val cx = c.x(p.x).toInt(); val cy = c.y(p.y).toInt()
            var best = 0f
            for (dy in -3..3 step 3) for (dx in -3..3 step 3) best = max(best, fillAt(ink, cx + dx, cy + dy))
            best
        }
        val order = fills.indices.sortedByDescending { fills[it] }
        val top = fills[order[0]]
        val second = if (order.size > 1) fills[order[1]] else 0f
        if (top < 0.35f) return ""
        if (second > 0.35f && second > 0.6f * top) return "*"
        return q.opts[order[0]].first.toString()
    }

    private fun readText(ink: BooleanArray, r: IntArray, c: Calib): String {
        val x0 = c.x(r[0].toFloat()).toInt().coerceIn(0, W - 2)
        val x1 = c.x(r[2].toFloat()).toInt().coerceIn(x0 + 1, W - 1)
        val y0 = c.y(r[1].toFloat()).toInt().coerceIn(0, H - 2)
        val y1 = c.y(r[3].toFloat()).toInt().coerceIn(y0 + 1, H - 1)
        var cnt = 0
        for (y in y0 until y1) for (x in x0 until x1) if (ink[y * W + x]) cnt++
        if (cnt < 40) return ""
        val pad = 8; val s = 2
        val bw = (x1 - x0 + 2 * pad) * s; val bh = (y1 - y0 + 2 * pad) * s
        val px = IntArray(bw * bh) { Color.WHITE }
        for (y in y0 until y1) for (x in x0 until x1) if (ink[y * W + x]) {
            val bx = (x - x0 + pad) * s; val by = (y - y0 + pad) * s
            for (j in 0 until s) for (i in 0 until s) px[(by + j) * bw + bx + i] = Color.BLACK
        }
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
        return try {
            val t = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            t.text.uppercase().filter { it.isLetterOrDigit() }
        } catch (e: Exception) { "" } finally { bmp.recycle() }
    }

    // ------------------------------------------------------------ baholash
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }.replace('0', 'O').replace('1', 'I')

    fun grade(cur: Map<String, String>, keys: Keys): Report {
        var lc = 0; var rc = 0
        val wrong = ArrayList<String>(); val ok = HashMap<String, Boolean>()
        for (q in Template.all) {
            val k = keys.get(q.sec, q.num)
            val got = cur[q.key] ?: ""
            val good = got.isNotEmpty() && norm(got) == norm(k)
            ok[q.key] = good
            if (good) { if (q.sec == 0) lc++ else rc++ }
            else wrong.add("${q.name}: ${if (got.isEmpty()) "—" else got}  (to'g'ri: $k)")
        }
        return Report(lc, rc, wrong, ok)
    }

    // ------------------------------------------------------------ ko'rsatma rasm
    fun overlay(sheet: Sheet, cur: Map<String, String>, keys: Keys, rep: Report, c: Calib): Bitmap {
        val full = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(sheet.warped, full)
        val tw = 360; val th = 508
        val bmp = Bitmap.createScaledBitmap(full, tw, th, true)
        full.recycle()
        val cv = Canvas(bmp)
        val k = tw.toFloat() / W
        val green = Paint().apply { color = Color.rgb(0, 170, 0); style = Paint.Style.STROKE; strokeWidth = 2.5f }
        val red = Paint().apply { color = Color.rgb(220, 0, 0); style = Paint.Style.STROKE; strokeWidth = 2.5f }
        for (q in Template.all) {
            val good = rep.ok[q.key] == true
            if (q.opts != null) {
                val got = cur[q.key] ?: ""
                val key = keys.get(q.sec, q.num)
                for ((ch, p) in q.opts) {
                    val cx = c.x(p.x) * k; val cy = c.y(p.y) * k
                    if (ch.toString() == key) cv.drawCircle(cx, cy, 5.5f, green)
                    else if (ch.toString() == got && !good) cv.drawCircle(cx, cy, 5.5f, red)
                }
            } else {
                val r = q.box!!
                cv.drawRect(c.x(r[0].toFloat()) * k, c.y(r[1].toFloat()) * k, c.x(r[2].toFloat()) * k, c.y(r[3].toFloat()) * k,
                    if (good) green else red)
            }
        }
        return bmp
    }
}
