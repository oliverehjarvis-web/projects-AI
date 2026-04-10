# Architecture

## Overview

Cortex uses MVVM with a clean separation between the inference layer and the rest of the app. The key design decision is the `InferenceBackend` interface, which allows swapping between local and remote model execution without touching UI or data code.

## Inference Abstraction

```
┌─────────────────────────────────────────────┐
│                InferenceManager              │
│  - Manages backend lifecycle                 │
│  - Routes generation requests                │
│  - Exposes model state                       │
├──────────────────┬──────────────────────────┤
│ LocalMediaPipe   │  RemoteHttpBackend       │
│ Backend          │  (TODO - v2)             │
│                  │                          │
│ Runs Gemma 4 E4B │  Hits OpenAI-compatible  │
│ via MediaPipe    │  endpoint on home NAS    │
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

### Adding a Remote Backend

To add a remote backend (e.g., a larger model on a home NAS):

1. Create `RemoteHttpBackend` implementing `InferenceBackend`
2. The `generate()` method should hit an OpenAI-compatible `/v1/chat/completions` endpoint with `stream: true` and yield tokens from SSE chunks
3. `countTokens()` can either call a remote tokenisation endpoint or use a local approximation
4. Register the new backend in `InferenceManager.backends` map
5. Add a configuration screen for URL, API key, and model name
6. The `Project.preferredBackend` field already supports `LOCAL` and `REMOTE` enum values

No changes needed to UI, ViewModels, or data layer - the `InferenceManager` handles routing.

## Data Layer

```
Room Database (cortex.db)
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

Currently uses a character-based approximation (~3.5 chars per token). When MediaPipe exposes a tokeniser count API, replace the implementation in `LocalMediaPipeBackend.countTokens()`. The approximation is accurate enough for budget tracking.

## Module Structure

```
com.oli.cortex/
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
