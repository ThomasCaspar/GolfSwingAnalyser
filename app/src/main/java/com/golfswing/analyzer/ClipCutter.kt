package com.golfswing.analyzer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Découpe d'un swing dans une longue vidéo, SANS réencodage : les images compressées sont recopiées
 * telles quelles dans un nouveau MP4. C'est rapide, sans perte de qualité, et seule la fenêtre utile
 * est ensuite chargée par la revue (lecture, analyse, export).
 */
object ClipCutter {

    /**
     * @param startSec début souhaité (s, dans la source)
     * @param endSec fin souhaitée (s, dans la source)
     * @return décalage (s) entre la source et le clip : le clip commence à l'image clé qui précède startSec.
     *         Les temps du clip = temps source - décalage. Null en cas d'échec.
     */
    fun cut(src: String, dst: String, startSec: Double, endSec: Double, rotation: Int): Double? {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(src)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)

            // Aller directement à l'image clé qui précède le début : on ne lit pas la session depuis le début
            ex.seekTo((max(0.0, startSec) * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val offsetUs = ex.sampleTime
            if (offsetUs < 0) return null

            val m = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(rotation)
            val outTrack = m.addTrack(fmt)
            m.start()

            val declared = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocateDirect(max(4 shl 20, declared))
            val info = MediaCodec.BufferInfo()
            var lastPts = -1L
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val pts = ex.sampleTime
                if (pts / 1e6 > endSec) break
                // Temps relatifs au clip, strictement croissants
                val rel = max(pts - offsetUs, lastPts + 1)
                lastPts = rel
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, rel, flags)
                m.writeSampleData(outTrack, buf, info)
                ex.advance()
            }
            m.stop()
            return offsetUs / 1e6
        } catch (_: Exception) {
            return null
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
