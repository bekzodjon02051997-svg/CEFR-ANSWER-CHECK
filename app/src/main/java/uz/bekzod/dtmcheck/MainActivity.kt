package uz.bekzod.dtmcheck

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var scoreTv: TextView
    private lateinit var wrongTv: TextView
    private lateinit var thumb: ImageView
    private val exec = Executors.newSingleThreadExecutor()
    private val votes = Votes()
    @Volatile private var keys: Keys = Keys.default()
    @Volatile private var cal = Calib(0f, 0f, 1f)
    @Volatile private var lastSeen = 0L
    private var keyEdit: EditText? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val txt = try { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
            keyEdit?.setText(txt)
        }
    }
    private val reqCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("Kamera ruxsati kerak")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) toast("OpenCV yuklanmadi")
        val prefs = getSharedPreferences("dtm", Context.MODE_PRIVATE)
        Keys.parse(prefs.getString("keys", Keys.DEFAULT) ?: Keys.DEFAULT).first?.let { keys = it }
        cal = Calib(prefs.getFloat("ox", 0f), prefs.getFloat("oy", 0f), prefs.getFloat("sc", 1f))
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else reqCam.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val sw = resources.displayMetrics.widthPixels
        val frame = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(GuideView(this), FrameLayout.LayoutParams(-1, -1))
        thumb = ImageView(this).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)) }
        frame.addView(thumb, FrameLayout.LayoutParams((sw * 0.40).toInt(), (sw * 0.40 * 508 / 360).toInt(), Gravity.TOP or Gravity.END))
        root.addView(frame, LinearLayout.LayoutParams(sw, sw * 4 / 3))

        scoreTv = TextView(this).apply {
            textSize = 20f; setTextColor(Color.BLACK); setPadding(dp(12), dp(8), dp(12), dp(4))
            text = "Varaqni kameraga tuting"
        }
        root.addView(scoreTv)
        wrongTv = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(160, 0, 0)); setPadding(dp(12), 0, dp(12), 0) }
        val sv = ScrollView(this).apply { addView(wrongTv) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 13f; setOnClickListener { f() } }
            .also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        btn("Kalitlar") { keysDialog() }
        btn("Sozlash") { calibDialog() }
        btn("Yangi varaq") { synchronized(votes) { votes.clear() }; scoreTv.text = "Yangi varaq"; wrongTv.text = ""; thumb.setImageBitmap(null) }
        root.addView(row)
        setContentView(root)
    }

    // ------------------------------------------------------------ kamera
    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        var mat: Mat? = null
        try {
            val bmp = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            val raw = Mat()
            Utils.bitmapToMat(bmp, raw)
            mat = Mat()
            when (rot) {
                90 -> Core.rotate(raw, mat, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(raw, mat, Core.ROTATE_180)
                270 -> Core.rotate(raw, mat, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> raw.copyTo(mat)
            }
            raw.release()
            val sheet = Omr.findSheet(mat)
            val now = System.currentTimeMillis()
            if (sheet == null) {
                if (now - lastSeen > 1500) runOnUiThread { scoreTv.text = "Varaq topilmadi — yashil ramkaga to'g'rilang"; thumb.setImageBitmap(null) }
                return
            }
            lastSeen = now
            val c = cal; val k = keys
            val readings = Omr.read(sheet, c)
            val cur: Map<String, String>
            synchronized(votes) { votes.add(readings); cur = votes.current() }
            val rep = Omr.grade(cur, k)
            val tb = Omr.overlay(sheet, cur, k, rep, c)
            sheet.warped.release()
            runOnUiThread {
                scoreTv.text = "Listening ${rep.lc}/35   Reading ${rep.rc}/35\nJami: ${rep.lc + rep.rc}/70"
                wrongTv.text = "Xato / bo'sh javoblar (${rep.wrong.size}):\n" + rep.wrong.joinToString("\n")
                thumb.setImageBitmap(tb)
            }
        } catch (e: Throwable) {
            runOnUiThread { scoreTv.text = "Xato: ${e.javaClass.simpleName}" }
        } finally {
            mat?.release()
            img.close()
        }
    }

    // ------------------------------------------------------------ dialoglar
    private fun keysDialog() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        val info = TextView(this).apply {
            text = "Tartib: Listening 1–35, keyin Reading 1–35 (jami 70 ta, bo'sh joy yoki vergul bilan). Fayl (.txt) ham yuklash mumkin."
            textSize = 12f
        }
        val et = EditText(this).apply { setText(keys.raw); minLines = 6; textSize = 13f; gravity = Gravity.TOP }
        keyEdit = et
        val load = Button(this).apply { text = "Fayldan yuklash"; setOnClickListener { pickFile.launch(arrayOf("text/*", "application/octet-stream")) } }
        lay.addView(info); lay.addView(et); lay.addView(load)
        AlertDialog.Builder(this).setTitle("To'g'ri javoblar (kalitlar)").setView(lay)
            .setPositiveButton("Saqlash") { _, _ ->
                val (k, err) = Keys.parse(et.text.toString())
                if (k == null) toast(err) else {
                    keys = k
                    getSharedPreferences("dtm", Context.MODE_PRIVATE).edit().putString("keys", et.text.toString()).apply()
                    synchronized(votes) { votes.clear() }
                    toast("Kalitlar saqlandi")
                }
            }.setNegativeButton("Bekor", null).show()
    }

    private fun calibDialog() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun bar(label: String, max: Int, p: Int): SeekBar {
            lay.addView(TextView(this).apply { text = label })
            return SeekBar(this).apply { this.max = max; progress = p }.also { lay.addView(it) }
        }
        val sx = bar("Gorizontal siljish", 80, (cal.ox + 40).toInt())
        val sy = bar("Vertikal siljish", 80, (cal.oy + 40).toInt())
        val ss = bar("Masshtab", 20, ((cal.sc - 0.9f) * 100).toInt())
        AlertDialog.Builder(this).setTitle("Moslash (o'ng yuqoridagi rasmga qarang)").setView(lay)
            .setPositiveButton("Saqlash") { _, _ ->
                cal = Calib(sx.progress - 40f, sy.progress - 40f, 0.9f + ss.progress / 100f)
                getSharedPreferences("dtm", Context.MODE_PRIVATE).edit()
                    .putFloat("ox", cal.ox).putFloat("oy", cal.oy).putFloat("sc", cal.sc).apply()
                synchronized(votes) { votes.clear() }
            }.setNegativeButton("Bekor", null).show()
    }

    /** Varaqni joylash uchun yashil punktir ramka */
    private class GuideView(ctx: Context) : View(ctx) {
        private val p = Paint().apply {
            color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f
            pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
        }
        override fun onDraw(c: Canvas) {
            var gw = 0.88f * width; var gh = gw * 1.4125f
            if (gh > 0.96f * height) { gh = 0.96f * height; gw = gh / 1.4125f }
            val x0 = (width - gw) / 2; val y0 = (height - gh) / 2
            c.drawRect(x0, y0, x0 + gw, y0 + gh, p)
        }
    }
}
