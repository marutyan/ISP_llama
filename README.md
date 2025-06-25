# no11: Voice Recognition & AI Response Desktop App

![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Kotlin-blue)
![License](https://img.shields.io/badge/license-Education-lightgrey)

## Overview

no11 is a desktop application that continuously listens to your microphone, detects voice activity, and automatically:
- Records only the segments where you speak
- Sends the audio to a Django server for speech-to-text
- Sends the recognized text to an Ollama server for AI response
- Displays both the recognition result and AI response in a GUI
- Reads the AI response aloud using macOS's built-in speech synthesis
- No button operation required—fully automatic!

## Features
- **Voice Activity Detection (VAD):** Only records when you speak
- **Automatic Workflow:** Recording → Speech Recognition → AI Response → Display & Speech
- **Modern GUI:** Results and status messages are clearly separated
- **Status Bar:** Shows "Ready", "Recording", and processing states
- **Timestamped WAV files:** Each segment is saved with a unique name
- **macOS Speech Synthesis:** AI responses are spoken aloud

## Getting Started

### Prerequisites
- **macOS** (tested)
- **Java 21**
- **Kotlin** (managed by Gradle)
- **Django server** for speech recognition (running at `http://127.0.0.1:8000/api/upload/`)
- **Ollama server** for AI response (running at `http://localhost:11434/api/generate`)

### Installation & Run
1. **Start the Django server** (in a separate terminal):
   ```sh
   python manage.py runserver
   ```
2. **Start the Ollama server** (in another terminal):
   ```sh
   ollama serve
   ```
3. **Run the app:**
   ```sh
   cd ipro/no11
   ./gradlew run
   ```

### Usage Example
- When you speak, the app automatically records, recognizes, and responds.
- The GUI will show:
  ```
  --- New Voice Segment ---
  Recognition: Hello!
  Ollama Response: Hello! How can I help you today?
  ```
- The status bar at the bottom will show messages like:
  - "Ready. Waiting for voice..."
  - "Recording..."
  - "Saved segment to recorded_audio_xxxxx.wav. Posting to Django..."

## Project Structure
```
ipro/no11/
├── app/
│   ├── src/main/kotlin/kindai/example/App.kt
│   └── ...
├── README.md
├── build.gradle.kts
└── ...
```

## GitHub Integration
- This project is managed at [https://github.com/marutyan/ISP_llama](https://github.com/marutyan/ISP_llama)
- To push changes:
  ```sh
  git add .
  git commit -m "Describe your changes"
  git push
  ```

## License
This repository is for educational purposes only. 