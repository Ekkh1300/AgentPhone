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
- Keys are never logged. The only `Log` calls in release code are gated behind
  `BuildConfig.DEBUG` and contain chat titles/short previews, never credentials.
- API keys are **not** included in chat export files.
- `android:allowBackup="false"`, and both legacy backup rules and Android 12+ data-extraction
  rules exclude every domain (root, database, shared preferences, files) from cloud backup and
  device transfer. The keystore key is non-exportable anyway, so restored ciphertext would be
  useless.

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
- **Background tasks auto-deny every WRITE and DANGEROUS call** (`background-auto-deny`) because
  no user is present to approve them. Only READ tools run in the background.
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
| `READ_SMS` | runtime (dangerous) | `sms_list`. | Messages tool |
| `SEND_SMS` | runtime (dangerous) | `sms_send`. Always DANGEROUS and confirmed; no background sending. | SMS send tool |
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
Messages are never sent in the background and calls are never placed by the app: `dial_number`
only opens the dialer, `sms_send` is DANGEROUS and runs only in the foreground with your approval,
and `app_install` relies on the system installer and its own confirmation dialog.
Gallery attachments use the system Photo Picker, which grants access to only the picked image and
needs no storage permission.

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
| Model reads messages, call history or contacts / sends an SMS | Reading requires runtime permissions the user grants; `sms_send` is DANGEROUS and always confirmed; no SMS is ever sent in a background run (background runs auto-deny WRITE/DANGEROUS). |
| Model installs an APK | `app_install` is DANGEROUS, requires **Install unknown apps**, opens the system installer, and the system shows its own final confirmation. Only local `.apk` files are accepted. |
| Malicious app invoking exported components | Only `MainActivity` (launcher/share/deep link), the widget receiver and the tile service are exported. The tile service is protected by `BIND_QUICK_SETTINGS_TILE`; the notification listener is `exported="false"` and protected by `BIND_NOTIFICATION_LISTENER_SERVICE`. No exported services or providers process untrusted data. |

## Reporting

This is a template project; replace this section with your security contact before publishing.
Please do not open a public issue for a vulnerability — send a private report with reproduction
steps and the affected version.

## Known Android limitations

These are platform constraints, not bugs; the app surfaces them as clear errors instead of
pretending success:

- Alarms and timers cannot be created silently on modern Android; the system clock UI opens.
- Calls cannot be placed automatically — only the dialer can be opened.
- SMS cannot be sent or read (no permissions requested).
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
