package com.golfswing.analyzer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Export vidéo avec tracé incrusté : décode chaque image (YUV), dessine le tracé
 * sur le plan de luminance et de chrominance, puis réencode en H.264.
 * Les images gardent leur horodatage d'origine : le ralenti est conservé.
 * La vidéo garde son orientation d'origine.
 */
object VideoExporter {

    /**
     * @param src vidéo source
     * @param dst fichier MP4 de sortie
     * @param rotation orientation à appliquer à la lecture (degrés)
     * @param fps cadence de la vidéo source
     * @param overlayFrom à partir de cet instant (s), le dessin est appelé ; avant, l'image est copiée telle quelle
     * @param drawAt dessine le tracé à l'instant t (s) sur un canevas aux dimensions brutes
     * @return true si l'export a abouti
     */
    fun export(
        src: String,
        dst: String,
        rotation: Int,
        fps: Double,
        overlayFrom: Double,
        drawAt: (Canvas, Double) -> Unit,
        onProgress: (Float) -> Unit,
        decimate: Int = 1,              // 2 = une image sur deux (copie plus légère)
        bitrate: Int = 20_000_000,
        startSec: Double = 0.0,         // découpe : seule la plage [startSec, endSec] est exportée
        endSec: Double = Double.MAX_VALUE
    ): Boolean {
        val ex = MediaExtractor()
        var thread: HandlerThread? = null
        var reader: ImageReader? = null
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var bitmap: Bitmap? = null
        try {
            ex.setDataSource(src)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) return false
            ex.selectTrack(track)
            // Aller directement à l'image clé qui précède la découpe : pas de décodage inutile au début
            if (startSec > 0.0) ex.seekTo((startSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 1L

            val ht = HandlerThread("VideoExport").also { it.start() }
            thread = ht
            val images = LinkedBlockingQueue<Image>()
            val rd = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 6)
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.let { images.add(it) }
            }, Handler(ht.looper))

            val encoder = MediaCodec.createEncoderByType("video/avc")
            enc = encoder
            val ef = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceIn(15, 240))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = muxer
            muxer.setOrientationHint(rotation)

            val decoder = MediaCodec.createDecoderByType(mime)
            dec = decoder
            decoder.configure(fmt, rd.surface, null, 0)
            decoder.start()

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            val canvas = Canvas(bmp)
            val argb = IntArray(w * h)

            var muxStarted = false
            var encTrack = -1

            // Vide la sortie de l'encodeur et l'écrit dans le fichier. Retourne true à la fin du flux.
            fun drainEncoder(timeoutUs: Long): Boolean {
                val encInfo = MediaCodec.BufferInfo()
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, timeoutUs)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            encTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                        idx >= 0 -> {
                            val buf = encoder.getOutputBuffer(idx)
                            val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buf != null && encInfo.size > 0 && muxStarted && !isConfig) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(encTrack, buf, encInfo)
                            }
                            val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(idx, false)
                            if (eos) return true
                        }
                    }
                }
            }

            // Encode une image : copie YUV, puis tracé par-dessus.
            fun encodeFrame(img: Image, ptsUs: Long, t: Double) {
                var idx = -1
                while (true) {
                    idx = encoder.dequeueInputBuffer(10_000)
                    if (idx >= 0) break
                    drainEncoder(0)
                }
                val dstImg = encoder.getInputImage(idx)
                    ?: throw IllegalStateException("Entrée de l'encodeur indisponible")
                copyImage(img, dstImg)
                if (t >= overlayFrom) {
                    bmp.eraseColor(0)
                    drawAt(canvas, t)
                    bmp.getPixels(argb, 0, w, 0, 0, w, h)
                    blendOverlay(argb, w, h, dstImg)
                }
                dstImg.close()
                encoder.queueInputBuffer(idx, 0, w * h * 3 / 2, ptsUs, 0)
            }

            val info = MediaCodec.BufferInfo()
            var inEos = false
            var decEos = false
            var decoded = 0
            var processed = 0
            var firstTs = -1L
            var idle = 0
            var frameIdx = 0
            var encoded = 0
            var stop = false
            val span = Math.max(0.001, Math.min(endSec, durUs / 1e6) - startSec)

            while (true) {
                if (!inEos) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decEos) {
                    val oi = decoder.dequeueOutputBuffer(info, 5_000)
                    if (oi >= 0) {
                        val render = info.size > 0
                        decoder.releaseOutputBuffer(oi, render)
                        if (render) decoded++
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decEos = true
                    }
                }

                var img = images.poll(2, TimeUnit.MILLISECONDS)
                if (img == null && decEos && processed < decoded) {
                    if (++idle > 1000) break     // image jamais reçue : on arrête proprement
                }
                while (img != null && !stop) {
                    idle = 0
                    val t = img.timestamp / 1e9
                    if (t > endSec) {
                        stop = true                       // fin de la découpe
                    } else if (t >= startSec - 1e-4) {
                        if (firstTs < 0) firstTs = img.timestamp
                        if (frameIdx++ % decimate == 0) {
                            encodeFrame(img, (img.timestamp - firstTs) / 1000L, t)
                            encoded++
                        }
                        onProgress(min1(((t - startSec) / span).toFloat()))
                    }
                    img.close()
                    processed++
                    img = images.poll()
                }
                drainEncoder(0)
                if (stop || (decEos && processed >= decoded)) break
            }

            // Fin du flux pour l'encodeur
            var eosIdx = -1
            while (true) {
                eosIdx = encoder.dequeueInputBuffer(10_000)
                if (eosIdx >= 0) break
                drainEncoder(0)
            }
            encoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!drainEncoder(10_000)) { /* attente de la fin */ }

            return encoded > 0
        } catch (_: Exception) {
            return false
        } finally {
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            try { mux?.stop() } catch (_: Exception) {}
            try { mux?.release() } catch (_: Exception) {}
            reader?.close()
            thread?.quitSafely()
            bitmap?.recycle()
            ex.release()
        }
    }

    /** Cadence réelle d'une vidéo : nombre d'images divisé par la durée (sans décodage). */
    fun frameRate(path: String): Double {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return 0.0
            ex.selectTrack(track)
            var n = 0
            var first = -1L
            var last = 0L
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (first < 0) first = t
                last = t
                n++
                ex.advance()
            }
            if (n < 2 || last <= first) 0.0 else (n - 1) * 1_000_000.0 / (last - first)
        } catch (_: Exception) {
            0.0
        } finally {
            ex.release()
        }
    }

    private fun min1(v: Float): Float = if (v > 1f) 1f else if (v < 0f) 0f else v

    /** Copie les trois plans YUV de l'image source vers l'image de l'encodeur. */
    private fun copyImage(src: Image, dst: Image) {
        for (p in 0..2) {
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val pw = if (p == 0) src.width else src.width / 2
            val ph = if (p == 0) src.height else src.height / 2
            copyPlane(sp.buffer, sp.rowStride, sp.pixelStride, dp.buffer, dp.rowStride, dp.pixelStride, pw, ph)
        }
    }

    private fun copyPlane(
        sb: ByteBuffer, srs: Int, sps: Int,
        db: ByteBuffer, drs: Int, dps: Int,
        w: Int, h: Int
    ) {
        for (y in 0 until h) {
            val so = y * srs
            val dO = y * drs
            for (x in 0 until w) {
                db.put(dO + x * dps, sb.get(so + x * sps))
            }
        }
    }

    /** Mélange le tracé (ARGB, non prémultiplié) sur la luminance et la chrominance. */
    private fun blendOverlay(argb: IntArray, w: Int, h: Int, dst: Image) {
        val yp = dst.planes[0]
        val up = dst.planes[1]
        val vp = dst.planes[2]
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = argb[row + x]
                val a = c ushr 24
                if (a == 0) continue
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val af = a / 255f
                val yo = 0.299f * r + 0.587f * g + 0.114f * b
                val yi = y * yp.rowStride + x * yp.pixelStride
                val yv = yp.buffer.get(yi).toInt() and 0xFF
                yp.buffer.put(yi, (yv + (yo - yv) * af).roundToInt().coerceIn(0, 255).toByte())
                if (x % 2 == 0 && y % 2 == 0) {
                    val uo = -0.169f * r - 0.331f * g + 0.5f * b + 128f
                    val vo = 0.5f * r - 0.419f * g - 0.081f * b + 128f
                    val ui = (y / 2) * up.rowStride + (x / 2) * up.pixelStride
                    val vi = (y / 2) * vp.rowStride + (x / 2) * vp.pixelStride
                    val uv = up.buffer.get(ui).toInt() and 0xFF
                    val vv = vp.buffer.get(vi).toInt() and 0xFF
                    up.buffer.put(ui, (uv + (uo - uv) * af).roundToInt().coerceIn(0, 255).toByte())
                    vp.buffer.put(vi, (vv + (vo - vv) * af).roundToInt().coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
