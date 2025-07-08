# no11: Voice Recognition & AI Response Desktop App

![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Kotlin-blue)
![License](https://img.shields.io/badge/license-Education-lightgrey)
![Models](https://img.shields.io/badge/models-Gemma2%20%7C%20Gemma3-green)

## 🎯 Overview

no11 is an advanced desktop application that continuously listens to your microphone, detects voice activity, and automatically:

- **🎙️ Records** only the segments where you speak
- **🔤 Transcribes** audio to text using Google Web Speech API
- **🤖 Processes** with AI using **Gemma2** (text-only) or **Gemma3** (multimodal)
- **🖼️ Analyzes** images when using Gemma3 multimodal mode
- **💬 Displays** both recognition results and AI responses in GUI
- **🔊 Reads** AI responses aloud using macOS built-in speech synthesis
- **⚡ No buttons** required—fully automatic voice detection!

## 🆕 New Features (v2.0)

### 🔄 Model Selection
- **Gemma2 (9B)**: High-performance text-only model
- **Gemma3 (4B)**: Cutting-edge multimodal model with image understanding
- **Gemma3:1B (815MB)**: 🆕 **Lightweight version - Default choice!**
- Real-time model availability checking
- Automatic model validation via Ollama API

### ⚡ Performance Comparison
| Model | Size | Speed | Features | Best For |
|-------|------|-------|----------|----------|
| **Gemma3:1B** | 815MB | ⚡⚡⚡ Very Fast | Text + Images | **Daily use, fast responses** |
| Gemma3 (4B) | 3.3GB | ⚡⚡ Fast | Text + Images | Complex multimodal tasks |
| Gemma2 (9B) | 5.4GB | ⚡ Moderate | Text only | High-quality text generation |

### 🖼️ Multimodal Support (All Gemma3 variants)
- **Image Upload**: Select and preview images
- **Visual Analysis**: AI can analyze and describe images
- **Combined Prompts**: Voice + image + custom text prompts
- **Smart Resizing**: Automatic image optimization for AI processing

### 📝 Advanced Prompting
- **5 Preset Prompts**:
  - Standard Japanese response
  - Detailed explanations
  - Concise answers  
  - Image analysis focused
  - Expert analysis mode
- **Custom Prompts**: Fully editable for specific needs
- **Context Awareness**: Combines voice input with custom instructions

### 🎨 Enhanced UI
- **Model Status Display**: Real-time availability (✓/✗)
- **Image Preview**: Thumbnail view of selected images
- **Progress Indicators**: Clear status messages
- **Smart Controls**: Context-sensitive button states

## 🛠️ Prerequisites

### Required Software
- **macOS** (for `say` command and audio processing)
- **Java 17+** 
- **Kotlin** (handled by Gradle)
- **Python 3** with `speech_recognition` library
- **Ollama** server running locally

### AI Models Setup
```bash
# Install Ollama
brew install ollama

# Download models
ollama pull gemma2      # For text-only processing
ollama pull gemma3      # For multimodal processing

# Start Ollama server
ollama serve
```

### Python Dependencies
```bash
pip install SpeechRecognition
```

## 🚀 Installation & Run

### Quick Start
```bash
# Clone and navigate
git clone https://github.com/marutyan/ISP_llama.git
cd ISP_llama/ipro/no11

# Run directly
../gradlew run
```

### Development Build
```bash
# Build only
../gradlew build

# Clean build
../gradlew clean build

# Run tests
../gradlew test
```

## 📖 Usage Guide

### 🎯 Basic Operation
1. **Launch** the application
2. **Wait** for "マイク準備完了．音声待機中....." status
3. **Speak** naturally - recording starts automatically
4. **Listen** as AI responds and reads aloud
5. **Repeat** - microphone reactivates after each cycle

### 🔧 Advanced Configuration

#### Model Selection
- **Gemma2**: Choose for fast, text-only responses
- **Gemma3**: Choose for image analysis capabilities
- Status indicators show model availability in real-time

#### Custom Prompts
- Select from 5 presets or create custom instructions
- Prompts are combined with voice input automatically
- Edit prompts during operation without restart

#### Image Analysis (Gemma3 only)
1. Select **Gemma3** model
2. Click **"画像を選択"** button
3. Choose image file (jpg, png, gif, bmp)
4. Preview appears in GUI
5. Speak your question about the image
6. AI analyzes both voice and image together

### 🎨 Example Workflows

#### Text Analysis
```
1. Select: Gemma2
2. Prompt: "詳しく説明してください。日本語で。"
3. Speak: "機械学習とは何ですか？"
4. Result: Detailed ML explanation in Japanese
```

#### Image Analysis
```
1. Select: Gemma3  
2. Upload: Photo of a cat
3. Prompt: "この画像について詳しく教えてください。日本語で。"
4. Speak: "この動物の特徴を教えて"
5. Result: Detailed analysis of the cat in the image
```

## 🏗️ Project Structure

```
no11/
├── app/
│   ├── src/main/kotlin/kindai/example/
│   │   └── App.kt                 # Main application
│   ├── build.gradle.kts           # Dependencies
│   └── recorded_audio_*.wav       # Voice recordings
├── README.md                      # This file
└── settings.gradle.kts            # Project config
```

## 🔧 Technical Details

### Architecture
- **Voice Detection**: Continuous audio monitoring with VAD
- **Speech Recognition**: Google Web Speech API via Python
- **AI Processing**: Ollama API with model selection
- **GUI**: Swing with modern layout and controls
- **Audio**: javax.sound.sampled for recording/playback

### Key Components
- `VoiceDetector`: Handles continuous audio monitoring
- `AppFrame`: Main GUI with model selection and controls  
- `askOllama()`: Multimodal API communication
- `checkModelStatus()`: Real-time model validation

### Performance Optimizations
- Automatic silence detection and trimming
- Background processing to prevent GUI freezing
- Efficient image resizing and Base64 encoding
- Smart model availability caching

## 🔍 Troubleshooting

### Common Issues

**"モデル状態確認エラー"**
```bash
# Ensure Ollama is running
ollama serve

# Check model availability
ollama list
```

**"音声認識エラー"**
```bash
# Install/update speech recognition
pip install --upgrade SpeechRecognition

# Check microphone permissions in System Preferences
```

**Image preview issues**
- Supported formats: JPG, PNG, GIF, BMP
- File size limit: ~10MB recommended
- Check file permissions and accessibility

## 🤝 Contributing

This is an educational project. Feel free to:
- Report bugs via GitHub Issues
- Suggest improvements
- Submit pull requests
- Share usage examples

## 📄 License

Educational use only. Built with:
- **Kotlin** + **Gradle** for robust JVM development
- **OkHttp** + **Jackson** for API communication  
- **Swing** for cross-platform GUI
- **Ollama** for local AI model serving
- **Google Speech API** for voice recognition

---

**Happy Voice AI Interaction! 🎤🤖** 
