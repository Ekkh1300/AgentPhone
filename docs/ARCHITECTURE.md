# AgentPhone architecture

AgentPhone is a single-activity Jetpack Compose app with two Gradle modules:

| Module | Purpose |
| --- | --- |
| `:app` | UI, domain, provider layer, agent engine, tools, persistence, workers. |
| `:accessibility` | The optional `AccessibilityService` used by `screen_snapshot` / `screen_action`. It is a separate module so the sensitive service has its own manifest and cannot be pulled into unrelated components. |

Gradle: AGP 8.13.1, Kotlin 2.2.21, KSP, Hilt, compileSdk/targetSdk 36, minSdk 26,
Java 17. All dependency versions live in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

## Package map

```
com.agentphone.app
├── AgentPhoneApp.kt          Application; Hilt + WorkManager Configuration.Provider
├── MainActivity.kt           Edge-to-edge Compose host, share/deep-link entry points
├── agent/                    AgentEngine, ContextManager, ToolRegistry, TitleGenerator
├── data/
│   ├── backup/               Chat export/import (JSON)
│   ├── local/                Room database, entities, DAOs
│   ├── preferences/          DataStore-backed settings, encrypted secrets
│   ├── remote/               OpenAI-compatible adapter, SSE parser, request builder,
│   │                         stream accumulator, URL normalizer, error mapping
│   ├── repository/           Chat, Provider, Audit repositories
│   └── security/             SecretCipher (Android Keystore AES-GCM)
├── domain/
│   ├── agent/                AgentTool contract, risk levels, approval types,
│   │                         PromptInjectionDetector
│   ├── model/                Chat, ChatMessage, Attachment, ProviderProfile, AppSettings
│   └── provider/             ProviderAdapter contract + wire DTOs
├── platform/                 Permissions, attachments, camera capture, clipboard,
│                             downloads, FileProvider, all-files access, SAF, notification
│                             listener, notifications, TTS, UI-action bus
├── tools/                    74 AgentTool implementations (see TOOLS.md)
├── ui/                       Compose screens, ViewModels, theme, components
├── widget/                   App widget + Quick Settings tile
└── worker/                   Background agent + reminder workers
```

## Layers and dependency direction

```
ui  ──────────────►  agent  ─────►  data/remote (provider adapters)
 │                     │
 │                     ├───────►  tools ─────►  platform
 │                     │
 └────►  data/repository  ◄──────  data/local (Room)
                     │
                     └───────►  data/preferences (DataStore)
```

Rules used throughout:

- ViewModels never touch OkHttp or Room directly; they use repositories and the engine.
- The engine depends on abstractions (`ProviderAdapterRegistry`, `AgentTool`), never on UI.
- Tools receive only `ToolContext(chatId)` at execution time; they use injected platform
  services so they are testable and cannot reach the UI except through `UiActionBus`.
- Everything long-running is `suspend`; Room queries run on `Dispatchers.IO`.

## Chat request lifecycle

```
ChatScreen ──send──► ChatViewModel ──persist user message──► ChatRepository (Room)
                           │
                           ▼
                     AgentEngine.run(config, history, approval)  : Flow<AgentEvent>
                           │
                ┌──────────┴───────────┐
                ▼                      ▼
      ProviderAdapter.stream()    ToolRegistry / tools
                │                      │
          SSE → accumulator      approvals + audit log
                │                      │
                └──────────► AgentEvent ◄──────────┘
                           │
                    ChatViewModel reduces events into Room + UI state
```

`AgentEvent` is the single contract between the engine and the UI:

`AssistantTurnStarted`, `TextDelta`, `ReasoningDelta`, `AssistantTurnCompleted`,
`ToolApprovalRequired`, `ToolStarted`, `ToolFinished`, `Completed`, `Failed`.

The ViewModel creates a streaming assistant row, appends deltas, finalizes it with usage
and tool calls, and renders each tool call as a compact step card that can expand into
arguments and the result. Cancelling the view-model scope cancels the SSE call and the
running tool.

### Provider layer

The default adapter speaks the OpenAI Chat Completions wire format:

- `POST {base}/chat/completions`, `GET {base}/models`.
- Base URLs are normalized: a bare host becomes `/v1`; `/chat/completions`, `/completions`
  and `/models` suffixes are stripped; local/LAN hosts default to `http://`, public hosts to
  `https://`. Query strings and embedded credentials are rejected.
- Streaming uses `stream=true` with `stream_options.include_usage`; the SSE parser handles
  chunked frames, comments, multiple `data:` lines, and `[DONE]`.
- Tool calls are reassembled by index; fragmented JSON arguments are concatenated before
  parsing.
- Reasoning/thinking deltas are recognized from `reasoning_content`, `reasoning`,
  `thinking` and `analysis`, so DeepSeek/OpenRouter/vLLM-style providers work.
- Images are sent as `image_url` data URIs; text attachments are embedded in the user
  message with clear `--- Attached document ---` delimiters.
- Every failure is mapped to a `ProviderException` with a `ProviderErrorKind`:
  `AUTH` (401/403), `NOT_FOUND` (404, usually a wrong Base URL), `RATE_LIMIT` (429 +
  `Retry-After`), `BAD_REQUEST` (400/413/422), `SERVER` (5xx), `TIMEOUT`, `NETWORK`, `TLS`,
  `NO_API_KEY`, `EMPTY_RESPONSE`, `PARSE`, `CANCELLED`, `UNKNOWN`.

"Test connection" tries `/models` first; if the server returns 404 but a default model is
set, it falls back to a minimal 4-token chat completion so local servers without `/models`
still work.

### Agent loop

`AgentEngine` is a suspend flow, not a recursive function:

1. Build the tool definitions for enabled tools and prepare context (see below).
2. Stream one assistant turn.
3. If the model returned no tool calls, emit `Completed` (or `Failed` for an empty turn).
4. Otherwise, for each call: resolve tool → parse JSON → check risk → approve if needed →
   execute with a 120 s timeout → audit → append an `<untrusted_tool_output>` tool message.
5. On the last allowed step, tools are withheld from the request. If the model still calls
   tools, they are recorded as `CANCELLED` with a step-limit explanation and the run ends.
6. A denial ends the run immediately and marks remaining calls `CANCELLED`.
7. Unknown or disabled tools receive a synthetic error tool result so the model can correct
   itself without breaking the OpenAI message sequence.

Approval state has three scopes: once, this chat (in-memory `sessionGrants`, cleared when
the chat is cleared or the process dies), and always (persisted in DataStore). `DANGEROUS`
ignores all grants and always prompts.

### Context management

`ContextManager.prepare` estimates tokens (rough character-based estimate, 850 per image),
reserves space for the system prompt, tools and the output budget, then keeps the newest
turn groups that fit in 90 % of the model's context window. A system note tells the model
how many messages were omitted.

Turn grouping keeps `assistant(tool_calls)` and its `tool` replies together, because
splitting them causes HTTP 400 on most providers. `sanitizeToolSequence` also repairs
histories imported from backups: orphan tool messages are dropped and unanswered calls get
a synthetic result.

## Persistence

Room (`AgentPhoneDatabase`) has four tables:

| Table | Contents |
| --- | --- |
| `chats` | Title, timestamps, pin, tag, per-chat provider/model and generation overrides. |
| `messages` | Role, content, reasoning, status, tool-call JSON, artifact metadata, image URIs, token usage, duration, error. `CASCADE` delete from chat; indexed on `chatId` and `(chatId, createdAt)`. |
| `providers` | Name, normalized base URL, **encrypted** API key, default model, context window, input/output prices, header/organization/project options. |
| `tool_call_logs` | Audit trail for every tool execution, indexed by chat and time. |

DataStore (`agentphone_settings`) stores app settings: theme, AMOLED, dynamic color,
language, Persian digits, haptics, streaming, auto-title, max steps, context/output budget,
temperature/top-p, system prompt, enabled tools, always-allow list, TTS, web-search
endpoint/key (encrypted), SAF tree URI, memory notes, MCP settings, and aggregate usage.

Attachments are copied into `files/attachments/<uuid>/`; the workspace is
`files/agent_workspace/`. Both are app-private and excluded from backups.

## UI

- **Navigation** (`Routes`): chats, chat, providers, provider editor, model picker, tools,
  audit, settings, about. Chat is the start destination once onboarding is complete.
- **State**: each screen has a ViewModel exposing an immutable `UiState`; Compose screens
  are stateless functions taking state + callbacks.
- **Theme**: Material 3, dynamic color on Android 12+, curated light/dark fallback palettes,
  and an AMOLED pure-black scheme that takes precedence when enabled.
- **RTL / Persian**: `supportsRtl`, locale config (`en`, `fa`), `AppCompatDelegate`
  locale switching, all icons that imply direction use `Icons.AutoMirrored.*`, and Persian
  digits can be applied to numbers in the UI.
- **Adaptive layout**: `WindowSizeClass` drives a two-pane conversation list/detail layout
  on wide screens.
- **Input**: composer supports text, IME send, attachments (Photo Picker, camera, SAF),
  speech recognition (`SpeechRecognizer`), stop/regenerate/edit/copy/share/delete, and
  read-aloud (TTS).
- **Agent feedback**: step cards for every tool call, a full-screen approval sheet for
  privileged actions, an offline banner, and an audit screen.
- **Background**: long tasks use `AgentBackgroundWorker` with a foreground notification and
  a cancel action. Because no user is present, **all WRITE and DANGEROUS tools are
  auto-denied in background runs**; only READ tools execute.
- **Entry points**: launcher, `ACTION_SEND` (text and images), `agentphone://chat` deep
  link, home-screen widget, and a Quick Settings tile.

## Concurrency

- One `AgentEngine` instance is a singleton; per-chat session grants are held in a
  `ConcurrentHashMap`.
- A chat runs at most one generation at a time (ViewModel guards on `isStreaming`).
- Permission prompts are serialized through `PermissionCoordinator`: requests carry an id,
  wait on a `CompletableDeferred`, and time out after 115 s — below the 120 s tool timeout
  so tools always receive a clean denial rather than being killed mid-prompt.
- Background workers use WorkManager with Hilt's `HiltWorkerFactory`; retries are capped at
  3 and in-flight assistant messages are finalized (error/cancelled) under
  `NonCancellable` so no `STREAMING` row is left behind.

## Security boundaries

See [SECURITY.md](SECURITY.md). In short:

- API keys are encrypted with a non-exportable Android Keystore AES-256-GCM key; the
  ciphertext lives in DataStore/Room, never in logs or backups.
- Tool output and web pages are data, not instructions: they are wrapped in an escaped
  `<untrusted_tool_output>` envelope, screened by a heuristic injection detector, and shown
  to the user with a warning badge.
- `DANGEROUS` tools always require an explicit tap; `dial_number` uses `ACTION_DIAL` and can
  never place a call. All-files access, notification access, usage access and screen control are
  opt-in special access granted by the user; the app opens the matching system page and never
  enables them silently. Raw secure/global settings writes need an adb-granted permission, and
  every write is still a confirmed `DANGEROUS` action.

## Testing strategy

| Test | What it covers |
| --- | --- |
| `AgentEngineTest` | Multi-step loop, approvals, denial, step limit, unknown tools, tool-result wrapping. Uses `FakeProviderAdapter`. |
| `SseParserTest` | Chunk framing, comments, multiple data lines, `[DONE]`, malformed frames. |
| `OpenAiStreamAccumulatorTest` | Text/reasoning concatenation, fragmented tool-call arguments, usage. |
| `UrlNormalizerTest` | Base URL normalization, local host detection, invalid inputs. |
| `MarkdownUiTest` (androidTest) | Compose rendering of Markdown/code blocks. |

`./gradlew :app:testDebugUnitTest` runs the JVM suite; `lintDebug` and `assembleRelease`
(R8 + resource shrinking) are part of the CI workflow.

## Extension points

- **New provider dialect**: implement `ProviderAdapter`, register it in
  `DefaultProviderAdapterRegistry`, and choose `apiFormat` on the provider profile.
- **New tool**: implement `AgentTool` (see the end of [TOOLS.md](TOOLS.md)).
- **New screen**: add a route in `AppNavigation`, a ViewModel, and a stateless screen.
