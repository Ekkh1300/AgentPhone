# AgentPhone tool reference

AgentPhone exposes 74 on-device tools to any OpenAI-compatible model through standard
`tools` / `tool_calls` messages. Every tool is defined in
`app/src/main/java/com/agentphone/app/tools/` and registered automatically by Hilt into
[`ToolRegistry`](../app/src/main/java/com/agentphone/app/agent/ToolRegistry.kt).

## How a tool call is executed

1. The model returns one or more `tool_calls` with JSON arguments.
2. `AgentEngine` looks the tool up. Tools disabled in **Settings → Tools & permissions**
   are never shown to the model; if a model still calls one, the call is refused and
   recorded in the audit log.
3. Arguments are parsed as JSON. The schema in the `tools` array is the source of truth;
   tool code additionally validates ranges and re-checks permissions.
4. Risk decides whether the user is asked:

   | Risk | Approval |
   | --- | --- |
   | `READ` | Runs automatically. |
   | `WRITE` | Asks once. The user can choose **Allow once**, **Allow for this chat**, **Always allow**, or **Deny**. |
   | `DANGEROUS` | Always asks, every time. "Always allow" cannot be set from the dialog, and a `DANGEROUS` tool is never granted for the session. |

   Denying a call stops the run, feeds the denial back to the model, and skips the
   remaining tool calls of that turn.
5. The tool runs with a 120-second timeout. Cancelling the chat cancels the coroutine.
6. The result is recorded in the audit log (tool, arguments, result, status, duration,
   approval mode, injection-warning flag) and wrapped in an `<untrusted_tool_output>`
   envelope before it is sent back to the model. The wrapper is escaped so content cannot
   close the envelope early.
7. At most 8 tool steps run per user message by default (configurable). On the final turn
   tools are withheld so the model must answer with the information it already has.

`Skill`-style tools such as `screen_snapshot`, `screen_action` and `mcp_call_tool` are
disabled by default and must be enabled explicitly. `dnd_set`, `airplane_mode`, `quick_toggle`,
`system_settings` and `ringer_mode` ship enabled but remain `DANGEROUS`: they always ask
before running.

## Permissions and special access

Most tools work with no setup. A few need the user to grant access once; the tool opens the
right system page and returns a clear error until the grant is in place. Everything is
listed on **Tools & permissions → Advanced access**.

| Capability | What it unlocks | How it is granted |
| --- | --- | --- |
| All files access (`MANAGE_EXTERNAL_STORAGE`, API 30+) | `location="all"` on the file tools: list, read, write, search the whole shared storage. | Android **All files access** page. On API ≤ 29 the app uses `READ/WRITE_EXTERNAL_STORAGE` instead. |
| Notification listener | `notifications_list`, `notification_action`. | Android **Notification access** page. |
| Usage access (`PACKAGE_USAGE_STATS`) | `apps_usage`. | Android **Usage access** page. |
| Screen control (accessibility service) | `screen_snapshot`, `screen_action`, Quick Settings automation used by `airplane_mode` and `quick_toggle`. | Android **Accessibility** settings. |
| Modify system settings (`WRITE_SETTINGS`) | `brightness_set`, `display_control`. | Android **Modify system settings** page. |
| Do Not Disturb access | `dnd_set`, silent `ringer_mode`. | Android **Do Not Disturb access** page. |
| `WRITE_SECURE_SETTINGS` | Writing `secure`/`global` keys with `system_settings`. | adb only: `adb shell pm grant com.agentphone.app.debug android.permission.WRITE_SECURE_SETTINGS`. The app shows a copyable command via `system_settings action=grant_info`. |
| Install unknown apps | `app_install` (system package installer). | Android **Install unknown apps** page for AgentPhone. The app also opens it automatically when the grant is missing. |

Runtime permissions are requested the first time a tool needs them and can be revoked in Android settings:

| Permission | Used by |
| --- | --- |
| `READ_SMS` | `sms_list` |
| `SEND_SMS` | `sms_send` |
| `READ_CALL_LOG` | `call_log` |
| `READ_CONTACTS` | `contacts_search` |
| `WRITE_CONTACTS` | `contact_create` |
| `READ_CALENDAR` / `WRITE_CALENDAR` | `calendar_list`, `calendar_create`, `calendar_update`, `calendar_delete` |
| `CAMERA` | `camera_capture`, `flashlight` |
| `RECORD_AUDIO` | `audio_record` |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | `location_current`, `wifi_scan`, Wi-Fi name in `network_info` |
| `NEARBY_WIFI_DEVICES` (API 33+) | `wifi_scan` |
| `READ_PHONE_STATE` | `sim_info` |
| `READ_PHONE_NUMBERS` (optional) | Phone number display in `sim_info` |
| `BLUETOOTH_CONNECT` (API 31+) | `bluetooth_devices`, `quick_toggle target=bluetooth` |
| `POST_NOTIFICATIONS` (API 33+) | `notification_post`, `reminder_schedule` |

`SET_WALLPAPER` and `VIBRATE` are normal permissions granted at install time.

## Filesystem locations

File tools accept a `location` argument:

| Value | Meaning |
| --- | --- |
| `workspace` (default) | App-private `files/agent_workspace` directory. Created on demand. Path traversal outside it is rejected. |
| `shared` | The folder the user picked with the Storage Access Framework (SAF) tree. Requires the user to have granted persistent access in **Settings → Storage folder**. |
| `all` | Full shared storage (`/storage/emulated/0` plus any SD card / USB volume). Requires **All files access**; relative paths resolve against the primary volume, and absolute paths must stay inside a known volume. Hidden files are skipped by listing and search. |

All paths are relative for `workspace` and `all`. Absolute paths, `..` escapes and symlink
escapes are rejected. `files_save_download` is the only tool that writes outside these
locations: it uses MediaStore `Downloads/AgentPhone` (with a legacy-storage fallback on
Android 9 and below).

---

## Files (`FILES`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `files_roots` | READ | List phone storage volumes with free/total space, the agent workspace and the connected shared folder. | none |
| `files_list` | READ | List a folder, optionally recursive (max depth 6). | `location` (enum), `path`, `recursive` |
| `files_read` | READ | Read a UTF-8 text file (max 1 MB). | `location`, `path` **(required)** |
| `files_write` | WRITE | Create or overwrite a UTF-8 text file. Supports md/txt/csv/json/html/code. | `location`, `path`**, `content`** |
| `files_append` | WRITE | Append text, creating the file if needed. | `location`, `path`**, `content`** |
| `files_edit` | WRITE | Replace exact text and report the number of replacements. | `location`, `path`**, `find`**, `replace`** |
| `files_copy` | WRITE | Copy a file or folder without deleting the original. | `location`, `from`**, `to`**, `overwrite` |
| `files_mkdir` | WRITE | Create a folder (parents allowed). | `location`, `path`** |
| `files_delete` | DANGEROUS | Delete a file or folder; folders are removed recursively. | `location`, `path`** |
| `files_move` | DANGEROUS | Move or rename a file/folder inside the same location. | `location`, `from`**, `to`** |
| `files_search` | READ | Find files by name and/or text content. Returns up to 200 hits; workspace/SAF scans stop after 600 entries, while `location="all"` walks are bounded only by `max_depth` and `limit`. | `location`, `path`, `name`, `content`, `max_depth` (1–12), `limit` (1–200) |
| `files_info` | READ | Size, type, modification time and permissions for a file or folder. | `location`, `path`** |
| `files_export_pdf` | WRITE | Render text/Markdown to a PDF in `workspace/Exports`. | `title`**, `content`** |
| `files_save_download` | WRITE | Save text to public `Downloads/AgentPhone`; returns a shareable URI. | `filename`**, `content`**, `mime_type` |
| `file_open` | WRITE | Open a file with a compatible app through FileProvider/SAF with a temporary read grant. | `location`, `path`**, `mime_type` |

`**` marks a required argument.

Reading and writing `location="all"` returns an error that points at the All files access
page when the permission is missing, instead of silently falling back to the workspace.
`files_copy` works inside one location: copy between locations by reading and writing, or
copy into `shared` with two separate calls.

---

## Device (`DEVICE`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `device_info` | READ | Battery, radios (airplane/Wi-Fi/Bluetooth/mobile data), sound (ringer, media volume, DND), display (rotation, timeout, adaptive brightness), network, storage and OS details. | none |
| `location_current` | READ | One-shot location fix with accuracy, provider, timestamp, altitude and speed. Requests `ACCESS_COARSE_LOCATION`, or fine location when `precision=fine`. | `precision` (`coarse`/`fine`) |
| `clipboard_read` | WRITE | Read clipboard text. Android only allows this while the app is in the foreground. | none |
| `clipboard_write` | WRITE | Copy text to the clipboard. | `text`** |
| `memory_read` | READ | Read long-term notes the user saved. Notes are labelled as reference data, not instructions. | none |
| `memory_write` | WRITE | Append or replace long-term notes (max 12,000 chars per call). The model is told never to store secrets. | `content`**, `mode` (`append`/`replace`) |
| `flashlight` | WRITE | Toggle the camera torch. Needs `CAMERA`; fails gracefully when no flash exists. | `on`** (boolean) |
| `vibrate` | WRITE | Vibrate once for 1–10000 ms (default 500). | `milliseconds` (1–10000) |
| `sensor_read` | READ | Read one sample from light, proximity, accelerometer, gyroscope, magnetic field, pressure, humidity or ambient temperature. | `sensor`** (enum) |
| `bluetooth_devices` | READ | List paired Bluetooth devices with name, address and bond state. Requires `BLUETOOTH_CONNECT` on Android 12+. | none |
| `sim_info` | READ | SIM state, carrier, network operator, country, roaming, phone type, active SIMs and phone number when permitted. Requires `READ_PHONE_STATE`. | none |

---

## Communication and intents (`COMMUNICATION`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `calendar_list` | READ | List upcoming events (default 30 days, max 50). Requires `READ_CALENDAR` at call time. | `days` (1–90), `limit` (1–50) |
| `calendar_create` | WRITE | Insert an event into the first writable calendar. Requires `READ_CALENDAR` + `WRITE_CALENDAR`. | `title`**, `start`**, `end`** (ISO local), `description`, `location` |
| `calendar_update` | WRITE | Change the title, times, description or location of an event by id. Verifies the resulting end time is after the start. | `event_id`**, `title`, `start`, `end`, `description`, `location` |
| `calendar_delete` | DANGEROUS | Delete an event by id. Always confirmed; cannot be undone. | `event_id`** |
| `contacts_search` | READ | Search contacts by name/number; returns at most 20. Requires `READ_CONTACTS`. | `query`** |
| `contact_create` | WRITE | Add a contact with a name and at least one phone number or email (atomic provider batch). Requires `WRITE_CONTACTS`. | `name`**, `phone`, `email` |
| `sms_list` | READ | List recent inbox/sent messages, optionally filtered by number. Requires `READ_SMS`. | `address`, `limit` (1–50) |
| `sms_send` | DANGEROUS | Send an SMS (auto-splits long messages). Requires `SEND_SMS`; always confirmed. | `number`**, `text`** (≤1600) |
| `call_log` | READ | List recent calls with type, duration and time. Requires `READ_CALL_LOG`. | `number`, `limit` (1–50) |
| `email_compose` | READ | Open the email app with recipient, subject and body pre-filled. The user must press send. | `to`, `subject`, `body` |
| `share_text` | WRITE | Open the Android share sheet with text. | `text`**, `title` |
| `open_url` | WRITE | Open an `http(s)` URL in the browser. Other schemes are rejected. | `url`** |
| `open_maps` | WRITE | Open a maps app for an address, place or `lat,lng`. | `query`** |
| `dial_number` | WRITE | Open the dialer with a number pre-filled using `ACTION_DIAL`. **Never places a call.** | `number`** |
| `open_app` | WRITE | Launch an app by package name or label. Ambiguous labels return a disambiguation error. | `query`** |
| `open_settings` | WRITE | Open 34 Android settings destinations, including Wi-Fi, internet, Bluetooth, airplane, hotspot, NFC, sound, display, night display, auto-rotate, battery saver, apps, security, developer options, date, language, keyboard, storage, usage access, notification listener, default apps, VPN, cast, home and print. Wi-Fi, internet, NFC and volume use floating system panels on Android 10+. | `page`** (enum) |

---

## Notifications and apps (`NOTIFICATIONS`, `APPS`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `notifications_list` | READ | List recent notifications with app label, title, text, time and key. Falls back to `NotificationManager.activeNotifications` when the listener is off. | `limit` (1–60) |
| `notification_action` | WRITE | Dismiss one notification by key, dismiss all, or open a notification. Requires the notification listener. | `action`** (`dismiss`/`dismiss_all`/`open`), `key` |
| `apps_list` | READ | Search installed apps by name or package; optionally include system apps or ignore launchable-only. | `query`, `include_system`, `only_launchable`, `limit` (1–300) |
| `apps_usage` | READ | Most-used apps for the last day with foreground time. Requires usage access. | `limit` (1–50) |
| `app_info` | READ | Version, install/update time, SDK levels, requested permissions, enabled/system state and APK path. | `package`** |
| `app_uninstall` | DANGEROUS | Open the system uninstall confirmation for an app. Refuses AgentPhone itself. | `package`** |

`app_uninstall` uses `Intent.ACTION_DELETE`, so the system confirmation dialog is always shown;
the app cannot remove a package silently.

---

## Media (`MEDIA`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `camera_capture` | WRITE | Take a photo with the system camera and attach it to the conversation for vision analysis. Requires `CAMERA`; the photo is stored in the app cache and shared through FileProvider. | `reason` |
| `speak` | WRITE | Speak text through the device text-to-speech engine and wait until it finishes. | `text`** (≤1000), `language` (BCP-47), `rate`, `pitch` |
| `audio_record` | WRITE | Record 1–30 seconds from the microphone into `Recordings/` as M4A. Requires `RECORD_AUDIO` and your approval. | `seconds` (1–30) |
| `wallpaper_set` | WRITE | Set the home, lock or both wallpapers from a JPEG/PNG in any file location. | `location`, `path`**, `target` (`home`/`lock`/`both`) |

---

## System (`SYSTEM`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `volume_control` | WRITE | Read or change media/ring/alarm/notification volume. | `action`** (`up`/`down`/`mute`/`unmute`/`set`/`status`), `stream`, `percent` (0–100) |
| `brightness_set` | WRITE | Set screen brightness. Needs the special **Modify system settings** access; the tool opens that settings page when missing. | `percent`** (0–100) |
| `display_control` | WRITE | Auto-rotate on/off (rotation lock), screen timeout (15–1800 s) and adaptive brightness. Needs **Modify system settings**. | `action`** (`status`/`rotation_lock`/`rotation_unlock`/`timeout`/`auto_brightness_on`/`auto_brightness_off`), `seconds` |
| `quick_toggle` | DANGEROUS | Read or toggle Wi-Fi, Bluetooth, mobile data, hotspot, location, airplane mode, Do Not Disturb and battery saver. Direct radio APIs are blocked on Android 10+, so with the screen-control service enabled the tool taps the matching Quick Settings tile (and the Android 12+ Internet panel for Wi-Fi/mobile data). | `target`** (enum), `action`** (`status`/`on`/`off`/`toggle`) |
| `airplane_mode` | DANGEROUS | Read or toggle airplane mode through the Quick Settings tile and verify the global setting; without screen control it opens the airplane settings page. | `action`** (`status`/`on`/`off`/`toggle`) |
| `ringer_mode` | DANGEROUS | Normal, vibrate or silent ringer. Silent requires Do Not Disturb access; the tool opens that page when missing. | `action`** (`status`/`normal`/`vibrate`/`silent`) |
| `media_control` | WRITE | Send media keys to the active player. | `action`** (`play_pause`/`play`/`pause`/`next`/`previous`/`stop`) |
| `dnd_set` | DANGEROUS | Change Do Not Disturb mode. Needs the special DND access. | `mode`** (`off`/`priority`/`alarms`/`none`) |
| `alarm_set` | WRITE | Open the clock app's new-alarm screen pre-filled. The user confirms in the clock app. | `hour`**, `minute`**, `label` |
| `timer_set` | WRITE | Open the clock app's timer screen pre-filled. | `seconds`** (1–86,400), `label` |
| `reminder_schedule` | WRITE | Schedule a local notification with WorkManager (survives restarts). Asks for notification permission on Android 13+. | `title`**, `delay_seconds`** (5–2,592,000), `message` |
| `notification_post` | WRITE | Post a notification to the shade. Asks for notification permission on Android 13+. | `title`**, `message`** |
| `network_info` | READ | Active connection type, validated/metered state, interface, IPs, DNS, Wi-Fi SSID, signal, link speed and frequency. | none |
| `wifi_scan` | READ | Scan for nearby Wi-Fi networks with signal, band and security. Requires location (and `NEARBY_WIFI_DEVICES` on Android 13+); location services must be on. | `limit` (1–30) |
| `app_install` | DANGEROUS | Open the system package installer for a local APK. Requires **Install unknown apps**; always confirmed. | `location`, `path`** (.apk) |

Every state change above is verified by reading the system setting back; if the device refuses,
the tool returns an error instead of reporting success. `quick_toggle` and `airplane_mode`
report the state they actually observed after the tap, including “state unknown” when the OS
does not expose a value.

---

## Advanced (`ADVANCED`)

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `system_settings` | DANGEROUS | Read, write, delete or list `system`/`secure`/`global` settings by key. Writing `system` keys needs **Modify system settings**; writing `secure`/`global` needs `WRITE_SECURE_SETTINGS` (adb). `grant_info` returns the exact adb command. | `action`** (`get`/`set`/`delete`/`list`/`grant_info`), `namespace`, `key`, `value`, `query`, `limit` (1–300) |
| `screen_snapshot` | WRITE | Read visible text nodes from the accessibility service (max 220 nodes, 300 chars each) with clickable/editable flags and coordinates. Off by default. | none |
| `screen_action` | DANGEROUS | Tap by text or coordinates, scroll, type, back, home, recents, notification shade, quick settings, lock screen, power menu, split screen and screenshot through the accessibility service. Every call is confirmed. Off by default. | `action`**, `text`, `x`, `y`, `forward` |
| `mcp_list_tools` | READ | List tools exposed by the user's remote MCP server (HTTP/SSE, optional bearer key). Off by default. | none |
| `mcp_call_tool` | DANGEROUS | Call a remote MCP tool. Remote code is external and every call is confirmed. Off by default. | `tool_name`**, `arguments` (object) |

The accessibility service must be enabled manually in **Android Settings → Accessibility**.
It never runs automatically; `screen_snapshot`/`screen_action` return a clear error and open
the settings page when it is off. `system_settings` is powerful on purpose: keys such as
`animator_duration_scale` are harmless, keys such as `adb_enabled` or lock-screen policy can
change device behavior. Every call is DANGEROUS and audited.

### What Android does not allow

These limits apply to every app without root:

- **Wi-Fi, Bluetooth, mobile data, hotspot and NFC** cannot be switched through the public
  SDK on Android 10+. `quick_toggle` handles Wi-Fi/mobile data by tapping the combined
  **Internet** panel and Bluetooth/hotspot through their tiles; when the screen-control
  service is off, or the tile is not on the first page, the tool returns the observed state
  and opens the matching settings panel instead of pretending the toggle worked. NFC is
  read-only and uses `open_settings nfc`.
- **Airplane mode** cannot be written through `Settings.Global` on Android 10+.
  `airplane_mode` drives the Quick Settings tile through the accessibility service and
  verifies the result.
- **Silent ringer** is gated by Do Not Disturb access on Android 6+.
- **Secure/global settings** are read-only without `WRITE_SECURE_SETTINGS`; there is no
  user-facing settings screen for that permission, only adb.
- **Notification access** and **usage access** are special app-ops that only the user can
  grant in system settings.
- **Night display / color inversion / dark theme** are not exposed as writable settings on
  most devices; `open_settings night_display` opens the page.
- **Alarms, timers and uninstall** always require the system UI confirmation on modern
  Android; the agent can only pre-fill them.

---

## Web (`WEB`)

Both web tools treat fetched content as untrusted and require approval because URLs and
queries can carry private data.

| Tool | Risk | Description | Arguments |
| --- | --- | --- | --- |
| `web_fetch` | WRITE | Fetch a public `http(s)` URL and extract readable text with jsoup. Blocks loopback/private addresses unless **Allow private web addresses** is enabled. Output is capped. | `url`** |
| `web_search` | WRITE | Search through the user's Tavily-compatible endpoint (key stored encrypted). Returns titles, URLs, snippets. | `query`**, `count` (1–8) |

---

## Output and artifacts

`ToolResult` supports:

- `output` — text returned to the model, wrapped as untrusted.
- `isError` — feeds an error back to the model so it can adapt instead of claiming success.
- `artifact` — a `content://` URI, display name and MIME type shown in the step card with
  **Open** and **Share** actions. File writes in `location="all"` return a FileProvider URI
  so the result can be opened directly.
- `images` — local image paths attached as a follow-up user message after the tool result
  (used by `camera_capture`), explicitly labelled as tool output rather than a new
  instruction.

## Audit

Every execution writes a `ToolCallLogEntity` row: chat id, tool name, pretty arguments,
result, risk, `SUCCESS`/`ERROR`/`DENIED`/`CANCELLED`, timestamp, duration, approval mode
(`auto`, `ALLOW_ONCE`, `ALLOW_FOR_CHAT`, `ALWAYS_ALLOW`, `step-limit`, `none`), and whether
the prompt-injection detector matched. The log is visible under **Audit log** and can be
cleared there.

## Adding a tool

1. Implement `AgentTool` in `app/src/main/java/com/agentphone/app/tools/`.
2. Inject it with `@Inject constructor(...)`; Hilt discovers it through the
   `Set<AgentTool>` multibinding.
3. Give it a unique `name`, a precise `description` (the model reads it), a JSON Schema
   built with `Schemas.obj`, and an honest `riskLevel`.
4. Request runtime permissions lazily through `PermissionCoordinator` (never at startup).
5. Add it to the default enabled set only if it is safe to run after a normal approval.
6. Update this file.
