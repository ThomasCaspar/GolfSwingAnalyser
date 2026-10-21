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
        if (uri != null) importVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnLive.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        b.btnAnalyzeFile.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        b.btnSession.setOnClickListener { startActivity(Intent(this, SessionActivity::class.java)) }
    }

    /** Copie la vidéo choisie dans le cache de l'app, puis ouvre l'analyse de session. */
    private fun importVideo(uri: Uri) {
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
                    startActivity(Intent(this, SessionAnalysisActivity::class.java).apply {
                        putExtra(SessionAnalysisActivity.EXTRA_VIDEO, out.absolutePath)
                        putExtra(SessionAnalysisActivity.EXTRA_FOCAL, 0.75)
                    })
                } else {
                    b.tvMenuStatus.text = getString(R.string.menu_import_failed)
                }
            }
        }.start()
    }
}
