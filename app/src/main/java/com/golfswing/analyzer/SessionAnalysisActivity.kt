package com.golfswing.analyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.golfswing.analyzer.databinding.ActivitySessionAnalysisBinding
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Analyse d'une vidéo de session : comptage des swings, tracé automatique de chaque balle,
 * puis proposition d'une analyse par IA (prompt + vidéo à envoyer à Gemini, par exemple).
 */
class SessionAnalysisActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_FOCAL = "focal"
        // Fenêtre enregistrée autour d'un swing : une marge avant le repère, et le vol de la balle après le swing
        const val TRIM_BEFORE_S = 0.5
        // Longueur de la vidéo analysée pour un swing : seule cette fenêtre est découpée et chargée
        const val CLIP_LENGTH_S = 15.0
    }

    private class Row(
        val index: Int,
        val startT: Double,
        val endT: Double,
        val seedT: Double,
        val seedU: Double,
        val seedV: Double,
        val found: Boolean,
        val result: SwingResult?
    )

    private lateinit var b: ActivitySessionAnalysisBinding
    private lateinit var videoPath: String
    private var focal = 0.75
    private val rows = ArrayList<Row>()
    private var aiPrompt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionAnalysisBinding.inflate(layoutInflater)
        setContentView(b.root)
        videoPath = intent.getStringExtra(EXTRA_VIDEO) ?: run { finish(); return }
        focal = intent.getDoubleExtra(EXTRA_FOCAL, 0.75)
        b.tvSessionTitle.text = getString(R.string.session_analysis_title)
        b.tvSessionProgress.text = "Recherche des swings…"
        Thread { runSession() }.start()
    }

    private fun runSession() {
        val scan = SessionScanner.scan(videoPath) { p ->
            runOnUiThread { b.tvSessionProgress.text = "Recherche des swings… ${(p * 100).toInt()} %" }
        }
        if (scan == null) {
            runOnUiThread { b.tvSessionProgress.text = getString(R.string.session_scan_failed) }
            return
        }
        if (scan.swings.isEmpty()) {
            runOnUiThread { b.tvSessionProgress.text = "Aucun swing détecté dans cette vidéo." }
            return
        }
        runOnUiThread { b.tvSessionProgress.text = "${scan.swings.size} swing(s) trouvé(s). Analyse des trajectoires…" }

        scan.swings.forEachIndexed { i, s ->
            val res = if (!s.seedFound) null else try {
                SwingAnalyzer.analyze(videoPath, listOf(doubleArrayOf(s.seedT, s.seedU, s.seedV)), focal)
            } catch (_: Exception) {
                null
            }
            val row = Row(i + 1, s.runStart, s.runEnd, s.seedT, s.seedU, s.seedV, s.seedFound, res)
            rows.add(row)
            runOnUiThread {
                addRow(row)
                b.tvSessionProgress.text = "Analyse ${i + 1}/${scan.swings.size}"
            }
        }

        runOnUiThread {
            val ok = rows.count { it.result != null }
            b.tvSessionProgress.text = "$ok trajectoire(s) sur ${rows.size} swing(s). Touchez un swing pour voir son tracé."
            askAi()
        }
    }

    private fun addRow(row: Row) {
        val r = row.result
        val label = buildString {
            append("Swing ${row.index} · ${mmss(row.startT)}")
            when {
                !row.found -> append(" · balle non trouvée, touchez-la")
                r == null -> append(" · trajectoire non détectée")
                r.carryM > 0 -> append(
                    " · %.0f km/h · carry %.0f m · %s · fiabilité %d %%"
                        .format(r.ballSpeed * 3.6, r.carryM, r.shape, r.confidence)
                )
                else -> append(" · trajectoire non fiable · fiabilité %d %%".format(r.confidence))
            }
        }
        val btn = MaterialButton(this).apply {
            text = label
            setOnClickListener { openReview(row) }
        }
        b.llSwings.addView(
            btn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 12 }
        )
    }

    /** Ouvre la revue de ce swing ; la balle est déjà repérée quand la détection l'a trouvée. */
    private fun openReview(row: Row) {
        startActivity(Intent(this, ReviewActivity::class.java).apply {
            putExtra(ReviewActivity.EXTRA_VIDEO, videoPath)
            putExtra(ReviewActivity.EXTRA_SLOWMO, false)
            putExtra(ReviewActivity.EXTRA_FPS, 60)
            putExtra(ReviewActivity.EXTRA_FOCAL_RATIO, focal)
            putExtra(ReviewActivity.EXTRA_HFOV, 60.0)
            putExtra(ReviewActivity.EXTRA_TRIM_START, Math.max(0.0, Math.min(row.seedT, row.startT) - TRIM_BEFORE_S))
            putExtra(ReviewActivity.EXTRA_TRIM_END, Math.max(0.0, Math.min(row.seedT, row.startT) - TRIM_BEFORE_S) + CLIP_LENGTH_S)
            if (row.found) {
                putExtra(ReviewActivity.EXTRA_SEED_T, row.seedT)
                putExtra(ReviewActivity.EXTRA_SEED_U, row.seedU)
                putExtra(ReviewActivity.EXTRA_SEED_V, row.seedV)
            }
        })
    }

    private fun askAi() {
        AlertDialog.Builder(this)
            .setTitle("Analyse par IA")
            .setMessage(getString(R.string.ai_question))
            .setPositiveButton(R.string.ai_yes) { _, _ -> showPrompt() }
            .setNegativeButton(R.string.ai_no, null)
            .show()
    }

    private fun showPrompt() {
        aiPrompt = buildPrompt()
        AlertDialog.Builder(this)
            .setTitle("Prompt pour l'IA")
            .setMessage(aiPrompt)
            .setPositiveButton(R.string.ai_share) { _, _ -> shareWithVideo() }
            .setNeutralButton(R.string.ai_copy) { _, _ -> copyPrompt() }
            .setNegativeButton(R.string.ai_close, null)
            .show()
    }

    private fun copyPrompt() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("prompt", aiPrompt))
        Toast.makeText(this, R.string.ai_copied, Toast.LENGTH_SHORT).show()
    }

    /** Envoie la vidéo et le prompt vers l'application choisie (Gemini, par exemple). */
    /** Envoie le prompt avec la vidéo. Une session à plus de 70 fps est d'abord copiée à 60 fps, plus légère. */
    private fun shareWithVideo() {
        b.tvSessionProgress.text = "Préparation de la vidéo pour l'IA…"
        Thread {
            val path = prepareAiVideo()
            runOnUiThread {
                b.tvSessionProgress.text = "Vidéo prête pour l'IA."
                sendToAi(path)
            }
        }.start()
    }

    private fun prepareAiVideo(): String {
        val fps = VideoExporter.frameRate(videoPath)
        if (fps <= 70.0) return videoPath
        val decimate = (fps / 60.0).roundToInt().coerceAtLeast(2)
        val out = File(cacheDir, "ai_share_60.mp4").absolutePath
        val ok = VideoExporter.export(
            src = videoPath,
            dst = out,
            rotation = readRotation(),
            fps = fps / decimate,
            overlayFrom = Double.MAX_VALUE,
            drawAt = { _, _ -> },
            onProgress = { p ->
                runOnUiThread { b.tvSessionProgress.text = "Préparation de la vidéo pour l'IA… ${(p * 100).toInt()} %" }
            },
            decimate = decimate,
            bitrate = 12_000_000
        )
        return if (ok) out else videoPath
    }

    private fun readRotation(): Int = try {
        MediaMetadataRetriever().use { m ->
            m.setDataSource(videoPath)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }
    } catch (_: Exception) {
        0
    }

    private fun sendToAi(path: String) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        } catch (_: Exception) {
            null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, aiPrompt)
            if (uri != null) {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("session", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        startActivity(Intent.createChooser(send, "Envoyer à une IA"))
    }

    private fun buildPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("Tu es un coach de golf expert. Je te joins la vidéo complète d'une session d'entraînement, filmée derrière le golfeur, caméra sur trépied.")
        sb.appendLine()
        sb.appendLine("Des mesures automatiques ont été faites sur chaque swing détecté. Ce sont des estimations qui peuvent contenir des erreurs :")
        rows.forEach { r ->
            sb.append("- Swing ${r.index} à ${mmss(r.startT)} : ")
            val res = r.result
            if (res == null || res.carryM <= 0) {
                sb.appendLine(if (r.found) "trajectoire non fiable" else "balle non détectée")
            } else {
                sb.appendLine(
                    "vitesse balle %.0f km/h, vitesse club estimée %.0f km/h, carry estimé %.0f m, forme %s, fiabilité %d %%"
                        .format(res.ballSpeed * 3.6, res.clubSpeed * 3.6, res.carryM, res.shape, res.confidence)
                )
            }
        }
        sb.appendLine()
        sb.appendLine("Produis un rapport d'entraînement en français :")
        sb.appendLine("1. Résumé de la session : nombre de swings, régularité, tendances.")
        sb.appendLine("2. Pour les swings les plus représentatifs : ce que la vidéo montre (prise, rotation, transfert du poids, trajectoire de la balle).")
        sb.appendLine("3. Lien entre la forme de trajectoire (push, pull, draw, fade, slice, hook) et les mouvements observés.")
        sb.appendLine("4. Points forts et deux axes de travail prioritaires.")
        sb.appendLine("5. Trois exercices concrets pour la prochaine séance.")
        sb.append("Vérifie les mesures avec la vidéo et signale les swings où la détection semble fausse (fiabilité faible ou balle non détectée).")
        return sb.toString()
    }

    private fun mmss(t: Double): String {
        val s = t.toInt()
        return String.format(Locale.FRANCE, "%d:%02d", s / 60, s % 60)
    }
}
