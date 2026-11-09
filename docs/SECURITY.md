# Security and privacy

AgentPhone is a local-first app that talks directly to the AI provider the user chooses. There
is no AgentPhone backend, no analytics SDK, and no telemetry. This document describes what the
app stores, what it sends, how dangerous actions are gated, and what the platform does not allow.

## Secrets

### API keys (provider, web search, MCP)

- Keys are encrypted with **AES-256-GCM** using a non-exportable key created in the **Android
  Keystore** (`SecretCipher`, alias `agentphone_secrets_v1`).
- Ciphertext format is `v1:<base64 iv>:<base64 ciphertext>`, stored in DataStore (web-search and
  MCP keys) and Room (`providers.apiKeyEncrypted`). Plaintext keys are never written to disk.
- Decryption happens only in memory when a request is built. If the Keystore key is invalidated
  (for example after a factory reset), the app reports that the saved key could not be decrypted
  and asks the user to re-enter it; it never falls back to a weaker store.
- `EncryptedSharedPreferences` is intentionally not used because it is deprecated.
- Keys are never logged. Debug `Log` calls contain chat titles/short previews, never credentials.
  The away-mode worker logs only an exception class name on failure, and TDLib's own logging is
  disabled; message text, auth codes and provider output never reach logcat.
- API keys are **not** included in chat export files.
- `android:allowBackup="false"`, and both legacy backup rules and Android 12+ data-extraction
  rules exclude every domain (root, database, shared preferences, files) from cloud backup and
  device transfer. The keystore key is non-exportable anyway, so restored ciphertext would be
  useless.

### Telegram credentials and TDLib database

- The Telegram `api_id` and phone number are stored in DataStore; the `api_hash` is encrypted with
  the same `SecretCipher` (AES-256-GCM, non-exportable Keystore key).
- The 32-byte TDLib database key is generated with `SecureRandom` on first use, stored only as
  `SecretCipher` ciphertext, and handed to TDLib raw in memory. TDLib encrypts its SQLite database
  with it.
- The TDLib folder (`files/tdlib/db` and `files/tdlib/files`) is app-private and covered by the
  same “exclude every domain” backup and device-transfer rules as the rest of the app.
- TDLib's own logging is disabled with `Client.setLogMessageHandler(0, null)`; its log lines can
  contain message text. Login codes and 2FA passwords exist only in memory and are sent directly
  to TDLib.
- **Wipe local data** signs out, deletes the encrypted credential blob, the TDLib database key and
  the TDLib folder, and clears away-mode rules and decisions.

### Provider requests

- Requests go directly from the device to the user's Base URL with the decrypted key in the
  `Authorization: Bearer` header (or custom headers if configured).
- TLS uses the system trust store. There is no certificate pinning because users may run
  self-hosted or LAN servers with self-signed certificates — pinning would break those setups.
- Cleartext `http://` is allowed in the network-security config because local LLM servers often
  have no TLS. The app accepts `http://` exactly like `https://`, with no blocking dialog and no
  warning banner, so local model servers work out of the box. Traffic is unencrypted, so prefer
  HTTPS or a trusted LAN when an API key is involved.
- The app does not send prompts, keys or usage data anywhere except the configured provider
  (and the configured web-search endpoint, or an MCP server when the user enables it).

## Prompt injection and untrusted content

The model is an untrusted planner and every tool output is untrusted data:

1. The default system prompt states that tool output and web pages are data, never instructions,
   and forbids destructive actions without a clear user request.
2. Every tool result is wrapped before it is sent back to the model:

   ```
   <untrusted_tool_output tool="web_fetch">
   ...content...
   </untrusted_tool_output>
   ```

   Any embedded `</untrusted_tool_output>` or `<untrusted_tool_output` text is HTML-escaped so
   content cannot break out of the envelope. Output is capped at 24,000 characters.
3. `PromptInjectionDetector` screens results for common override patterns ("ignore previous
   instructions", "new system prompt", "reveal the API key", "call tools without asking", hidden
   HTML comments, and similar). A match sets a flag on the audit entry and shows a warning on the
   tool step card. It is an annotation, **not** a filter: content is still shown to the model, so
   the user can see and judge it.
4. Approval is the real enforcement layer. A model that is fully hijacked still cannot run a
   WRITE action without the user's tap, and cannot run a DANGEROUS action at all without an
   explicit confirmation. This includes full-storage deletion, app uninstall, radio toggles and
   raw system-settings writes.
5. Tool output can influence later turns, but it cannot add permissions, disable approvals, or
   change the always-allow list. Those live in code and local settings only.

## Approval model

| Risk | Shown to model | Execution |
| --- | --- | --- |
| READ | Yes | Automatic. |
| WRITE | Yes | Requires approval unless the user chose **Always allow** for that tool. Chat-scoped grants last only for the current process/chat. |
| DANGEROUS | Yes | **Always** requires confirmation. "Always allow" is rejected for these tools and session grants are ignored. |

- Denying an action immediately stops the run; the model is told not to retry, and the remaining
  tool calls in that turn are cancelled.
- **Background agent tasks auto-deny every WRITE and DANGEROUS call** (`background-auto-deny`)
  because no user is present to approve them. Only READ tools run in that loop. The separate
  opt-in auto-reply subsystem described below is the only path that sends anything without a
  per-message prompt.
- Arguments are shown verbatim in the approval sheet, and the tool description explains the
  effect. There is no "remember this for all future chats" for dangerous capabilities.
- `dial_number` uses `Intent.ACTION_DIAL`; it can only pre-fill the dialer and can never place a
  call. `alarm_set` and `timer_set` open the system clock UI with values pre-filled and never
  create an alarm silently.
- `app_uninstall` uses `Intent.ACTION_DELETE`, so the system confirmation dialog is always the
  final gate; the app cannot remove a package silently.
- File tools are confined to the app workspace, the folder the user picked through the Storage
  Access Framework, or — only with the user-granted **All files access** — the whole shared
  storage. Absolute paths and `..` escapes that leave a known volume are rejected. The optional
  all-files mode is off until the user turns it on in Android settings; there is no silent
  escalation and no fallback that widens scope.

## Automatic SMS replies (opt-in)

This is the only feature that can send a message without a per-message confirmation. It is a
separate subsystem, not an agent tool, and it has its own safeguards:

- **Off by default.** Enabling it requires an “I understand what this will do” confirmation in
  Settings plus the `RECEIVE_SMS` and `SEND_SMS` runtime permissions. Turning it off immediately
  cancels every pending reply.
- **Only a plain SMS reply.** The composer is called with no tools at all, so it cannot read
  files, change settings or call anything else.
- **Never a silent trigger from the model.** The agent's chat loop cannot enable it, change its
  instructions or send through it.
- **Won't answer:** shortcodes and alphanumeric senders (banks, operators, services), messages
  containing verification/one-time codes, messages that match the prompt-injection detector,
  the phone's own number, and any thread where the user already sent an SMS after the incoming
  message.
- **Rate limited:** a configurable maximum number of automatic replies per local day (default 5),
  and only one pending reply per address — a newer message resets the timer instead of stacking.
- **Visible and auditable:** every attempt writes a local Room row and an `auto_reply` entry in
  the audit log (`DANGEROUS`, approval `auto-reply-enabled`), and sends result/skip notifications.
  The history screen lists the incoming text, the reply and the reason for every skip.
- **Untrusted input:** the SMS body and transcript are wrapped as untrusted data, the injection
  detector is checked first, and the prompt explicitly forbids following instructions found in
  messages.
- **Opt-out persistence:** the pending timer lives in WorkManager and the database, so a reboot
  does not lose it; disabling the feature cancels it.

## Telegram away mode (opt-in)

This sends messages from the user's **own Telegram account** through TDLib. It is off by default,
requires the user's own api_id/api_hash and a successful login, and is a separate subsystem that
the agent loop cannot invoke.

- **Default deny allow-list.** No chat is answered until the user adds it as `Auto` or `Draft`.
  `Never` blocks a chat; removing a rule removes the permission. Channels, bots and secret chats
  are always refused.
- **Groups:** addressed only (mention, reply to the owner or a configured nickname), text only,
  threaded, short/neutral, half the per-chat cap, at least a 60-minute cooldown, disclosure on by
  default. A separate per-chat switch can forbid sending that group's text to the AI.
- **Hard escalation without sending:** OTP/verification codes, bank/card/payment data,
  credentials, personal-data requests, legal and admin/moderation topics are escalated before or
  after the model call and never auto-sent. The model also has no tools and is instructed never to
  agree to anything, reveal data or claim to be human.
- **Prompt injection:** incoming text is delimited and escaped; a detector match escalates the
  batch, adds the chat to the watchlist and marks the audit entry. Incoming text is never used as
  a system instruction.
- **Strict, validated output:** one JSON object, validated (allowed enums, numeric confidence in
  range, reply length, risks array). Invalid output is retried once, then escalated. A provider
  failure means **no send** and increments an error streak; three consecutive failures disable
  away mode.
- **No duplicates, no loops:** a sent/draft/escalated decision for the trigger message cancels a
  replayed job, a newer incoming message supersedes the batch, outgoing messages cancel pending
  replies, and read messages are not answered.
- **Caps:** per-chat and global daily caps plus a per-chat cooldown (stricter in groups), and
  Telegram's own slow mode is honoured. Limits are checked at fire time and logged with a reason.
- **Transparency:** automated replies get a disclosure suffix (default on; mandatory-looking in
  groups unless disabled per chat). Every decision is stored locally and shown in the log with
  correct/undo/never actions; drafts and escalations are notified and actionable.
- **Kill switches:** settings switch, Quick Settings tile and the notification pause action
  disable the feature, cancel every timer and stop the foreground service immediately.

See [TELEGRAM.md](TELEGRAM.md) for onboarding, storage, ban risk and limitations.

## Audit log

Every tool run writes a local Room row: tool name, arguments, result preview, risk, status
(`SUCCESS`/`ERROR`/`DENIED`/`CANCELLED`), start time, duration, approval mode, and whether the
injection detector matched. The **Audit log** screen shows this history and lets the user clear
it. Audit data never leaves the device and is not part of chat export.

## Network safety for web tools

- `web_fetch` accepts only `http(s)`. It refuses `localhost`, `.local`, loopback, link-local,
  site-local and private addresses unless the user enables **Allow private web addresses** in
  Settings.
- Redirects are followed manually (maximum 4), and each hop is re-validated, so a public URL
  cannot redirect into the LAN.
- Page size is capped at 1.5 MB and extracted text at 24,000 characters. JavaScript is not
  executed; only server-rendered HTML/text is read.
- Known limitation: DNS can resolve differently between validation and the actual request
  (DNS-rebinding). The private-address check raises the bar but is not a substitute for a network
  firewall; running an untrusted model against hostile sites should still be treated cautiously.
- `web_search` sends the query and key to the user-configured Tavily-compatible endpoint over
  HTTPS (or HTTP if the user configures it that way).

## Permissions and why they exist

Runtime permissions are requested **lazily**, only when a tool or UI action actually needs them.
Special app access is never requested automatically; the app opens the matching system page and
the user decides.

| Permission | Type | Why | Requested when |
| --- | --- | --- | --- |
| `INTERNET` | normal | Provider API, web tools, MCP. | Install time |
| `ACCESS_NETWORK_STATE` | normal | Offline banner and connectivity checks. | Install time |
| `ACCESS_WIFI_STATE` | normal | Wi-Fi on/off state in `device_info` and `quick_toggle`. | Install time |
| `VIBRATE` | normal | Haptic feedback (can be disabled in Settings). | Install time |
| `WAKE_LOCK` | normal | WorkManager keeps long background runs alive. | Install time |
| `POST_NOTIFICATIONS` | runtime (33+) | Reminders, agent completion, background progress. | First reminder/notification/background run |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | normal | Background agent runs as a data-sync foreground worker with a cancellable notification. | Install time |
| `CAMERA` | runtime (dangerous) | In-chat camera attachment and the `camera_capture` tool. Also required by `CameraManager` for the torch. | Camera button or flashlight tool |
| `RECORD_AUDIO` | runtime (dangerous) | Speech-to-text input and the `audio_record` tool. Speech recognition is performed by the on-device/system recognizer; audio recordings are saved only in the app workspace when you ask for them. | Microphone button or `audio_record` |
| `ACCESS_COARSE_LOCATION` | runtime (dangerous) | `location_current` default precision. | Location tool |
| `ACCESS_FINE_LOCATION` | runtime (dangerous) | `location_current` with `precision=fine`; Wi-Fi scans and SSID names. | Location tool, `wifi_scan` |
| `READ_CALENDAR` | runtime (dangerous) | Listing upcoming events; also needed to update or delete events. | Calendar tools |
| `WRITE_CALENDAR` | runtime (dangerous) | `calendar_create`, `calendar_update`, `calendar_delete`. | Calendar write tools |
| `READ_CONTACTS` | runtime (dangerous) | `contacts_search`. | Contacts tool |
| `WRITE_CONTACTS` | runtime (dangerous) | `contact_create` (atomic provider batch). | Contact create tool |
| `READ_SMS` | runtime (dangerous) | `sms_list`; the opt-in auto-reply thread reader. | Messages tool, auto-reply |
| `RECEIVE_SMS` | runtime (dangerous) | Starts the opt-in auto-reply timer when an SMS arrives, even while the app is closed. The receiver stores the sender/text locally and schedules work; it never replies by itself. | Enabling auto-reply |
| `SEND_SMS` | runtime (dangerous) | `sms_send` (always DANGEROUS and confirmed), and the opt-in auto-reply worker. The model cannot use the auto-reply path; it is driven by the incoming message, the timer and the user's explicit feature opt-in. | SMS send tool, enabling auto-reply |
| `READ_CALL_LOG` | runtime (dangerous) | `call_log`. | Call history tool |
| `READ_PHONE_STATE` | runtime (dangerous) | `sim_info` SIM/carrier details. | SIM info tool |
| `READ_PHONE_NUMBERS` | runtime (dangerous) | Optional phone-number display in `sim_info`. | SIM info tool |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | runtime (dangerous, 33+) | `wifi_scan` on Android 13+. | Wi-Fi scan tool |
| `SET_WALLPAPER` | normal | `wallpaper_set`. | Install time |
| `REQUEST_INSTALL_PACKAGES` | special app access | `app_install` opens the system installer. The user must also allow **Install unknown apps** and confirm each install in the system dialog. | Install time declaration; access granted on the system page |
| `BLUETOOTH_CONNECT` | runtime (dangerous, 31+) | Reading Bluetooth state and verifying a Bluetooth toggle requested with `quick_toggle`. Legacy `BLUETOOTH` is declared with `maxSdkVersion=30`. | `quick_toggle target=bluetooth` |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | runtime, legacy | Reading shared storage on Android 10–12L, where All files access does not cover reads. Not requested on Android 13+. | File tool with `location="all"` |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`) | runtime, legacy | MediaStore download fallback and all-files writes on Android 8–9 only. Not requested on Android 10+. | `files_save_download` / `location="all"` on API ≤ 29 |
| `MANAGE_EXTERNAL_STORAGE` | special app access (API 30+) | `location="all"` on the file tools (list, read, write, search the whole shared storage). | Only when the user opens **All files access** from Advanced access, or a file tool reports it is missing |
| `QUERY_ALL_PACKAGES` | normal (special-store review) | `apps_list`, `app_info`, `open_app` and label resolution across all installed apps instead of the default `<queries>` subset. | Install time |
| `PACKAGE_USAGE_STATS` | special app access (appop) | `apps_usage` foreground-time report. | Only when the user opens **Usage access** |
| `WRITE_SETTINGS` | special app access (appop) | `brightness_set`, `display_control`, and `system_settings` for the `system` namespace. Checked with `Settings.System.canWrite` before every write. | Special access page; checked before every write |
| `WRITE_SECURE_SETTINGS` | signature permission, adb only | Writing `secure`/`global` keys with `system_settings`. Ordinary installs never receive it; the user must run the adb command shown by `action=grant_info`. | Never prompted; optional manual adb grant |

There is **no** `SYSTEM_ALERT_WINDOW`, `CALL_PHONE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`,
`READ_MEDIA_AUDIO`, `BODY_SENSORS`, `ACCESS_BACKGROUND_LOCATION` or `MODIFY_PHONE_STATE`.
The agent tool loop never sends messages in the background and never places calls: `dial_number`
only opens the dialer, `sms_send` is DANGEROUS and runs only in the foreground with your approval,
and `app_install` relies on the system installer and its own confirmation dialog. The opt-in
auto-reply subsystem is the sole automatic sender; it can only be enabled with an explicit
warning acknowledgement plus SMS permissions, never exposes itself as a model tool, and records
every send. Gallery attachments use the system Photo Picker, which grants access to only the
picked image and needs no storage permission.

### Special app access (not runtime permissions)

| Access | Why | How it is requested |
| --- | --- | --- |
| All files access (`MANAGE_EXTERNAL_STORAGE`) | `location="all"`: whole-storage listing, reading, writing, search. | Android **All files access** page, from **Tools & permissions → Advanced access** or a tool error. |
| Notification listener (`BIND_NOTIFICATION_LISTENER_SERVICE`) | `notifications_list`, `notification_action` (dismiss/open). | Android **Notification access** page. The service is `exported="false"` and only active after the user enables it. |
| Usage access (`PACKAGE_USAGE_STATS`) | `apps_usage`. | Android **Usage access** page. |
| Accessibility service (`:accessibility` module, `BIND_ACCESSIBILITY_SERVICE`) | `screen_snapshot` and `screen_action` (off by default), including global actions (shade, quick settings, lock, power menu, split screen, screenshot). Also drives Quick Settings automation for `airplane_mode` and `quick_toggle`. | The tool opens Android Accessibility settings; the user must enable it manually. |
| Modify system settings | `brightness_set`, `display_control`, `system_settings` (`system` namespace). | Tool opens the write-settings page; tool returns an error until granted. |
| Do Not Disturb access | `dnd_set`; silent `ringer_mode`. | Tool opens the DND access page. |
| Install unknown apps | `app_install`. The system still shows its own install confirmation for every APK. | Android **Install unknown apps** page for AgentPhone, from Advanced access or automatically when the tool is called without the grant. |
| Overlay | **Not used.** | — |

The accessibility service is deliberately isolated in its own module. It has its own
`accessibility_service_config` and only becomes active after the user turns it on. It cannot read
secure (`FLAG_SECURE`) windows, and the app caps snapshots at 220–300 nodes with 300–500
characters per node.

The notification listener reads notification titles and text for the tools only. It is not used
for analytics, does not store notification content in the database, and stops working the moment
the user revokes notification access. Usage access is queried on demand for `apps_usage`; the raw
usage history is not persisted.

`WRITE_SECURE_SETTINGS` is the most privileged capability in the app. It exists because the user
asked for full agent control; it can only be granted through adb, every write is DANGEROUS and
audited, and the app never requests it on its own.

## What leaves the device

| Destination | Data | Trigger |
| --- | --- | --- |
| The configured provider | System prompt, chat history (truncated to context), attachments you added, tool results, tool schemas | Every message |
| The configured provider (auto-reply) | The incoming SMS, the last few messages of that thread, the contact name, your auto-reply instructions and long-term memory notes | Only when auto-reply is enabled and the timer fires |
| The configured provider (Telegram away mode) | The triggering message, the last 14 messages of **that chat only**, sender name and “in contacts” flag, your persona, long-term memory notes and calendar free/busy text | Only when away mode is on, that chat is in the allow-list, the debounce timer fires, and no local rule escalates first |
| The configured web-search endpoint | The search query and API key | `web_search` |
| The configured MCP server | Tool name/arguments and the MCP key | `mcp_list_tools` / `mcp_call_tool` |
| URLs you ask the model to fetch | Request to that site (your IP is visible to it) | `web_fetch` / `open_url` |
| Nothing else | — | No analytics, crash reporting or ads |

The provider sees everything sent to it, including tool results that may contain file contents,
notifications, installed apps, contacts, calendar events or location. The model provider's own
retention policy applies. The app shows permission rationales that state this before reading
calendar/contacts/location, and the Advanced access card explains what each grant unlocks.

## Threat model summary

| Threat | Mitigation |
| --- | --- |
| Stolen/lost unlocked device | Keystore key is non-exportable; keys are not in backups; no app-level PIN (rely on device lock). All-files access does not expose the app's encrypted key store. |
| Malicious web page or file instructing the model | Untrusted envelope, injection detection, visible warning, approval gate, never-auto DANGEROUS. |
| Malicious MCP server | User-configured, every call is DANGEROUS and confirmed, output is wrapped as untrusted. |
| Prompt requests to exfiltrate a key | The key is never placed in the prompt; only the provider adapter sees it. File and web tools cannot read the app's private DataStore/Room files through the agent tools. |
| Fake provider / wrong Base URL | Connection test, human-readable 401/404/429/TLS errors. |
| Backup or ADB backup extraction | Backups disabled; data-extraction rules exclude all domains. |
| Model deletes files or changes system settings | Recursive delete/move and screen actions are DANGEROUS; airplane, ringer, DND, radio toggles and raw system-settings writes are DANGEROUS and always confirmed; brightness/display require special access; file access is limited to workspace/SAF unless the user explicitly grants all files. |
| Model uninstalls an app | `app_uninstall` is DANGEROUS and uses `ACTION_DELETE`; the system confirmation is always shown. |
| Model reads private notifications or app usage | Both require explicit special access the user grants in system settings; results are read-only tools, never persisted. |
| Model reads messages, call history or contacts / sends an SMS | Reading requires runtime permissions the user grants; `sms_send` is DANGEROUS and always confirmed; the background agent loop auto-denies it. Automatic replies are a separate opt-in feature that only sends a plain SMS after the user-configured delay, never exposes itself as a tool, and logs every send. |
| Prompt injection inside an incoming SMS | The auto-reply composer wraps the text as untrusted data, runs the injection detector first, skips matches, offers no tools, and instructs the model to ignore directives inside messages. |
| Prompt injection inside an incoming Telegram message | `AwaySafety`/`PromptInjectionDetector` delimit and escape the text, a match escalates the batch without sending, the chat is added to the watchlist, and the audit entry is marked. The responder has no tools. |
| Telegram automation causing account limits or a ban | Off by default, default-deny allow-list, low per-chat/global caps, cooldown, draft-only for unknown senders and links/attachments, no bulk/outreach use, flood waits respected; the risk and the user's responsibility are documented in TELEGRAM.md. |
| Telegram session data on a lost device | The TDLib database is encrypted with a Keystore-protected key; the api_hash is encrypted; the folder is excluded from backups. As with any logged-in app, an unlocked device can still read messages. |
| Model installs an APK | `app_install` is DANGEROUS, requires **Install unknown apps**, opens the system installer, and the system shows its own final confirmation. Only local `.apk` files are accepted. |
| Malicious app invoking exported components | Only `MainActivity` (launcher/share/deep link), the widget receiver, the tile service and the SMS receiver are exported. The tile service is protected by `BIND_QUICK_SETTINGS_TILE`; the SMS receiver requires `BROADCAST_SMS` so only the system can invoke it; the notification listener is `exported="false"` and protected by `BIND_NOTIFICATION_LISTENER_SERVICE`. No exported services or providers process untrusted data. |

## Reporting

This is a template project; replace this section with your security contact before publishing.
Please do not open a public issue for a vulnerability — send a private report with reproduction
steps and the affected version.

## Known Android limitations

These are platform constraints, not bugs; the app surfaces them as clear errors instead of
pretending success:

- Alarms and timers cannot be created silently on modern Android; the system clock UI opens.
- Calls cannot be placed automatically — only the dialer can be opened.
- SMS can be read, sent and received only after the user grants the SMS runtime permissions; the
  auto-reply feature requires its own explicit opt-in. RCS and messaging apps are not covered.
- WorkManager delays are inexact under Doze and battery optimization, so an auto-reply may go out
  later than the configured delay; the app never claims an exact send time.
- Wi-Fi, Bluetooth, mobile data, hotspot and NFC cannot be toggled through public APIs on
  Android 10+. `quick_toggle` automates the Quick Settings tile (and the Internet panel for
  Wi-Fi/mobile data) when the accessibility service is enabled; otherwise it reports the
  observed state and opens the matching system panel. NFC remains read-only.
- DND, brightness and display control require special access that only the user can grant in
  system settings.
- Secure and global settings writes require `WRITE_SECURE_SETTINGS`, which only adb can grant.
- Airplane mode cannot be changed directly on Android 10+; `airplane_mode` taps the Quick
  Settings tile through the accessibility service and otherwise opens the system page.
- Silent ringer requires Do Not Disturb access.
- Accessibility nodes are unavailable in secure windows and in apps that disable accessibility.
- Notification content is only available while notification access is granted; ongoing and
  system notifications may be hidden by the OS.
- Usage stats are only available while usage access is granted, and some devices throttle the
  history.
- Clipboard reads require the app to be in the foreground.
- Background agent runs cannot prompt for approval, so all non-READ tools are denied there.
- PDF attachments are rendered to images (first 3 pages) rather than text-extracted; scanned or
  image-only PDFs depend on the model's vision capability.
- Shared storage, the SAF folder and app-private storage are the only file locations. All files
  access covers shared storage, not other apps' private data directories.
- `open_app` is subject to Android 11+ package visibility; `QUERY_ALL_PACKAGES` is declared, so
  the agent can see installed apps, but launching still respects each app's exported activities.
- Providers that require exotic request shapes, non-OpenAI streaming formats or proprietary auth
  beyond bearer/custom headers are not supported by the default adapter.
