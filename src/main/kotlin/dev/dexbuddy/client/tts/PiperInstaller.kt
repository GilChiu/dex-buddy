package dev.dexbuddy.client.tts

import dev.dexbuddy.client.DexBuddyClient
import net.fabricmc.loader.api.FabricLoader
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Fetches Piper (MIT) and a voice from their official homes the first time they're needed, into
 * <game dir>/dexbuddy/. Nothing third-party is shipped inside the mod jar.
 */
object PiperInstaller {
    private const val RELEASE = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/"
    private const val VOICES = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"

    val root: Path = FabricLoader.getInstance().gameDir.resolve("dexbuddy")
    private val binDir: Path = root.resolve("piper")
    private val voiceDir: Path = root.resolve("voices")

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(15)).build()

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * SHA-256 of each Piper download (release 2023.11.14-2). The engine is a program that runs on your PC,
     * so a download that doesn't match exactly is thrown away instead of run.
     */
    private val SHA256 = mapOf(
        "piper_windows_amd64.zip" to "f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea",
        "piper_macos_aarch64.tar.gz" to "6b1eb03b3735946cb35216e063e7eebcc33a6bbf5dd96ec0217959bf1cdcb0cc",
        "piper_macos_x64.tar.gz" to "ced85c0a3df13945b1e623b878a48fdc2854d5c485b4b67f62857cf551deaf8b",
        "piper_linux_aarch64.tar.gz" to "fea0fd2d87c54dbc7078d0f878289f404bd4d6eea6e7444a77835d1537ab88eb",
        "piper_linux_x86_64.tar.gz" to "a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992",
    )

    private fun sha256(p: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { input -> val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The platform's Piper archive, or null when Piper has no build for it. */
    private fun asset(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
        return when {
            os.contains("win") -> "piper_windows_amd64.zip"
            os.contains("mac") -> if (arm) "piper_macos_aarch64.tar.gz" else "piper_macos_x64.tar.gz"
            os.contains("linux") -> if (arm) "piper_linux_aarch64.tar.gz" else "piper_linux_x86_64.tar.gz"
            else -> null
        }
    }

    fun executable(): Path = binDir.resolve("piper").resolve(if (windows) "piper.exe" else "piper")
    fun model(voice: String): Path = voiceDir.resolve("$voice.onnx")

    fun isInstalled(voice: String): Boolean =
        Files.isRegularFile(executable()) && Files.isRegularFile(model(voice)) && Files.isRegularFile(voiceDir.resolve("$voice.onnx.json"))

    /** Downloads whatever is missing. [progress] gets short status lines. Throws on failure. */
    fun install(voice: String, progress: (String) -> Unit) {
        Files.createDirectories(binDir)
        Files.createDirectories(voiceDir)
        if (!Files.isRegularFile(executable())) {
            val asset = asset() ?: error("Piper has no build for this computer (${System.getProperty("os.name")} ${System.getProperty("os.arch")})")
            progress("Downloading the voice engine (about 22 MB)...")
            val archive = Files.createTempFile(root, "piper", if (asset.endsWith(".zip")) ".zip" else ".tar.gz")
            try {
                download(RELEASE + asset, archive)
                if (sha256(archive) != SHA256[asset]) error("the voice engine download didn't match (damaged or changed), so it wasn't installed")
                if (asset.endsWith(".zip")) unzip(archive, binDir) else untarGz(archive, binDir)
            } finally {
                Files.deleteIfExists(archive)
            }
            if (!windows) executable().toFile().setExecutable(true)
        }
        if (!Files.isRegularFile(model(voice))) {
            val url = voiceUrl(voice) ?: error("\"$voice\" is not a Piper voice id (like en_US-ljspeech-medium)")
            progress("Downloading the voice \"$voice\" (about 60 MB)...")
            download("$url.onnx.json", voiceDir.resolve("$voice.onnx.json"))
            download("$url.onnx", model(voice))
        }
    }

    /** en_US-ljspeech-medium -> .../en/en_US/ljspeech/medium/en_US-ljspeech-medium */
    private fun voiceUrl(voice: String): String? {
        val parts = voice.split('-')
        if (parts.size < 3) return null
        val locale = parts[0]
        val quality = parts.last()
        val name = parts.subList(1, parts.size - 1).joinToString("-")
        val lang = locale.substringBefore('_')
        return "$VOICES$lang/$locale/$name/$quality/$voice"
    }

    private fun download(url: String, to: Path) {
        val tmp = to.resolveSibling(to.fileName.toString() + ".part")
        val res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp))
        if (res.statusCode() != 200) {
            Files.deleteIfExists(tmp)
            error("download failed (HTTP ${res.statusCode()}) for ${url.substringAfterLast('/')}")
        }
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING)
        DexBuddyClient.LOG.info("[dexbuddy] downloaded {} ({} KB)", to.fileName, Files.size(to) / 1024)
    }

    private fun safe(dest: Path, name: String): Path {
        val p = dest.resolve(name).normalize()
        require(p.startsWith(dest.normalize())) { "archive entry escapes the folder: $name" }
        return p
    }

    private fun unzip(archive: Path, dest: Path) {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = safe(dest, e.name)
                if (e.isDirectory) Files.createDirectories(out) else {
                    Files.createDirectories(out.parent)
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** Minimal tar reader (files, folders, symlinks): enough for Piper's Linux/macOS archives. */
    private fun untarGz(archive: Path, dest: Path) {
        GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it.toInt() == 0 }) break
                fun str(from: Int, len: Int) = String(header, from, len, Charsets.UTF_8).substringBefore('\u0000').trim()
                val name = (str(345, 155).let { if (it.isNotEmpty()) "$it/" else "" }) + str(0, 100)
                val size = str(124, 12).ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val out = safe(dest, name)
                when (type) {
                    '5' -> Files.createDirectories(out)
                    '2' -> {
                        Files.createDirectories(out.parent)
                        Files.deleteIfExists(out)
                        try { Files.createSymbolicLink(out, Path.of(str(157, 100))) } catch (_: Exception) {}
                    }
                    '0', '\u0000' -> {
                        Files.createDirectories(out.parent)
                        Files.newOutputStream(out).use { o -> copyN(input, o, size) }
                        if ((header[100 + 4].toInt().toChar()) in '1'..'7' || name.endsWith("/piper")) out.toFile().setExecutable(true)
                    }
                    else -> skip(input, size)
                }
                // Entry data is padded to a 512-byte boundary (folders and links have none).
                skip(input, (512 - (size % 512)) % 512)
            }
        }
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) return false; n += r }
        return true
    }

    private fun copyN(i: InputStream, o: java.io.OutputStream, size: Long) {
        val buf = ByteArray(65536)
        var left = size
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; o.write(buf, 0, r); left -= r }
    }

    private fun skip(i: InputStream, n: Long) {
        var left = n
        while (left > 0) { val s = i.skip(left); if (s <= 0) { if (i.read() < 0) break; left-- } else left -= s }
    }
}
