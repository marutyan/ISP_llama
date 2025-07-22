package kindai.example

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*
import kotlin.concurrent.thread
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.util.Base64

// 🎨 Material 3 Color Palette
object AppTheme {
    val Primary = Color(0xFF6750A4)
    val Secondary = Color(0xFF625B71)
    val Tertiary = Color(0xFF7D5260)
    val Background = Color(0xFFFFFBFE)
    val Surface = Color(0xFFFFFBFE)
    val SurfaceVariant = Color(0xFFE7E0EC)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnSecondary = Color(0xFFFFFFFF)
    val OnBackground = Color(0xFF1C1B1F)
    val OnSurface = Color(0xFF1C1B1F)
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFF9800)
    val Error = Color(0xFFF44336)
}

/* ─────────────────────── v1.0の音声検出・録音ロジック ─────────────────────── */
object VoiceDetector {
    private val fmt = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private val isListening = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false) // AI音声合成中フラグ
    private var listenerThread: Thread? = null
    private val dateFormat = java.text.SimpleDateFormat("yyyyMMddHHmmssSSS")
    
    // 音声検出の閾値設定（v1.0の値を使用）
    private val SILENCE_THRESHOLD = 1000.0  // 音声検出の閾値（適度な感度に調整）
    private val SILENCE_DURATION = 3000    // 無音が続く時間(ms)（3秒に延長）
    private val MIN_RECORDING_DURATION = 1500 // 最小録音時間(ms)（1.5秒に延長）
    
    fun startListening(
        onVoiceDetected: () -> Unit,
        onVoiceEnded: (File) -> Unit,
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

    // AI音声合成開始時に呼び出し
    fun setSpeaking(speaking: Boolean) {
        isSpeaking.set(speaking)
    }
    
    private fun listenForVoice(
        onVoiceDetected: () -> Unit,
        onVoiceEnded: (File) -> Unit,
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
                            // 音声検出（AI音声合成中は録音開始しない）
                            if (!isRecording.get() && !isSpeaking.get()) {
                                isRecording.set(true)
                                recordingStart = currentTime
                                audioBuffer.reset()
                                onVoiceDetected()
                            }
                            silenceStart = currentTime
                            if (isRecording.get()) {
                                audioBuffer.write(buffer, 0, bytesRead)
                            }
                        } else {
                            // 無音
                            if (isRecording.get()) {
                                audioBuffer.write(buffer, 0, bytesRead)
                                
                                if (currentTime - silenceStart > SILENCE_DURATION &&
                                    currentTime - recordingStart > MIN_RECORDING_DURATION) {
                                    // 録音終了
                                    isRecording.set(false)
                                    val audioData = audioBuffer.toByteArray()
                                    
                                    // WAVファイルに保存
                                    val timestamp = dateFormat.format(java.util.Date())
                                    val wavFile = File("app/recorded_audio_$timestamp.wav")
                                    saveAsWav(audioData, wavFile)
                                    
                                    onVoiceEnded(wavFile)
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
                onError("音声検出エラー: ${e.message}")
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

    private fun saveAsWav(audioData: ByteArray, file: File) {
        file.parentFile?.mkdirs()
        val audioInputStream = AudioInputStream(
            ByteArrayInputStream(audioData),
            fmt,
            audioData.size / fmt.frameSize.toLong()
        )
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, file)
        println("録音ファイル保存場所: ${file.absolutePath}")
    }
}

// 🌐 HTTP クライアント
object Http {
    val client = OkHttpClient.Builder()
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val mapper = jacksonObjectMapper()
}

// 🔊 音声合成状態管理
object SpeechManager {
    private val isSpeaking = AtomicBoolean(false)
    private var currentProcess: Process? = null
    private var speechSpeed: Int = 200 // デフォルト速度（words per minute）
    
    fun setSpeaking(speaking: Boolean) {
        isSpeaking.set(speaking)
        VoiceDetector.setSpeaking(speaking)
    }
    
    fun isSpeaking(): Boolean = isSpeaking.get()
    
    fun setSpeechSpeed(speed: Int) {
        speechSpeed = speed.coerceIn(100, 400) // 100-400 wpm の範囲に制限
    }
    
    fun getSpeechSpeed(): Int = speechSpeed
    
    fun stopSpeaking() {
        currentProcess?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                println("音声合成を強制停止しました")
            }
        }
        setSpeaking(false)
    }
    
    fun speakText(text: String) {
        try {
            setSpeaking(true)
            val cleanText = text.replace("\"", "\\\"")
            // macOSのsayコマンドに速度パラメータを追加
            val process = ProcessBuilder("say", "-r", speechSpeed.toString(), cleanText).start()
            currentProcess = process
            process.waitFor()
            setSpeaking(false)
        } catch (e: Exception) {
            println("音声合成エラー: ${e.message}")
            setSpeaking(false)
        } finally {
            currentProcess = null
        }
    }
}

// 🎯 メインアプリケーション
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAIApp() {
    var isListening by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("🎤 マイク準備完了．音声待機中.....") }
    var statusColor by remember { mutableStateOf(AppTheme.Success) }
    var resultText by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("gemma3_light") }
    var customPrompt by remember { mutableStateOf("日本語で答えてください。") }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var modelStatus by remember { mutableStateOf("🔍 モデル状態確認中...") }
    
    // 🔊 音声合成関連のstate
    var isSpeaking by remember { mutableStateOf(false) }
    var speechSpeed by remember { mutableStateOf(SpeechManager.getSpeechSpeed()) }
    
    // v1.0の音声検出ロジックを使用（objectなのでrememberは不要）

    // モデル状態チェック
    LaunchedEffect(selectedModel) {
        modelStatus = "🔍 ${getModelDisplayName(selectedModel)} 状態確認中..."
        val isAvailable = checkModelStatus(selectedModel)
        modelStatus = if (isAvailable) {
            "✅ ${getModelDisplayName(selectedModel)} 利用可能"
        } else {
            "❌ ${getModelDisplayName(selectedModel)} 利用不可"
        }
    }

    // 音声合成状態の監視
    LaunchedEffect(Unit) {
        while (true) {
            isSpeaking = SpeechManager.isSpeaking()
            delay(100) // 100ms間隔で状態をチェック
        }
    }

    // アプリ開始時に音声検出を開始（v1.0ロジック適用）
    LaunchedEffect(Unit) {
        VoiceDetector.startListening(
            onVoiceDetected = {
                statusMessage = "🎙️ 録音中"
                statusColor = AppTheme.Warning
            },
            onVoiceEnded = { audioFile ->
                if (!isProcessing) {
                    isProcessing = true
                    statusMessage = "💾 音声セグメントを${audioFile.name}に保存しました．AI処理中..."
                    statusColor = AppTheme.Warning
                    
                    // バックグラウンドで音声認識とAI応答を処理
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val transcription = recognizeSpeech(audioFile)
                            val aiResponse = askOllama(transcription, selectedModel, customPrompt, selectedImageFile)
                            
                            // UI更新
                            val modelInfo = when (selectedModel) {
                                "gemma2" -> "Gemma2 (9.2B parameters - 高品質テキスト生成)"
                                "gemma3" -> "Gemma3 (4.3B parameters - マルチモーダル対応)"
                                "gemma3_light" -> "⚡ Gemma3:1B (815MB - 軽量・高速)"
                                else -> selectedModel
                            }
                            
                            val newResult = buildString {
                                append("---新しい音声セグメント---\n")
                                append("認識結果: $transcription\n")
                                append("使用モデル: $modelInfo\n")
                                if (selectedImageFile != null) {
                                    append("画像: ${selectedImageFile!!.name}\n")
                                }
                                if (customPrompt.isNotEmpty() && customPrompt != "日本語で答えてください。") {
                                    append("カスタムプロンプト: $customPrompt\n")
                                }
                                append("Ollama応答: $aiResponse\n\n")
                            }
                            
                            resultText = newResult + resultText
                            
                            // 音声合成で読み上げ（録音停止制御付き）
                            SpeechManager.speakText(aiResponse)
                            
                            statusMessage = "🎤 マイク準備完了．音声待機中....."
                            statusColor = AppTheme.Success
                        } catch (e: Exception) {
                            statusMessage = "❌ エラー: ${e.message}"
                            statusColor = AppTheme.Error
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            },
            onError = { error ->
                statusMessage = "❌ 音声検出エラー: $error"
                statusColor = AppTheme.Error
            }
        )
        isListening = true
    }

    // UI構成
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AppTheme.Primary,
            secondary = AppTheme.Secondary,
            tertiary = AppTheme.Tertiary,
            background = AppTheme.Background,
            surface = AppTheme.Surface,
            surfaceVariant = AppTheme.SurfaceVariant,
            onPrimary = AppTheme.OnPrimary,
            onSecondary = AppTheme.OnSecondary,
            onBackground = AppTheme.OnBackground,
            onSurface = AppTheme.OnSurface
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                tint = AppTheme.Primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "音声認識AI アプリケーション v2.0",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppTheme.Surface,
                        titleContentColor = AppTheme.OnSurface
                    )
                )
            },
            bottomBar = {
                Surface(
                    color = AppTheme.SurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = modelStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.OnSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                // 設定パネル
                SettingsPanel(
                    selectedModel = selectedModel,
                    onModelChange = { selectedModel = it },
                    customPrompt = customPrompt,
                    onPromptChange = { customPrompt = it },
                    selectedImageFile = selectedImageFile,
                    onImageFileChange = { selectedImageFile = it },
                    isSpeaking = isSpeaking,
                    speechSpeed = speechSpeed,
                    onStopSpeaking = { 
                        SpeechManager.stopSpeaking()
                        statusMessage = "🎤 マイク準備完了．音声待機中....."
                        statusColor = AppTheme.Success
                    },
                    onSpeechSpeedChange = { 
                        speechSpeed = it
                        SpeechManager.setSpeechSpeed(it)
                    }
                )
                
                Spacer(Modifier.height(16.dp))
                
                // 結果表示エリア
                ResultDisplay(
                    resultText = resultText,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    customPrompt: String,
    onPromptChange: (String) -> Unit,
    selectedImageFile: File?,
    onImageFileChange: (File?) -> Unit,
    isSpeaking: Boolean,
    speechSpeed: Int,
    onStopSpeaking: () -> Unit,
    onSpeechSpeedChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                "🔧 設定",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppTheme.Primary
            )
            
            Spacer(Modifier.height(16.dp))
            
            // モデル選択
            Text(
                "AI モデル選択",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModelRadioButton(
                    selected = selectedModel == "gemma2",
                    onClick = { onModelChange("gemma2") },
                    icon = Icons.Filled.Star,
                    title = "Gemma2",
                    subtitle = "9B - 高性能",
                    color = AppTheme.Primary
                )
                
                ModelRadioButton(
                    selected = selectedModel == "gemma3",
                    onClick = { onModelChange("gemma3") },
                    icon = Icons.Filled.Palette,
                    title = "Gemma3",
                    subtitle = "4B - マルチモーダル",
                    color = AppTheme.Secondary
                )
                
                ModelRadioButton(
                    selected = selectedModel == "gemma3_light",
                    onClick = { onModelChange("gemma3_light") },
                    icon = Icons.Filled.Speed,
                    title = "Gemma3:1B",
                    subtitle = "軽量・高速",
                    color = AppTheme.Success
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            // プロンプト設定
            Text(
                "プロンプト設定",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            
            val promptPresets = listOf(
                "日本語で答えてください。",
                "簡潔に答えてください。日本語で。",
                "短く説明してください。日本語で。",
                "詳しく説明してください。日本語で。",
                "専門的な観点から分析してください。日本語で。"
            )
            
            var expanded by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = onPromptChange,
                    label = { Text("カスタムプロンプト") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    promptPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset) },
                            onClick = {
                                onPromptChange(preset)
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // 画像アップロード（Gemma3のみ）
            if (selectedModel == "gemma3") {
                Text(
                    "画像アップロード",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val fileDialog = FileDialog(Frame(), "画像を選択", FileDialog.LOAD)
                            fileDialog.setFilenameFilter { _, name ->
                                name.lowercase().endsWith(".png") || 
                                name.lowercase().endsWith(".jpg") || 
                                name.lowercase().endsWith(".jpeg")
                            }
                            fileDialog.isVisible = true
                            
                            if (fileDialog.file != null) {
                                val selectedFile = File(fileDialog.directory, fileDialog.file)
                                onImageFileChange(selectedFile)
                            }
                        }
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("画像選択")
                    }
                    
                    if (selectedImageFile != null) {
                        OutlinedButton(
                            onClick = { onImageFileChange(null) }
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("クリア")
                        }
                        
                        Text(
                            "📷 ${selectedImageFile.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.Success
                        )
                    }
                }
                
                Spacer(Modifier.height(20.dp))
                
                // 🔊 音声合成コントロール
                Text(
                    "🔊 音声合成設定",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 読み上げ停止ボタン
                    Button(
                        onClick = onStopSpeaking,
                        enabled = isSpeaking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSpeaking) AppTheme.Error else AppTheme.Error.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (isSpeaking) Icons.Filled.Stop else Icons.Filled.VolumeOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isSpeaking) "読み上げ停止" else "読み上げ停止",
                            fontSize = 14.sp
                        )
                    }
                    
                    // 読み上げ状態表示
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSpeaking) AppTheme.Warning.copy(alpha = 0.1f) else AppTheme.Success.copy(alpha = 0.1f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                if (isSpeaking) Icons.Filled.RecordVoiceOver else Icons.Filled.Mic,
                                contentDescription = null,
                                tint = if (isSpeaking) AppTheme.Warning else AppTheme.Success,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isSpeaking) "読み上げ中" else "録音可能",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSpeaking) AppTheme.Warning else AppTheme.Success,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                // 読み上げ速度調整
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "読み上げ速度",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${speechSpeed} wpm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.Primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Slider(
                        value = speechSpeed.toFloat(),
                        onValueChange = { onSpeechSpeedChange(it.toInt()) },
                        valueRange = 100f..400f,
                        steps = 29, // 100から400まで10刻み
                        colors = SliderDefaults.colors(
                            thumbColor = AppTheme.Primary,
                            activeTrackColor = AppTheme.Primary,
                            inactiveTrackColor = AppTheme.Primary.copy(alpha = 0.3f)
                        )
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "遅い (100)",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.OnSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            "普通 (200)",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.OnSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            "速い (400)",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.OnSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) color.copy(alpha = 0.1f) else AppTheme.SurfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, color) else null,
        modifier = Modifier.width(120.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) color else AppTheme.OnSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) color else AppTheme.OnSurface,
                textAlign = TextAlign.Center
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.OnSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ResultDisplay(
    resultText: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ChatBubble,
                    contentDescription = null,
                    tint = AppTheme.Primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "認識結果・AI応答",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.Primary
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            if (resultText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.MicNone,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = AppTheme.OnSurface.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "音声を検出すると、ここに結果が表示されます",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.OnSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.SurfaceVariant),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.OnSurface,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

// 🎤 音声認識機能
fun recognizeSpeech(audioFile: File): String {
    return try {
        val process = ProcessBuilder("python3", "-c", """
import speech_recognition as sr
import sys

r = sr.Recognizer()
with sr.AudioFile('${audioFile.absolutePath}') as source:
    audio = r.record(source)
try:
    result = r.recognize_google(audio, language='ja-JP')
    print(result)
except sr.UnknownValueError:
    print("音声を認識できませんでした")
except sr.RequestError as e:
    print(f"Google Speech Recognition service error: {e}")
        """.trimIndent()).start()
        
        process.waitFor()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "音声認識エラー: ${e.message}"
    }
}

// 🤖 Ollama API呼び出し
fun askOllama(prompt: String, model: String, customPrompt: String, imageFile: File?): String {
    return try {
        val fullPrompt = if (customPrompt.isNotEmpty()) {
            "${prompt}。${customPrompt}"
        } else {
            "${prompt}。日本語で答えてください。"
        }

        val actualModel = when (model) {
            "gemma3_light" -> "gemma3:1b"
            else -> model
        }

        if (model == "gemma3_light" && imageFile != null) {
            return "⚠️ Gemma3:1B（軽量版）は画像処理に対応していません。画像を使用する場合は、Gemma3（4B）を選択してください。"
        }

        val json = if (model == "gemma3" && imageFile != null) {
            val imageBytes = imageFile.readBytes()
            val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
            
            """
            {"model":"$actualModel","prompt":"${fullPrompt.replace("\"","\\\"")}",
             "images":["$imageBase64"],
             "stream":false}
            """.trimIndent()
        } else {
            """
            {"model":"$actualModel","prompt":"${fullPrompt.replace("\"","\\\"")}",
             "stream":false}
            """.trimIndent()
        }

        val request = Request.Builder()
            .url("http://localhost:11434/api/generate")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = Http.client.newCall(request).execute()
        
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: ""
            val jsonResponse = Http.mapper.readValue<Map<String, Any>>(responseBody)
            cleanOllamaResponse(jsonResponse["response"]?.toString() ?: "応答なし")
        } else {
            "Ollama APIエラー (ステータス: ${response.code})"
        }
    } catch (e: Exception) {
        "Ollama API例外: ${e.message}"
    }
}

// 🔍 モデル状態確認
fun checkModelStatus(modelName: String): Boolean {
    return try {
        val actualModelName = when (modelName) {
            "gemma3_light" -> "gemma3:1b"
            else -> modelName
        }
        val json = """{"model":"$actualModelName","prompt":"test","stream":false}"""
        val request = Request.Builder()
            .url("http://localhost:11434/api/generate")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = Http.client.newCall(request).execute()
        response.isSuccessful
    } catch (e: Exception) {
        false
    }
}

// 🎯 モデル表示名取得
fun getModelDisplayName(model: String): String {
    return when (model) {
        "gemma2" -> "🏆 Gemma2 (9B)"
        "gemma3" -> "🎨 Gemma3 (4B)"
        "gemma3_light" -> "⚡ Gemma3:1B (軽量版)"
        else -> model
    }
}

// 🧹 Ollama応答クリーンアップ
fun cleanOllamaResponse(response: String): String {
    return response
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^\\s+|\\s+$"), "")
        .take(1000)
}



// 🚀 メイン関数
fun main() = application {
    Window(
        onCloseRequest = {
            VoiceDetector.stopListening() // 音声検出を停止
            exitApplication()
        },
        title = "🎙️ 音声認識AI アプリケーション v2.0 - Modern UI",
        state = WindowState(width = 1000.dp, height = 800.dp)
    ) {
        VoiceAIApp()
    }
}
