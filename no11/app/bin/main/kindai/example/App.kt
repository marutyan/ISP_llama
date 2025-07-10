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
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
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
    
    // モデル選択ラジオボタン
    private val gemma2Radio = JRadioButton("Gemma2 (9B - 高性能)").apply {
        toolTipText = "高品質テキスト生成 - テキストのみ対応"
    }
    private val gemma3Radio = JRadioButton("Gemma3 (4B - マルチモーダル)").apply {
        toolTipText = "画像＋テキスト処理 - マルチモーダル対応"
    }
    private val gemma3LightRadio = JRadioButton("Gemma3:1B (軽量版)", true).apply { // デフォルト選択
        toolTipText = "高速処理 - テキストのみ対応（画像処理不可）"
    }
    private val modelGroup = ButtonGroup().apply {
        add(gemma2Radio)
        add(gemma3Radio)
        add(gemma3LightRadio)
    }
    
    // カスタムプロンプト入力
    private val promptField = JTextField("日本語で答えてください。").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }
    
    // 画像選択ボタン（Gemma3用）
    private val imageButton = JButton("画像を選択").apply {
        isEnabled = false // 軽量版がデフォルトなので無効
        addActionListener { selectImage() }
    }
    private val imageClearButton = JButton("クリア").apply {
        isEnabled = false
        addActionListener { clearImage() }
    }
    private var selectedImageFile: java.io.File? = null
    private val imagePreviewLabel = JLabel("画像なし").apply {
        preferredSize = Dimension(100, 100)
        border = BorderFactory.createLoweredBevelBorder()
        horizontalAlignment = SwingConstants.CENTER
    }
    
    // プロンプトプリセット（軽量版向けに最適化）
    private val promptPresets = arrayOf(
        "日本語で答えてください。",
        "簡潔に答えてください。日本語で。", // 軽量版向け
        "短く説明してください。日本語で。", // 軽量版向け
        "詳しく説明してください。日本語で。",
        "専門的な観点から分析してください。日本語で。"
    )
    private val promptComboBox = JComboBox(promptPresets).apply {
        isEditable = true
        selectedIndex = 0
    }
    
    // モデル状態チェック
    private val modelStatusLabel = JLabel("モデル状態確認中...").apply {
        font = Font(Font.SANS_SERIF, Font.ITALIC, 10)
    }
    
    private val isProcessing = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmssSSS")

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(900, 700)

        // モデル選択パネル
        val modelPanel = JPanel().apply {
            border = BorderFactory.createTitledBorder("AIモデル選択")
            add(gemma2Radio)
            add(gemma3Radio)
            add(gemma3LightRadio)
        }
        
        // プロンプト入力パネル
        val promptPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("カスタムプロンプト")
            add(JLabel("プリセット/カスタム: "), BorderLayout.WEST)
            add(promptComboBox, BorderLayout.CENTER)
        }
        
        // 画像選択パネル（Gemma3用）
        val imagePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("画像入力 (Gemma3のみ)")
            val buttonPanel = JPanel().apply {
                add(imageButton)
                add(imageClearButton)
            }
            add(buttonPanel, BorderLayout.NORTH)
            add(imagePreviewLabel, BorderLayout.CENTER)
        }
        
        // モデル状態パネル
        val statusPanel = JPanel().apply {
            add(modelStatusLabel)
        }
        
        // 設定パネル（上部）
        val settingsPanel = JPanel(BorderLayout()).apply {
            add(modelPanel, BorderLayout.NORTH)
            add(promptPanel, BorderLayout.CENTER)
            val bottomPanel = JPanel(BorderLayout()).apply {
                add(imagePanel, BorderLayout.CENTER)
                add(statusPanel, BorderLayout.SOUTH)
            }
            add(bottomPanel, BorderLayout.SOUTH)
        }
        
        // モデル選択時の処理
        gemma3Radio.addActionListener {
            val isGemma3 = gemma3Radio.isSelected
            imageButton.isEnabled = isGemma3
            imageClearButton.isEnabled = isGemma3 && selectedImageFile != null
            if (!isGemma3) {
                clearImage()
            }
        }
        gemma2Radio.addActionListener {
            val isGemma3 = gemma3Radio.isSelected
            imageButton.isEnabled = isGemma3
            imageClearButton.isEnabled = isGemma3 && selectedImageFile != null
            if (!isGemma3) {
                clearImage()
            }
        }
        gemma3LightRadio.addActionListener {
            val isGemma3Light = gemma3LightRadio.isSelected
            // 軽量版はマルチモーダル非対応
            imageButton.isEnabled = false
            imageClearButton.isEnabled = false
            if (isGemma3Light) {
                clearImage()
            }
        }

        // GUI レイアウト
        add(settingsPanel, BorderLayout.NORTH)
        add(JScrollPane(resultArea), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        
        pack()
        setLocationRelativeTo(null)
        
        // モデル状態チェック
        checkModelAvailability()
        
        // アプリ起動時に音声検出開始
        startVoiceDetection()
    }
    
    private fun checkModelAvailability() {
        thread {
            try {
                // Gemma2の確認
                val gemma2Available = checkModelStatus("gemma2")
                // Gemma3の確認
                val gemma3Available = checkModelStatus("gemma3")
                // Gemma3:1B (軽量版)の確認
                val gemma3LightAvailable = checkModelStatus("gemma3_light")
                
                SwingUtilities.invokeLater {
                    val status = mutableListOf<String>()
                    if (gemma2Available) status.add("Gemma2: ✓")
                    else status.add("Gemma2: ✗")
                    
                    if (gemma3Available) status.add("Gemma3: ✓")
                    else status.add("Gemma3: ✗")

                    if (gemma3LightAvailable) status.add("Gemma3:1B (軽量版): ✓")
                    else status.add("Gemma3:1B (軽量版): ✗")
                    
                    modelStatusLabel.text = status.joinToString(" | ")
                    
                    // 利用できないモデルは無効化
                    if (!gemma2Available) gemma2Radio.isEnabled = false
                    if (!gemma3Available) gemma3Radio.isEnabled = false
                    if (!gemma3LightAvailable) gemma3LightRadio.isEnabled = false
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    modelStatusLabel.text = "モデル状態確認エラー: ${e.message}"
                }
            }
        }
    }
    
    private fun checkModelStatus(modelName: String): Boolean {
        return try {
            // モデル名を正しく変換
            val actualModelName = when (modelName) {
                "gemma3_light" -> "gemma3:1b"
                else -> modelName
            }
            
            val json = """{"model":"$actualModelName","prompt":"test","stream":false}"""
            val req = Request.Builder().url("http://localhost:11434/api/generate")
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            Http.cli.newCall(req).execute().use { res ->
                res.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun selectImage() {
        val fileChooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "画像ファイル", "jpg", "jpeg", "png", "gif", "bmp"
            )
        }
        
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedImageFile = fileChooser.selectedFile
            imageButton.text = "選択済み: ${selectedImageFile!!.name}"
            imageClearButton.isEnabled = true
            
            // 画像が選択されたらプレビューを更新
            if (selectedImageFile!!.exists()) {
                try {
                    val originalIcon = ImageIcon(selectedImageFile!!.toURI().toURL())
                    val originalImage = originalIcon.image
                    
                    // プレビューサイズに合わせてリサイズ
                    val previewWidth = 100
                    val previewHeight = 100
                    val scaledImage = originalImage.getScaledInstance(
                        previewWidth, previewHeight, java.awt.Image.SCALE_SMOOTH
                    )
                    
                    imagePreviewLabel.icon = ImageIcon(scaledImage)
                    imagePreviewLabel.text = "" // テキストをクリア
                } catch (e: Exception) {
                    imagePreviewLabel.icon = null
                    imagePreviewLabel.text = "プレビューエラー"
                }
            } else {
                imagePreviewLabel.icon = null
                imagePreviewLabel.text = "ファイルが見つかりません"
            }
        }
    }

    private fun clearImage() {
        selectedImageFile = null
        imageButton.text = "画像を選択"
        imageClearButton.isEnabled = false
        imagePreviewLabel.icon = null
        imagePreviewLabel.text = "画像なし"
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
        
        // 処理中のステータス表示を改善
        SwingUtilities.invokeLater {
            val selectedModel = if (gemma2Radio.isSelected) "gemma2" else if (gemma3Radio.isSelected) "gemma3" else "gemma3_light"
            val modelName = when (selectedModel) {
                "gemma2" -> "Gemma2 (9B)"
                "gemma3" -> "Gemma3 (4B)"
                "gemma3_light" -> "Gemma3:1B (軽量版・高速)"
                else -> selectedModel
            }
            statusLabel.text = "音声セグメントをrecorded_audio_${System.currentTimeMillis()}.wavに保存しました．${modelName}でAI処理中..."
        }
        
        // デバッグ用：録音ファイルの場所をコンソールのみに表示
        println("録音ファイル保存場所: ${wavFile.absolutePath}")
        
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                try {
                    // WAVファイルに保存
                    saveAsWav(audioData, wavFile)
                    
                    // 音声認識実行
                    val transcription = postToDjango(wavFile)
                    
                    // 選択されたモデルとカスタムプロンプトでAI応答取得
                    val selectedModel = if (gemma2Radio.isSelected) "gemma2" else if (gemma3Radio.isSelected) "gemma3" else "gemma3_light"
                    val customPrompt = promptComboBox.selectedItem as String
                    val aiResponse = askOllama(transcription, selectedModel, customPrompt, selectedImageFile)
                    
                    // GUI更新
                    SwingUtilities.invokeLater {
                        resultArea.append("---新しい音声セグメント---\n")
                        resultArea.append("認識結果: $transcription\n")
                        
                        // モデル情報を詳細表示
                        val modelInfo = when (selectedModel) {
                            "gemma2" -> "Gemma2 (9.2B parameters - 高品質テキスト生成)"
                            "gemma3" -> "Gemma3 (4.3B parameters - マルチモーダル対応)"
                            "gemma3_light" -> "⚡ Gemma3:1B (815MB - 軽量・高速)"
                            else -> selectedModel
                        }
                        resultArea.append("使用モデル: $modelInfo\n")
                        
                        if (selectedImageFile != null) {
                            resultArea.append("画像: ${selectedImageFile!!.name}\n")
                        }
                        if (customPrompt.isNotEmpty() && customPrompt != "日本語で答えてください。") {
                            resultArea.append("カスタムプロンプト: $customPrompt\n")
                        }
                        resultArea.append("Ollama応答: $aiResponse\n")
                        resultArea.append("\n")
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
        // macOSの標準音声認識を使用
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "osascript", "-e", 
                "tell application \"Speech Recognition Server\" to listen for \"\" with timeout of 5"
            ))
            
            // より簡単な方法：ffmpegとwhisperを使用（もしインストールされていれば）
            // ここでは一旦、WAVファイルから簡単な音声認識を試みる
            
            // 実際の実装：Pythonのspeech_recognitionライブラリを使用
            val pythonProcess = Runtime.getRuntime().exec(arrayOf(
                "python3", "-c", 
                """
import speech_recognition as sr
import sys
r = sr.Recognizer()
try:
    with sr.AudioFile('${wavFile.absolutePath}') as source:
        audio = r.record(source)
    text = r.recognize_google(audio, language='ja-JP')
    print(text)
except Exception as e:
    print('音声認識エラー')
                """.trimIndent()
            ))
            
            val result = pythonProcess.inputStream.bufferedReader().readText().trim()
            pythonProcess.waitFor()
            
            if (result.isNotEmpty() && result != "音声認識エラー") {
                result
            } else {
                "音声を認識できませんでした"
            }
        } catch (e: Exception) {
            "音声認識処理エラー: ${e.message}"
        }
    }

    private fun askOllama(prompt: String, model: String, customPrompt: String, imageFile: java.io.File?): String {
        return try {
            val fullPrompt = if (customPrompt.isNotEmpty()) {
                "${prompt}。${customPrompt}"
            } else {
                "${prompt}。日本語で答えてください。"
            }
            
            // モデル名を正しく変換
            val actualModel = when (model) {
                "gemma3_light" -> "gemma3:1b"
                else -> model
            }
            
            // Gemma3（標準版のみ）でマルチモーダル対応
            val json = if (model == "gemma3" && imageFile != null) {
                // 画像をBase64エンコード
                val imageBytes = imageFile.readBytes()
                val imageBase64 = java.util.Base64.getEncoder().encodeToString(imageBytes)
                
                """
                {"model":"$actualModel","prompt":"${fullPrompt.replace("\"","\\\"")}",
                 "images":["$imageBase64"],
                 "stream":false}
                """.trimIndent()
            } else if (model == "gemma3_light" && imageFile != null) {
                // 軽量版は画像非対応の警告
                return "⚠️ Gemma3:1B（軽量版）は画像処理に対応していません。画像を使用する場合は、Gemma3（4B）を選択してください。"
            } else {
                """
                {"model":"$actualModel","prompt":"${fullPrompt.replace("\"","\\\"")}",
                 "stream":false}
                """.trimIndent()
            }
            
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
