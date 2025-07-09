package kindai.example

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.sound.sampled.*
import javax.swing.*
import kotlin.concurrent.thread
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date

/* ─────────────────────── 音声検出・録音ユーティリティ ─────────────────────── */
object VoiceDetector {
    private val fmt = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private val isListening = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    
    // 音声検出の閾値設定
    private val SILENCE_THRESHOLD = 1000.0  // 音声検出の閾値（適度な感度に調整）
    private val SILENCE_DURATION = 1500    // 無音が続く時間(ms)（少し短めに調整）
    private val MIN_RECORDING_DURATION = 1500 // 最小録音時間(ms)（1.5秒に延長）
    
    fun startListening(
        onVoiceDetected: () -> Unit,
        onVoiceEnded: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isListening.get()) return
        
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            onError("マイクが16 kHzに非対応")
            return
        }
        
        try {
            line = AudioSystem.getLine(info) as TargetDataLine
            line!!.open(fmt)
            line!!.start()
            isListening.set(true)
            
            listenerThread = thread {
                listenForVoice(onVoiceDetected, onVoiceEnded, onError)
            }
        } catch (e: Exception) {
            onError("マイク初期化エラー: ${e.message}")
        }
    }
    
    fun stopListening() {
        isListening.set(false)
        isRecording.set(false)
        line?.apply { stop(); close() }
        listenerThread?.interrupt()
    }
    
    private fun listenForVoice(
        onVoiceDetected: () -> Unit,
        onVoiceEnded: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        val buffer = ByteArray(1024)
        val audioBuffer = ByteArrayOutputStream()
        var silenceStart = 0L
        var recordingStart = 0L
        
        try {
            while (isListening.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = line!!.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val volume = calculateVolume(buffer, bytesRead)
                        val currentTime = System.currentTimeMillis()
                        
                        if (volume > SILENCE_THRESHOLD) {
                            // 音声検出
                            if (!isRecording.get()) {
                                isRecording.set(true)
                                recordingStart = currentTime
                                audioBuffer.reset()
                                SwingUtilities.invokeLater { onVoiceDetected() }
                            }
                            silenceStart = currentTime
                            audioBuffer.write(buffer, 0, bytesRead)
                        } else {
                            // 無音
                            if (isRecording.get()) {
                                audioBuffer.write(buffer, 0, bytesRead)
                                
                                if (currentTime - silenceStart > SILENCE_DURATION &&
                                    currentTime - recordingStart > MIN_RECORDING_DURATION) {
                                    // 録音終了
                                    isRecording.set(false)
                                    val audioData = audioBuffer.toByteArray()
                                    SwingUtilities.invokeLater { onVoiceEnded(audioData) }
                                }
                            }
                        }
                    }
                    Thread.sleep(10) // CPU負荷軽減
                } catch (e: InterruptedException) {
                    // スレッドが割り込まれた場合は正常終了
                    break
                }
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) {
                SwingUtilities.invokeLater { onError("音声検出エラー: ${e.message}") }
            }
        }
    }
    
    private fun calculateVolume(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                sum += sample * sample
            }
        }
        return Math.sqrt(sum / (length / 2))
    }
}

/* ─────────────────────── 共通 HTTP ─────────────────────── */
object Http {
    val cli = okhttp3.OkHttpClient.Builder()
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val map = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
}

/* ─────────────────────── Swing GUI ─────────────────────── */
fun main() = SwingUtilities.invokeLater { AppFrame().isVisible = true }

class AppFrame : JFrame("音声認識&AI応答アプリ") {

    private val resultArea = JTextArea().apply { 
        lineWrap = true
        isEditable = false
        font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
    }
    private val statusLabel = JLabel("マイク準備完了．音声待機中.....").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        horizontalAlignment = SwingConstants.CENTER
    }
    
    private val isProcessing = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmssSSS")

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(800, 600)

        // GUI レイアウト
        add(JScrollPane(resultArea), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        
        pack()
        setLocationRelativeTo(null)
        
        // アプリ起動時に音声検出開始
        startVoiceDetection()
    }

    private fun startVoiceDetection() {
        VoiceDetector.startListening(
            onVoiceDetected = {
                statusLabel.text = "録音中"
            },
            onVoiceEnded = { audioData ->
                if (!isProcessing.get()) {
                    processAudioData(audioData)
                }
            },
            onError = { error ->
                showError(error)
                statusLabel.text = "エラー: $error"
            }
        )
    }

    private fun processAudioData(audioData: ByteArray) {
        isProcessing.set(true)
        VoiceDetector.stopListening() // 処理中はマイクオフ
        
        val timestamp = dateFormat.format(Date())
        val wavFile = java.io.File("app/recorded_audio_$timestamp.wav")
        
        statusLabel.text = "音声セグメントを${wavFile.name}に保存しました．DjangoにPOST中..."
        
        // デバッグ用：録音ファイルの場所をコンソールのみに表示
        println("録音ファイル保存場所: ${wavFile.absolutePath}")
        
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                try {
                    // WAVファイルに保存
                    saveAsWav(audioData, wavFile)
                    
                    // Django APIに送信
                    val transcription = postToDjango(wavFile)
                    
                    // Ollama APIに送信
                    val aiResponse = askOllama(transcription)
                    
                    // GUI更新
                    SwingUtilities.invokeLater {
                        resultArea.append("--- 新しい音声セグメント ---\n")
                        resultArea.append("認識結果: $transcription\n")
                        resultArea.append("Ollama応答: $aiResponse\n\n")
                        resultArea.caretPosition = resultArea.document.length
                        
                        // 読み上げ中はマイクオフ
                        statusLabel.text = "読み上げ中..."
                        
                        // 読み上げを別スレッドで実行
                        thread {
                            try {
                                val process = Runtime.getRuntime().exec(arrayOf("say", aiResponse))
                                process.waitFor() // 読み上げ完了まで待機
                            } catch (e: Exception) {
                                // 読み上げエラーは無視
                            }
                            
                            // 読み上げ完了後にマイク再開
                            SwingUtilities.invokeLater {
                                statusLabel.text = "マイク準備完了．音声待機中....."
                                isProcessing.set(false)
                                startVoiceDetection() // 処理完了後にマイクオン
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        showError("処理エラー: ${e.message}")
                        statusLabel.text = "マイク準備完了．音声待機中....."
                        isProcessing.set(false)
                        startVoiceDetection() // エラー時もマイクオン
                    }
                }
            }
        }.execute()
    }

    private fun saveAsWav(audioData: ByteArray, file: java.io.File) {
        file.parentFile.mkdirs()
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val audioInputStream = AudioInputStream(
            java.io.ByteArrayInputStream(audioData),
            format,
            audioData.size / format.frameSize.toLong()
        )
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, file)
    }

    private fun postToDjango(wavFile: java.io.File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaTypeOrNull()))
            .build()
        val req = Request.Builder().url("http://127.0.0.1:8000/api/upload/").post(body).build()
        
        return Http.cli.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val errorBody = res.body?.string() ?: "不明なエラー"
                throw Exception("音声認識エラー: HTTP ${res.code} - $errorBody")
            }
            val responseBody = res.body!!.string()
            println("Django API応答: $responseBody") // デバッグ用
            Http.map.readTree(responseBody)["transcription"].asText()
        }
    }

    private fun askOllama(prompt: String): String {
        return try {
            val promptForOllama = "${prompt}。日本語で、簡潔に答えてください。"
            val json = """
                {"model":"gemma2","prompt":"${promptForOllama.replace("\"","\\\"")}",
                 "stream":false}
            """.trimIndent()
            val req = Request.Builder().url("http://localhost:11434/api/generate")
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            Http.cli.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return "Ollama APIエラー (ステータス: ${res.code})"
                val raw = Http.map.readTree(res.body!!.string())["response"].asText()
                return cleanOllamaResponse(raw)
            }
        } catch (e: Exception) {
            "Ollama API例外: ${e.message}"
        }
    }

    private fun cleanOllamaResponse(response: String): String {
        val withoutThink = response.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        val japanese = Regex("[\u3040-\u30FF\u4E00-\u9FFF].*").find(withoutThink)?.value ?: withoutThink
        return japanese.trim()
    }

    private fun showError(msg: String) {
        JOptionPane.showMessageDialog(this, msg, "エラー", JOptionPane.ERROR_MESSAGE)
    }
    
    override fun dispose() {
        VoiceDetector.stopListening()
        super.dispose()
    }
}
