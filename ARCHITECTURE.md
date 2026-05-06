# Architecture

## Overview

Projects AI uses MVVM with a clean separation between the inference layer and the rest of the app. The key design decision is the `InferenceBackend` interface, which allows swapping between local and remote model execution without touching UI or data code.

## Inference Abstraction

```
┌─────────────────────────────────────────────┐
│                InferenceManager              │
│  - Manages backend lifecycle                 │
│  - Routes generation requests                │
│  - Exposes model state                       │
├──────────────────┬──────────────────────────┤
│ LocalLiteRt      │  RemoteHttpBackend       │
│ Backend          │                          │
│                  │                          │
│ Runs Gemma 4 E4B │  Streams from the home   │
│ via LiteRT-LM    │  NAS over an OpenAI-     │
│ on the phone     │  compatible HTTP API     │
└──────────────────┴──────────────────────────┘
```

### InferenceBackend Interface

All backends implement this interface:

```kotlin
interface InferenceBackend {
    val id: String
    val displayName: String
    val isAvailable: Boolean
    val isLoaded: Boolean
    val loadedModel: ModelInfo?

    suspend fun loadModel(modelInfo: ModelInfo)
    suspend fun unloadModel()
    suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig
    ): Flow<String>  // streamed tokens
    suspend fun countTokens(text: String): Int
}
```

### Adding another backend

`RemoteHttpBackend` is the second backend already shipping (since v2.1.0). It streams SSE from the FastAPI server in `/server`, which proxies to Ollama. To add a third backend:

1. Implement `InferenceBackend` in `inference/`.
2. The `generate()` method should yield streamed tokens as a `Flow<String>`.
3. `countTokens()` can call a remote tokeniser or use a local approximation (the on-device backend currently uses ~4 chars/token — see `LocalLiteRtBackend.countTokens`).
4. Register the new backend in the `backends` map in `InferenceManager`.
5. Reuse `data/preferences/` for any per-backend config (URL, token, etc.) — the same DataStore pattern already powers `RemoteSettings`, `GitHubSettings`, and friends.
6. Extend `PreferredBackend` if a project should be allowed to pin to it.

No UI / ViewModel / data-layer churn is needed for the routing itself — `InferenceManager` looks up by `id` string.

## Data Layer

```
Room Database (projects_ai.db)
├── projects     - Workspaces with context and memory
├── chats        - Conversations belonging to projects
├── messages     - Individual messages in chats
└── quick_actions - Prompt templates per project
```

Projects hold both manual context (curated, permanent) and accumulated memory (appended from chats) as text fields directly on the entity, keeping the schema simple for a single-user app.

## System Prompt Assembly

When a chat starts, the system prompt is assembled from:

1. Project manual context (always included)
2. Accumulated memory (included if under token threshold, or user-selected portions)

This is handled in `ChatViewModel.loadProjectContext()`.

## Token Counting

Currently uses a character-based approximation (~4 chars per token, calibrated against Gemma SentencePiece). LiteRT-LM 0.10 does not expose a tokeniser; once it does, replace the implementation in `LocalLiteRtBackend.countTokens()`. The approximation is accurate enough for budget tracking.

## Module Structure

```
com.oli.projectsai/
├── data/
│   ├── db/          - Room entities, DAOs, type converters
│   └── repository/  - Repository pattern over DAOs
├── inference/       - Backend interface, implementations, manager
├── ui/
│   ├── theme/       - Material 3 theming
│   ├── navigation/  - Nav graph and routes
│   ├── home/        - Project list
│   ├── project/     - Project detail/edit
│   ├── chat/        - Chat UI
│   ├── memory/      - Memory viewer/editor
│   ├── settings/    - Settings and model management
│   └── components/  - Reusable UI (token counter)
└── di/              - Hilt dependency injection modules
```
