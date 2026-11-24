package com.krudoy.stlviewer

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.krudoy.stlviewer.model.Mesh
import com.krudoy.stlviewer.model.ModelLoader
import com.krudoy.stlviewer.render.ModelView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var modelView: ModelView
    private lateinit var titleView: TextView
    private lateinit var info: TextView
    private lateinit var hint: View
    private lateinit var recentList: LinearLayout
    private lateinit var recentFiles: RecentFiles
    private lateinit var progress: ProgressBar
    private lateinit var rollButtons: View
    private var loadJob: Job? = null
    private var currentUri: Uri? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access so the file can be reopened after the process is recreated.
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            load(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recentFiles = RecentFiles(this)
        setContentView(buildLayout())

        val restored = savedInstanceState?.getString(KEY_URI)?.let(Uri::parse)
        when {
            restored != null -> load(restored)
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> load(intent.data!!)
            else -> showEmpty()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::load)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString(KEY_URI, it.toString()) }
    }

    override fun onResume() { super.onResume(); modelView.onResume() }
    override fun onPause() { modelView.onPause(); super.onPause() }

    private fun load(uri: Uri) {
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        hint.visibility = View.GONE
        val name = displayName(uri)
        titleView.text = name ?: getString(R.string.app_name)
        info.text = getString(R.string.loading)

        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val length = fileSize(uri)
                    val stream = contentResolver.openInputStream(uri)
                        ?: error(getString(R.string.error_open))
                    stream.use { s ->
                        ModelLoader.load(s, length, File(cacheDir, "import.tmp")) { f ->
                            progress.post {
                                progress.isIndeterminate = false
                                progress.progress = (f * 100).toInt()
                            }
                        }
                    }
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { mesh ->
                currentUri = uri
                recentFiles.add(uri, name ?: uri.lastPathSegment ?: getString(R.string.app_name))
                modelView.setMesh(mesh)
                rollButtons.visibility = View.VISIBLE
                info.text = describe(mesh)
            }.onFailure { e ->
                if (e is OutOfMemoryError) {
                    info.text = getString(R.string.error_memory)
                } else {
                    info.text = getString(R.string.error_parse, e.message ?: e.javaClass.simpleName)
                }
                // A recent file that was moved or deleted: stop offering it.
                if (e is java.io.FileNotFoundException || e is SecurityException) recentFiles.remove(uri)
                if (currentUri == null) showStartPanel()
            }
        }
    }

    private fun showEmpty() {
        titleView.text = getString(R.string.app_name)
        info.text = ""
        showStartPanel()
    }

    private fun showStartPanel() {
        refreshRecentList()
        hint.visibility = View.VISIBLE
    }

    /** Rebuilds the "Recent" rows on the start screen. */
    private fun refreshRecentList() {
        val dp = resources.displayMetrics.density
        recentList.removeAllViews()
        val entries = recentFiles.list()
        if (entries.isEmpty()) return
        recentList.addView(TextView(this).apply {
            text = getString(R.string.recent)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 13f
            isAllCaps = true
            setPadding(0, (24 * dp).toInt(), 0, (6 * dp).toInt())
        })
        for (entry in entries) {
            recentList.addView(TextView(this).apply {
                text = entry.name
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.CENTER
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                val shape = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0x1AFFFFFF) }
                background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null)
                isClickable = true
                setOnClickListener { load(entry.uri) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * dp).toInt() })
        }
    }

    /** Title tap: quick switch between recent files without the system picker. */
    private fun showRecentMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val entries = recentFiles.list().filter { it.uri != currentUri }
        entries.forEachIndexed { i, e -> menu.menu.add(0, i, i, e.name) }
        menu.menu.add(0, OPEN_ITEM, entries.size, getString(R.string.open_other))
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == OPEN_ITEM) openModelPicker() else load(entries[item.itemId].uri)
            true
        }
        menu.show()
    }

    private fun describe(mesh: Mesh): String {
        val nf = NumberFormat.getIntegerInstance()
        val b = mesh.bounds
        val size = String.format(Locale.getDefault(), "%.2f × %.2f × %.2f", b.sizeX, b.sizeY, b.sizeZ)
        return getString(R.string.mesh_info, nf.format(mesh.triangleCount), size)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun fileSize(uri: Uri): Long {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length >= 0) return fd.length
            }
        }
        return -1
    }

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this)

        modelView = ModelView(this)
        root.addView(modelView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Plain text views as pill buttons: Button's built-in min height and padding
        // clip the label inside a compact bar on some devices.
        fun pillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            minWidth = (64 * dp).toInt()
            val shape = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(0x33FFFFFF) }
            background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(0x99000000.toInt())
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            // Tap the file name to switch to a recent file.
            setOnClickListener { v -> showRecentMenu(v) }
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(pillButton(getString(R.string.reset_view)) { modelView.resetView() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() })
        topBar.addView(pillButton(getString(R.string.open)) { openModelPicker() }.apply {
            // Long press shows every file, for providers that label models with odd types.
            setOnLongClickListener { openDocument.launch(arrayOf("*/*")); true }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        // Keep the orientation cube just below the top bar and clear of side insets.
        // 90° spin buttons, centered under the orientation cube.
        val cubeSize = (ModelView.CUBE_SIZE_DP * dp).toInt()
        val rollRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
            } }
            addView(pillButton("↺") { modelView.roll(90f) }.apply {
                contentDescription = getString(R.string.rotate_left); minWidth = 0
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                textSize = 18f
            }, lp())
            addView(pillButton("↻") { modelView.roll(-90f) }.apply {
                contentDescription = getString(R.string.rotate_right); minWidth = 0
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                textSize = 18f
            }, lp())
        }
        rollButtons = rollRow
        root.addView(rollRow, FrameLayout.LayoutParams(cubeSize + (24 * dp).toInt(), WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // Keep the orientation cube just below the top bar and clear of side insets.
        topBar.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, _ ->
            val rightInset = v.paddingRight - (12 * dp).toInt()
            modelView.setCubeInsets(bottom, rightInset)
            val lp = rollRow.layoutParams as FrameLayout.LayoutParams
            val top = bottom + (12 * dp).toInt() + cubeSize + (4 * dp).toInt()
            val right = rightInset // row is 24dp wider than the cube, so this centers it
            if (lp.topMargin != top || lp.rightMargin != right) {
                lp.topMargin = top; lp.rightMargin = right
                rollRow.post { rollRow.layoutParams = lp }
            }
        }

        info = TextView(this).apply {
            setTextColor(0xDDFFFFFF.toInt())
            textSize = 13f
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(0x99000000.toInt())
        }
        root.addView(info, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // Start screen: how-to text, then recently opened files.
        val hintText = TextView(this).apply {
            text = getString(R.string.empty_hint) + "\n\n" + getString(R.string.picker_hint)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { openModelPicker() }
        }
        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((32 * dp).toInt(), (96 * dp).toInt(), (32 * dp).toInt(), (72 * dp).toInt())
            addView(hintText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(recentList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        hint = ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@MainActivity).apply {
                addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Right above the 3D view, so the top bar and its buttons stay on top and tappable.
        root.addView(hint, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams((200 * dp).toInt(), WRAP_CONTENT, Gravity.CENTER))

        // Keep the bars clear of the status bar, cutouts and gesture navigation.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(left = bars.left + (12 * dp).toInt(), top = bars.top + (8 * dp).toInt(),
                right = bars.right + (12 * dp).toInt())
            info.updatePadding(left = bars.left + (12 * dp).toInt(), right = bars.right + (12 * dp).toInt(),
                bottom = bars.bottom + (8 * dp).toInt())
            insets
        }
        return root
    }

    private fun openModelPicker() = openDocument.launch(MODEL_MIME_TYPES)

    private companion object {
        const val KEY_URI = "uri"
        const val OPEN_ITEM = 1000

        /**
         * What the file picker shows. Android has no single registered type for these
         * formats, so this lists the ones apps and file providers use. Most providers
         * report unknown extensions (often .stl and .step) as octet-stream, so that is
         * included too; it still hides photos, videos, PDFs and documents.
         */
        val MODEL_MIME_TYPES = arrayOf(
            // STL
            "model/stl", "model/x.stl-binary", "model/x.stl-ascii", "application/sla",
            "application/vnd.ms-pki.stl", "application/x-navistyle",
            // 3MF
            "model/3mf", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
            // STEP
            "model/step", "model/x-step", "application/step", "application/stp", "application/x-step",
            // Unlabeled binary files
            "application/octet-stream",
        )
    }
}
