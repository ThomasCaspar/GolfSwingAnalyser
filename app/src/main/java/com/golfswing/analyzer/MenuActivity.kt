package com.golfswing.analyzer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.golfswing.analyzer.databinding.ActivityMenuBinding
import java.io.File

/** Menu principal : analyse en direct, analyse d'une vidéo enregistrée, ou session complète. */
class MenuActivity : AppCompatActivity() {

    private lateinit var b: ActivityMenuBinding

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVideo(uri, manual = false)
    }

    private val pickManualVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVideo(uri, manual = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnLive.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        b.btnAnalyzeFile.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        b.btnSession.setOnClickListener { startActivity(Intent(this, SessionActivity::class.java)) }
        b.btnManualFile.setOnClickListener { pickManualVideo.launch(arrayOf("video/*")) }
    }

    /** Copie la vidéo choisie dans le cache de l'app, puis ouvre l'analyse de session. */
    private fun importVideo(uri: Uri, manual: Boolean) {
        b.tvMenuStatus.visibility = View.VISIBLE
        b.tvMenuStatus.text = getString(R.string.menu_importing)
        Thread {
            val out = File(cacheDir, "imported_${System.currentTimeMillis()}.mp4")
            val ok = try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    false
                } else {
                    input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    true
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    b.tvMenuStatus.visibility = View.GONE
                    if (manual) {
                        // Lecteur seul, mode manuel activé : aucune détection automatique
                        startActivity(Intent(this, ReviewActivity::class.java).apply {
                            putExtra(ReviewActivity.EXTRA_VIDEO, out.absolutePath)
                            putExtra(ReviewActivity.EXTRA_SLOWMO, false)
                            putExtra(ReviewActivity.EXTRA_FPS, 60)
                            putExtra(ReviewActivity.EXTRA_FOCAL_RATIO, 0.75)
                            putExtra(ReviewActivity.EXTRA_HFOV, 60.0)
                            putExtra(ReviewActivity.EXTRA_MANUAL, true)
                        })
                    } else {
                        startActivity(Intent(this, SessionAnalysisActivity::class.java).apply {
                            putExtra(SessionAnalysisActivity.EXTRA_VIDEO, out.absolutePath)
                            putExtra(SessionAnalysisActivity.EXTRA_FOCAL, 0.75)
                        })
                    }
                } else {
                    b.tvMenuStatus.text = getString(R.string.menu_import_failed)
                }
            }
        }.start()
    }
}
