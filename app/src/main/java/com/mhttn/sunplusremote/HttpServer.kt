package com.mhttn.sunplusremote

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Same job as the original app's HttpService:
 *   POST /dvb/<id>        the box delivers the answer to one of our requests
 *   GET  /down/<key>      a stored text (used for very long URLs we cast)
 *   GET  /video|music|image/<mediaStoreId>.<ext>   local media for casting
 */
object CallbackServer {
    val pending = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()
    val downCache = ConcurrentHashMap<String, String>()

    @Volatile
    var port: Int = -1
        private set

    private var server: ServerSocket? = null

    @Synchronized
    fun ensureStarted(): Int {
        val cur = server
        if (cur != null && !cur.isClosed && port > 0) return port
        var p = 9901
        while (p < 9921) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(p))
                server = s
                port = p
                val t = Thread { acceptLoop(s) }
                t.isDaemon = true
                t.start()
                return p
            } catch (e: IOException) {
                p++
            }
        }
        throw IllegalStateException("Telefondaki HTTP sunucusu başlatılamadı")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val c = s.accept()
                App.pool.execute { handle(c) }
            } catch (e: IOException) {
                if (s.isClosed) return
            }
        }
    }

    // ------------------------------------------------------------------ http

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16384) throw IOException("line too long")
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(minOf(len, 1 shl 20))
        val buf = ByteArray(16384)
        var left = len
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun respond(out: OutputStream, code: Int, text: String, body: ByteArray, type: String) {
        val head = "HTTP/1.0 $code $text\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val input = BufferedInputStream(sock.getInputStream(), 16384)
            val out = sock.getOutputStream()
            val line = readLine(input) ?: return
            val parts = line.split(" ")
            if (parts.size < 2) {
                respond(out, 400, "Bad Request", ByteArray(0), "text/plain")
                return
            }
            val method = parts[0].uppercase()
            var path = parts[1]
            val q = path.indexOf('?')
            if (q >= 0) path = path.substring(0, q)
            path = Uri.decode(path)

            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }

            when {
                path.startsWith("/dvb/") -> {
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readBody(input, len)
                    val id = path.substring(path.lastIndexOf('/') + 1)
                    pending[id]?.offer(String(body, Charsets.UTF_8))
                    respond(out, 200, "OK", ByteArray(0), "text/plain")
                }
                path.startsWith("/down/") -> {
                    val key = path.substring(path.lastIndexOf('/') + 1)
                    val text = downCache[key]
                    if (text == null) {
                        respond(out, 404, "Not Found", "Error 404, down not found.".toByteArray(), "text/plain")
                    } else {
                        respond(out, 200, "OK", text.toByteArray(), "application/octet-stream")
                    }
                }
                path.startsWith("/video/") || path.startsWith("/music/") || path.startsWith("/image/") ->
                    serveMedia(method, path, headers, out)
                else -> respond(out, 404, "Not Found", "Error 404".toByteArray(), "text/plain")
            }
        } catch (e: Exception) {
            // client went away or sent garbage - nothing to do
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }

    // ----------------------------------------------------------------- media

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "flv" -> "video/x-flv"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    private fun serveMedia(method: String, path: String, headers: Map<String, String>, out: OutputStream) {
        val kind = path.substring(1, path.indexOf('/', 1))
        val file = path.substring(path.lastIndexOf('/') + 1)
        val dot = file.lastIndexOf('.')
        val id = (if (dot >= 0) file.substring(0, dot) else file).toLongOrNull()
        val ext = if (dot >= 0) file.substring(dot + 1) else ""
        if (id == null) {
            respond(out, 404, "Not Found", "Error 404, file not found.".toByteArray(), "text/plain")
            return
        }
        val base: Uri = when (kind) {
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> {
                respond(out, 404, "Not Found", "Error 404".toByteArray(), "text/plain")
                return
            }
        }
        val uri = ContentUris.withAppendedId(base, id)
        val cr = App.ctx.contentResolver

        if (kind == "image") {
            val jpeg = scaledJpeg(uri)
            if (jpeg == null) {
                respond(out, 404, "Not Found", "Error 404, file not found.".toByteArray(), "text/plain")
            } else {
                val head = "HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray())
                if (method != "HEAD") out.write(jpeg)
                out.flush()
            }
            return
        }

        val pfd = try {
            cr.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            respond(out, 404, "Not Found", "Error 404, file not found.".toByteArray(), "text/plain")
            return
        }
        try {
            val total = pfd.statSize
            var start = 0L
            var end = total - 1
            var partial = false
            val range = headers["range"]
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.substring(6).split(",")[0].trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val a = spec.substring(0, dash).trim()
                    val b = spec.substring(dash + 1).trim()
                    if (a.isEmpty()) {
                        val n = b.toLongOrNull() ?: 0L
                        start = maxOf(0L, total - n)
                    } else {
                        start = a.toLongOrNull() ?: 0L
                        if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end)
                    }
                    partial = true
                }
                if (start >= total) {
                    val head = "HTTP/1.0 416 Requested Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray())
                    out.flush()
                    return
                }
            }
            val len = end - start + 1
            val sb = StringBuilder()
            if (partial) {
                sb.append("HTTP/1.0 206 Partial Content\r\n")
                sb.append("Content-Range: bytes $start-$end/$total\r\n")
            } else {
                sb.append("HTTP/1.0 200 OK\r\n")
            }
            sb.append("Content-Type: ${mimeFor(ext)}\r\n")
            sb.append("Content-Length: $len\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            if (method == "HEAD") {
                out.flush()
                return
            }
            val fis = FileInputStream(pfd.fileDescriptor)
            fis.channel.position(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            out.flush()
        } finally {
            try {
                pfd.close()
            } catch (e: Exception) {
            }
        }
    }

    /** Photos are sent as JPEG, at most about 2400x1800 and ~1 MB, like the original. */
    private fun scaledJpeg(uri: Uri): ByteArray? {
        val cr = App.ctx.contentResolver
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 1800) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            var q = 90
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            while (bos.size() > 1024 * 1024 && q > 20) {
                q -= 10
                bos.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            }
            bmp.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
