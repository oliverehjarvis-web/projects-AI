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

The app uses Google's Gemma 4 E4B model via [LiteRT-LM](https://github.com/google-ai-edge/litert), Google's on-device LLM runtime.

1. Download a `.litertlm` Gemma 4 file (the **Settings → Model Management** screen offers a one-tap download from `litert-community` on Hugging Face), or import a local `.litertlm` / `.task` / `.bin` file via **Import Model File**.
2. Tap **Load** on the imported file. RAM usage on a 16 GB OnePlus 13 is ≈ 4.5 GB for the E4B build.

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
- LiteRT-LM for on-device inference
- MVVM architecture with clean inference abstraction
