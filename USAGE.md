# Using Projects AI on your phone

This guide walks through installing, setting up, and using the app on an Android device (tested target: OnePlus 13, Android 15).

---

## 1. Install the APK

The app isn't on the Play Store — you sideload the APK from GitHub Releases.

### 1a. Download the APK

On your phone:
1. Open a browser and go to https://github.com/oliverehjarvis-web/projects-AI/releases
2. Tap the latest release (e.g. **Projects AI v0.1.0**)
3. Under **Assets**, tap `projects-ai-v0.1.0.apk` to download it

Alternatively, from a laptop: `gh release download v0.1.0 --repo oliverehjarvis-web/projects-AI` and AirDrop / copy it to the phone.

### 1b. Allow install from unknown sources

The first time you open an APK in your browser or file manager, Android will prompt you to grant that app permission to install unknown apps:

- **Settings → Apps → [your browser/file manager] → Install unknown apps → Allow**

Once allowed, tap the downloaded APK in your Downloads folder or notification tray → **Install**.

### 1c. First launch

Open the **Projects AI** app from your launcher. You'll land on an empty Home screen with a "No projects yet" message and a top-bar chip saying **"No model"**.

Don't create a project yet — load the model first.

---

## 2. Load the Gemma 4 E4B model

The app runs the model entirely on-device. The easiest way is to download it directly from the app.

### 2a. Download in-app (recommended)

1. Tap the **⚙ Settings** icon → **Model Management**
2. Under **Recommended Model**, tap **Download**
3. A system notification shows download progress. The file is 3.65 GB — on good Wi-Fi, expect 5–10 minutes.
4. When the download finishes, the model appears under **Available Models** and the button shows a tick ✓.

> Download over Wi-Fi, not mobile data.

### 2b. Load the model

Once downloaded (or imported — see 2c), tap **Load** next to the file.

The status card will show "Loading..." then "Loaded". The top-bar chip on every other screen will change from **"No model"** to the model name.

> If loading fails with an OOM error, close memory-hungry apps (Chrome, Maps, etc.) and try again.

### 2c. Manual import (alternative)

If you prefer to download the file outside the app:

- File: `gemma-4-E4B-it.litertlm` (3.65 GB)
- Source: [huggingface.co/litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) — Apache 2.0, no login required
- Transfer to phone (USB, AirDrop, KDE Connect/Snapdrop, etc.)
- In Model Management, tap **Import Model File** and select it

---

## 3. Create your first project

Projects are scoped workspaces — each one has its own system-prompt context, memory, and chat history. Think of one project per area of your life or work (e.g. "Work - Kindstock", "Personal finance", "RSPCA Cornwall", "Holidays 2026").

1. On the Home screen, tap the **+** FAB in the bottom-right
2. Fill in:
   - **Project name** — required, short and memorable (e.g. "Kindstock EPOS")
   - **Description** — optional, one-line summary
   - **Manual Context** — the permanent system-prompt info you want the model to always know about this project. Things to include:
     - Your role (e.g. "I'm the technical lead on the EPOS project")
     - Key people and their roles
     - Project background and current state
     - Tone preferences ("Write in a friendly but professional UK English tone")
     - Anything else the model should know before every chat
   - **Memory token limit** — defaults to 8000. Leave it unless you have a specific reason.
3. Below the context field you'll see a live **token count** so you can gauge how much of the context budget the manual context is eating
4. Tap **✓ Save** in the top-right

The project now appears on the Home screen.

### Tips for writing Manual Context

- **Be concrete.** "The EPOS project is a point-of-sale system for charity shops. Stack: Kotlin Android + Supabase" is worth more than "It's about software."
- **Keep it under ~1500 tokens** so memory and chat history have room to breathe.
- **Update it** when the project state changes — this is your curated source of truth.

---

## 4. Start chatting

1. From the Home screen, either tap the **💬 chat bubble** on a project card (quick new chat), or tap into the project and then tap the **+** FAB
2. Type a message and tap send (or hit the IME Send key)
3. The model response streams back into the chat
4. Each assistant message has small icons beneath it for **Copy** and **Share** — share opens the Android share sheet so you can forward straight to Gmail, WhatsApp, Messages, Slack, etc.

Chats are automatically saved against the project. The first message becomes the chat title (truncated to 50 chars).

### The top-bar chips

While chatting, three things in the top bar help you stay oriented:

- **📊 Token usage bar** just under the app bar — coloured segments show system prompt (blue), memory (green), conversation (amber), and remaining free space (grey). Tap the **DataUsage** icon to expand it into a full breakdown with exact token counts per segment.
- When total usage crosses **75%** the "Free" label turns red — start a new chat or add-to-memory to free up space.
- **🧠 Psychology icon** — opens the "Add to Memory" flow (see below).

---

## 5. Quick Actions — saved prompt templates

Projects have custom prompt templates so you can kick off common tasks in one tap.

### Create a quick action
1. Open a project
2. Under the **Quick Actions** row, tap the small **+** button
3. Give it a **Name** (e.g. "Draft sponsor email") and a **Prompt template** (the actual text the model will see as the first user message)
4. Tap **Add**

### Use a quick action
1. On the project detail screen, tap the quick action chip
2. A new chat starts with the template pre-sent as your first message
3. The model responds; you can then continue the conversation normally

Examples of useful templates:
- **"Draft sponsor email"** → `Draft a polite sponsorship outreach email for [PROJECT]. Keep it under 150 words and friendly but direct.`
- **"Summarise meeting"** → `I'm going to paste meeting notes. Summarise into: decisions made, action items with owners, and open questions.`
- **"Weekly update"** → `Based on memory, write a 3-paragraph weekly update covering wins, blockers, and next steps.`

---

## 6. Memory — the magic bit

Memory is what makes per-project chats feel like the model remembers you across sessions. Manual context is curated and permanent; **accumulated memory** is built up over time from real conversations.

### Add to Memory from a chat

After a useful chat, tap the **🧠 Psychology** icon in the chat top bar.

A dialog pops up. You have two paths:

**a) Auto-summarise** — tap the button and the app produces a starter summary. *(In v0.1 this is a simple line extractor; a future version will ask the model to condense the conversation properly.)* Edit the summary to your liking.

**b) Write your own** — skip the auto-summarise button and just type notes directly.

Either way:
1. Edit the text until it's useful
2. Tap **Save to Memory**

The notes get appended to the project's accumulated memory with a separator.

### View and edit memory

On the project detail screen, tap the **Memory** card (the green one on the right).

The Memory screen shows:
- **Token usage bar** at the top — how much of your configured token budget memory is using
- **Pinned section** (if any) — items you've marked protected from compression
- **Accumulated Memory** — rendered as separate blocks (one per add-to-memory session, split by `---`)

**Actions per memory block** (tap a block to expand):
- **📌 Pin** — protect this block so future compressions won't touch it
- **⬆ Promote to context** — copy the block into the project's permanent Manual Context (useful when a fact from a chat turns out to be permanently true)

**Top-bar actions on the Memory screen:**
- **✏ Edit** — opens a full-screen editor so you can manually rewrite anything (delete blocks, fix typos, reformat)
- **⇣ Compress** — asks the model to consolidate and deduplicate memory *(placeholder in v0.1 — full implementation coming)*

### When memory gets full

When accumulated memory exceeds your configured token limit:
- The usage bar turns red and shows "Memory exceeds token limit"
- Your options: **Compress**, **Edit** (manually trim), or **Promote** important blocks to Manual Context then delete them from memory

---

## 7. Managing chats and projects

### On the project detail screen
- **Chat list** — tap a chat to reopen it, tap **⋮** next to it to delete
- **Edit project** — pencil icon in top bar; change name/description/context/limit
- **Delete project** — trash icon in top bar (confirmation required — deletes all chats and memory too)

### On the home screen
- **Long-press** a project card to get the delete confirmation

---

## 8. Settings and Model Management

Access via the **⚙** icon on the Home screen.

### Settings screen
- **Model Management** — same screen you used in step 2
- **Backends** — shows which inference backends are available: **Local (LiteRT-LM)** runs on-device, and **Remote (HTTP)** streams from the FastAPI server in `/server`.
- **About** — version info

### Model Management screen
Useful operations:
- **Unload Model** — frees RAM if you want to do other intensive things on the phone
- **Load** a different model file or switch precision
- **Import .task File** — add a new model file

---

## 9. Dark mode

The app follows your system dark/light setting. On Android 12+ it also picks up your wallpaper-based Material You colour scheme. If you want to force one mode, change it in Android's system settings — there's no in-app toggle in v0.1.

---

## 10. Troubleshooting

| Symptom | Fix |
|---|---|
| **"No model loaded"** error when sending a chat | Go to Settings → Model Management and tap **Load** next to your model file |
| **Model load fails with OOM** | Close other apps (Chrome/Maps/games) and try again. If SFP8 still OOMs, switch to Q4 |
| **Import model file fails** | Make sure the file is a `.litertlm`, `.task`, or `.bin` LiteRT-LM-format file, not a raw Hugging Face checkpoint |
| **Chat feels slow on first message** | First inference warms the KV cache; subsequent responses are faster. If it's still slow, try Q4 |
| **App won't install (APK rejected)** | Enable "Install unknown apps" for your browser/file manager in system settings |
| **Memory token counter looks wrong** | The count is currently a ~3.5 chars/token approximation — it's a budget indicator, not a precise figure. Will be replaced with the real tokeniser in a future version |
| **App crashes on startup** | Clear app data via Android settings and re-import the model. If that doesn't help, check `adb logcat \| grep -iE 'projectsai\|litertlm\|LocalLiteRT'` |

---

## 11. Power user tips

- **One project per context switch.** Don't pile everything into "General" — the whole point of the app is scoped memory.
- **Curate manual context ruthlessly.** Every token in manual context is a token not available for conversation. Promote from memory when something crystallises into a permanent fact, and trim memory lines that are one-offs.
- **Use quick actions for anything you do more than twice.** Naming templates forces you to clarify what you actually want the model to do.
- **Pin aggressively.** If a memory line is "these are the API keys", "my tone is dry and direct", or "the client's name is spelled X not Y", pin it — future compressions will leave it alone.
- **Don't chase 100% context usage.** When the usage bar hits ~60-70%, start a new chat. Long conversations lose coherence on small models regardless of the context window size.
- **Keep debug/release separate.** v0.1 APKs are debug-signed, so they have `.debug` on the package name and install alongside any future release-signed build without conflict.

---

## 12. Updating the app

When a new version is released:
1. Download the new APK from the Releases page
2. Tap to install — Android will install it as an update (same package signing)
3. **Your projects, chats, and memory are preserved** — they live in the app's Room database and survive updates

If you ever need to wipe everything: Android Settings → Apps → Projects AI → Storage → **Clear data**.

---

## 13. What's not in v0.1 (yet)

- **True token streaming** — v0.1 emits the full response at once. The plumbing is there to switch to streaming once happy path is verified.
- **Model-powered memory summarisation** — "Add to Memory" auto-summarise is currently a line extractor, not a model call.
- **Memory compression** — button exists but is a placeholder.
- **Selective memory loading** — v0.1 loads the full accumulated memory into every chat. Selective retrieval is a v0.2 feature.
- **Voice input / image input** — not wired up yet.
- **Remote backend** — the inference abstraction supports it, but no UI to configure a home NAS endpoint. Coming in v0.2.
- **Cross-device sync / backup** — no cloud sync. Your data lives on this one phone. Back it up manually if you care (future: JSON export/import).
- **Proper release signing** — v0.1 APKs are debug-signed. Fine for personal sideloading, not for public distribution.

See https://github.com/oliverehjarvis-web/projects-AI/issues for the current roadmap and to raise anything that bugs you.
