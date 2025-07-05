package kindai.example

import java.awt.BorderLayout
import java.awt.Dimension
import javax.sound.sampled.*
import javax.swing.*
import kotlin.concurrent.thread
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/* ─────────────────────── 録音ユーティリティ ─────────────────────── */
object AudioUtil {
    private val fmt = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var th: Thread? = null

    fun start(out: java.io.File, onErr: (String) -> Unit) {
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) { onErr("マイクが16 kHzに非対応"); return }
        line = AudioSystem.getLine(info) as TargetDataLine
        line!!.open(fmt); line!!.start()
        th = thread { AudioSystem.write(AudioInputStream(line), AudioFileFormat.Type.WAVE, out) }
        return Unit
    }
    fun stop() { line?.apply { stop(); close() }; th?.join() }
}

/* ─────────────────────── 共通 HTTP ─────────────────────── */
object Http {
    val cli = okhttp3.OkHttpClient.Builder()
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val map = jacksonObjectMapper()
}

/* ─────────────────────── Swing GUI ─────────────────────── */
fun main() = SwingUtilities.invokeLater { AppFrame().isVisible = true }

class AppFrame : JFrame("音声 Chat システム") {

    private val startBtn = JButton("録音開始")
    private val stopBtn  = JButton("録音停止").apply { isEnabled = false }
    private val demoChk  = JCheckBox("デモモード").apply { toolTipText = "ON なら録音しても固定文章でテスト" }
    private val area     = JTextArea().apply { lineWrap = true }
    private val status   = JLabel("準備完了")
    private val wavFile  = java.io.File("app/recorded_audio.wav")

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(640, 400)

        val top = JPanel().apply { add(startBtn); add(stopBtn); add(demoChk) }
        add(top, BorderLayout.NORTH)
        add(JScrollPane(area), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        pack(); setLocationRelativeTo(null)

        startBtn.addActionListener { startRec() }
        stopBtn .addActionListener { stopRec() }
    }

    private fun startRec() {
        area.append("録音開始...\n")
        status.text = "録音中..."
        startBtn.isEnabled = false; stopBtn.isEnabled = true
        wavFile.parentFile.mkdirs()
        if (!demoChk.isSelected) {
        AudioUtil.start(wavFile) { showErr(it); reset() }
        } else {
            area.append("デモモード: 録音処理スキップ\n")
        }
    }

    private fun stopRec() {
        if (!demoChk.isSelected) {
        AudioUtil.stop()
        area.append("録音停止 → 保存: ${wavFile.path}\n")
        } else {
            area.append("デモモード: 録音処理スキップ\n")
        }
        status.text = "DjangoへPOST中..."
        stopBtn.isEnabled = false

        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                // ── 1. transcription を取得 ──
                val trans = if (demoChk.isSelected) {
                    "こんにちは！"                       // デモ固定文
                } else {
                    postToDjango()
                }

                // ── 2. AI へ問い合わせ（空ならスキップ） ──
                val ai = if (trans.isNotBlank()) askOllama(trans) else "(AI応答なし)"

                // ── 3. 画面更新 ──
                SwingUtilities.invokeLater {
                    area.append("認識結果: $trans\nAI応答:   $ai\n\n")
                    status.text = "完了"; reset()
                    Runtime.getRuntime().exec(arrayOf("say", ai))
                }
            }
        }.execute()
    }

    private fun reset() { startBtn.isEnabled = true; stopBtn.isEnabled = false }

    /* -------- Djangoへ音声POST -------- */
    private fun postToDjango(): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaTypeOrNull()))
            .build()
        val req = Request.Builder().url("http://127.0.0.1:8000/api/upload/").post(body).build()
        Http.cli.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("音声認識エラー: HTTP ${res.code}")
            return@use Http.map.readTree(res.body!!.string())["transcription"].asText()
        }
        return ""
    }

    /* -------- Ollama へ AI質問 -------- */
    private fun askOllama(prompt: String): String {
        return try {
            val promptForOllama = "${prompt}。日本語で、余計な説明や英語を含めず、簡潔に答えてください。"
        val json = """
                {"model":"deepseek-r1:7b","prompt":"${promptForOllama.replace("\"","\\\"")}",
             "stream":false}
        """.trimIndent()
        val req  = Request.Builder().url("http://localhost:11434/api/generate")
            .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        Http.cli.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return "Ollama APIエラー (ステータス: ${res.code})"
                val raw = Http.map.readTree(res.body!!.string())["response"].asText()
                return cleanOllamaResponse(raw)
            }
            return ""
        } catch (e: Exception) {
            return "Ollama API例外: ${e.message}"
        }
    }

    // Ollama応答から<think>タグや英語を除去し、日本語だけを返す
    private fun cleanOllamaResponse(response: String): String {
        // <think>...</think> を除去
        val withoutThink = response.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        // 日本語の文だけを抽出（最初の日本語文）
        val japanese = Regex("[\u3040-\u30FF\u4E00-\u9FFF].*").find(withoutThink)?.value ?: withoutThink
        return japanese.trim()
    }

    private fun showErr(msg: String) {
        JOptionPane.showMessageDialog(this, msg, "エラー", JOptionPane.ERROR_MESSAGE)
        area.append("!! $msg\n"); status.text = "エラー"
    }
}
