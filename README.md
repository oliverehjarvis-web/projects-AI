# Projects AI - On-Device AI Assistant with Project Context

A native Android app that wraps the Gemma 4 E4B model running locally on device, providing persistent project-scoped memory and context for high-quality content generation.

## Target Device

OnePlus 13 (Snapdragon 8 Elite, 16GB RAM, Android 15)

## Setup

### Prerequisites

- Android Studio Ladybug or later
- JDK 17
- Android SDK 35

### Building

1. Clone this repository
2. Open in Android Studio
3. Sync Gradle
4. Build and run on your device

### Model Setup

The app uses Google's Gemma 4 E4B model via MediaPipe LLM Inference API.

1. Download the Gemma 4 E4B `.task` file from [Kaggle](https://www.kaggle.com/models/google/gemma) or convert it using the MediaPipe model conversion tools
2. Two precision options:
   - **SFP8** (~7.5GB RAM) - Recommended for OnePlus 13. Better quality.
   - **Q4** (~4.5GB RAM) - Lower memory usage, slightly reduced quality.
3. Launch the app, go to **Settings > Model Management**
4. Tap **Import .task File** and select your downloaded model
5. Select your preferred precision and tap **Load**

The model file is stored in the app's private external storage (`Android/data/com.oli.projectsai/files/models/`).

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for details on the inference abstraction and how to add a remote backend.

## Features

- **Projects**: Scoped workspaces with their own context, memory, and chat history
- **Manual Context**: User-curated permanent system prompt per project
- **Accumulated Memory**: Condensed summaries from chats, with pin/promote/compress
- **Token Counter**: Live breakdown of context budget usage
- **Quick Actions**: Custom prompt templates per project
- **Chat History**: Browse and manage past conversations
- **Export/Share**: Copy or share any message via Android share sheet
- **Model Management**: Load/unload model, pick precision, view status
- **Dark Mode**: Follows system setting with dynamic colour support

## Tech Stack

- Kotlin, Jetpack Compose, Material 3
- Room (SQLite) for persistence
- Hilt for dependency injection
- MediaPipe LLM Inference API for on-device inference
- MVVM architecture with clean inference abstraction
