# Telegram away mode

**English** · [فارسی](#راهنمای-فارسی)

Away mode is an opt-in feature that answers Telegram messages **as your own account** while you
are unavailable. It is not a bot: messages are read and sent through TDLib, the official Telegram
client library, with the `api_id`/`api_hash` **you** create at
[my.telegram.org/apps](https://my.telegram.org/apps).

It is off by default. It never sends to a chat you have not explicitly allowed, never answers
channels or bots, never answers a group unless you were addressed, and escalates anything
sensitive to a human instead of replying.

## How a reply happens

```
UpdateNewMessage ──► TelegramRepository (TDLib update stream)
                          │  ignore outgoing, bots, channels, stale (>24 h)
                          ▼
                     AwayScheduler.onIncoming
                          │  master switch + per-chat allow-list
                          │  groups: addressed only, text only
                          │  merge a burst into one batch, timer from the LAST message
                          ▼
                     away_pending row (dueAt = last message + delay)
                          │
                     WorkManager unique work "away_reply_<chatId>"
                          ▼  after the delay
                     AwayWorker ──► AwayEngine.process
                          │  enabled? inside schedule window? unread? newer message?
                          │  already answered? per-chat/global caps, cooldown, slow mode?
                          │  OTP/payment/credentials/personal data/legal/admin? -> escalate
                          │  prompt injection? -> escalate + watchlist
                          ▼
                     AwayResponder (active provider, no tools, strict JSON)
                          │  one retry on invalid JSON, then escalate
                          ▼
               reply (with disclosure) │ draft │ ignore │ escalate
                          ▼
               channel.send(...) ──► TelegramChannel (TDLib) ──► audit + notification
```

Every guard is re-checked at fire time, not only when the timer starts. A message you read or
answer yourself, a newer message in the same chat, a cap, a cooldown, a closed schedule window,
or the master switch cancels the batch.

## Onboarding

1. Open [my.telegram.org/apps](https://my.telegram.org/apps) while signed in to your account.
2. Create an application (any name/platform) and copy its **api_id** and **api_hash**.
3. In AgentPhone open **Settings → Telegram** and enter the api_id, api_hash and your phone number
   in international format (`+98…`).
4. Tap **Connect**. Telegram sends a login code inside the Telegram app on your other sessions.
5. Enter the code. If two-step verification is enabled, enter your password. New accounts may be
   asked for a name or an email address.

The app never bundles or guesses credentials, and it cannot log in without your code. The phone
number and the code are never written to logs or crash reports. The api_hash is stored encrypted
with the Android Keystore (see below).

Signing out (or **Wipe local data**) closes the TDLib session, disables away mode, cancels every
pending timer, removes the encrypted credential blob and deletes the TDLib database folder.

## Where the data lives and how it is protected

| Item | Storage | Protection |
| --- | --- | --- |
| api_id | DataStore (`telegram_*`) | Plain integer; useless without the hash |
| api_hash | DataStore | AES-256-GCM via `SecretCipher`, key in the non-exportable Android Keystore |
| TDLib database key | DataStore | 32 random bytes, AES-256-GCM encrypted, generated on first use |
| TDLib database/messages | `files/tdlib/db`, `files/tdlib/files` | Encrypted SQLite by TDLib using that key; app-private |
| Login code / 2FA password | RAM only | Sent straight to TDLib, never persisted |
| Chat allow-list, drafts, decisions | Room `away_*` tables | App-private |
| TDLib log | — | Disabled: `Client.setLogMessageHandler(0, null)`; message text can never reach logcat |

All app data — including `files/tdlib/` — is excluded from cloud backup and device transfer in
`backup_rules.xml` and `data_extraction_rules.xml`.

Message text is sent **only** to the AI provider you configured, in the request built by
`AwayResponder`. Nothing is sent to AgentPhone servers; there are none.

## Rules and safety

- **Default deny.** Away mode starts with an empty allow-list. A chat must be added with one of:
  `Auto` (send low-risk replies), `Draft` (always queue for approval), `Never`.
- **Groups** are off by default and only answer when you are mentioned, replied to or called by
  a configured nickname. Group sends are threaded to the triggering message. Group replies are
  short, neutral and carry the disclosure suffix unless you turn it off for that chat.
- **Channels, bots and secret chats** are never answered.
- **Unknown senders, attachments and links** are forced to drafts, never auto-sent.
- **Hard escalations.** OTP/verification codes, payments/bank/card data, credentials, personal
  data requests, legal and admin/moderation topics are escalated to the approval queue without a
  model call (or after it, if detected in the reply). The model is also told to escalate.
- **Prompt injection.** Incoming text is matched by `PromptInjectionDetector` and passed to the
  model only inside escaped `<untrusted_message>` blocks. A hit escalates, adds the chat to the
  watchlist and is recorded in the audit log.
- **Strict output.** The model must return exactly
  `{"decision":"reply|draft|ignore|escalate","reply_text":"…","confidence":0.0-1.0,"reason":"…","urgency":"low|normal|high","detected_risks":["…"]}`.
  Invalid output is retried once with the parser error, then escalated. No send happens on a
  failed or malformed decision.
- **Threshold.** `reply` is only sent when confidence is at or above the configured threshold
  (default 0.75) and neither the model nor local checks reported a risk.
- **Caps and cooldowns** (defaults): 3 replies per chat per day, 20 per day overall, 15-minute
  cooldown per chat; groups use half the cap and at least a 60-minute cooldown. Reaching a limit
  logs an `IGNORED` decision with the reason.
- **No duplicates.** A batch is answered at most once: a sent/queued decision for the trigger
  message cancels a replayed job, and a newer incoming message supersedes the batch.
- **Auto-disable.** Three consecutive provider/transport failures turn the master switch off,
  cancel pending timers and post a notification.
- **Kill switches.** The Settings switch, the Quick Settings tile and the notification “Pause”
  action all disable away mode immediately and cancel every pending job.

The approval queue shows `DRAFT` and `ESCALATED` items with **Approve**, **Edit**, **Reject**,
**Never reply here**. The log records every decision and lets you **Undo** a sent reply (deletes
it for everyone when Telegram allows), correct it, or add the chat to the never list.

## Scheduling and lifecycle

- The **foreground service** (`TelegramService`, type `dataSync`) keeps TDLib connected while away
  mode is on. It is `START_STICKY`, restarts on boot and on app update (`AwayBootReceiver`), and
  shows an ongoing notification with a pause action.
- Per-chat timers are WorkManager unique work, so they survive Doze and reboots. The service
  re-enqueues anything missing when it starts or reconnects.
- The schedule window supports overnight ranges (`22:00 → 06:00`) and per-weekday toggles. A batch
  that comes due outside the window is re-queued for the next window start.
- A 15-minute `AwayWatchdog` periodic worker restarts the service if it died. `ServiceHealth`
  exposes the last heartbeat in the Away Mode screen.
- An optional **morning summary** notification is scheduled daily at 08:00 local time.
- Drafts, escalations and errors post notifications; tapping one opens the queue.

## TDLib build/update notes

- The app uses the prebuilt TDLib binding `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x),
  which ships `libtdjni.so` for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.
- The AAR exposes its Java classes through `libs/tdlib-classes.jar`, and AGP puts them on the
  compile/runtime classpath. **Do not** add that jar again as a local `files(...)` dependency —
  doing so produces duplicate `org.drinkless.tdlib.*` classes and fails
  `checkDebugDuplicateClasses`.
- The binding has **no `Client.close()`**. A TDLib client is created once per process; a `Close`
  request is sent only when you sign out. `TdLibClient.shutdown()` then drops the closed handle so
  a fresh client can be created for a new login. The native object cannot be freed by this binding;
  it stays idle until the process ends.
- To update TDLib, bump `tdlib.core` in `gradle/libs.versions.toml`. Java class names can change
  between TDLib releases (`TdApi` inner classes, auth state names); recompile and fix the mappings
  in `TdLibClient`/`TelegramChannel`. Test login, code/2FA, reading a chat, sending and logout.

## Ban risk and terms of service

Away mode sends messages from your personal account, so Telegram's anti-spam enforcement applies
to it:

- Automated or bulk messaging is against Telegram's terms. Only enable this on your own account,
  with people who expect to hear from you.
- Keep caps small (the defaults are conservative), keep the cooldown on, and prefer `Draft` for
  anyone you do not know. Never use it for advertising, outreach or mass replies.
- Telegram may limit (`FLOOD_WAIT`), restrict or ban accounts it considers spammy. **You use this
  feature at your own risk on your own account.** The app backs off on short flood waits and saves
  the reply as a draft instead of retrying aggressively, but it cannot prevent enforcement.
- Frequent login/logout and new sessions can also trigger additional verification or temporary
  limits from Telegram.

## Limitations

- One Telegram account (the one you sign in with). Secret chats are not supported.
- Auto-replies are text-only; attachments, stickers and voice messages are never sent.
- Reading requires the app process to receive updates: with away mode on, the foreground service
  must stay alive. Aggressive OEM battery managers may still kill it — see troubleshooting.
- Timers are best-effort under Doze; WorkManager may delay a reply.
- If Telegram forbids sending in the chat, the reply becomes a draft.
- `FLOOD_WAIT` longer than 15 minutes saves the reply as a draft.
- AI quality depends on your provider and model; a weak model will produce more drafts and
  escalations (which is the intended failure mode).
- Sign-out ends the session but the process keeps the closed native client until it exits.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| “Enter the api_id, api_hash and phone number…” | Credentials incomplete or the api_id changed; re-enter both. |
| `PHONE_NUMBER_INVALID` | Use international format with the `+` prefix. |
| Code never arrives | It is sent inside Telegram (service notifications), not SMS. Check other sessions. |
| `SESSION_PASSWORD_NEEDED` | Enter your two-step verification password. |
| `FLOOD_WAIT_n` | Telegram rate limit; wait `n` seconds. The app retries or drafts automatically. |
| `AUTH_KEY_UNREGISTERED` / logged out elsewhere | Session revoked by Telegram; sign in again. |
| Service health “stopped” | Battery optimization is killing the app. Use the battery card to exempt AgentPhone, keep notifications allowed. |
| Nothing is answered | Master switch, per-chat mode (`Auto`), global schedule window, cap/cooldown, or the chat is a group message that did not address you. The log explains each skip. |
| Provider errors | Run the provider connection test; after 3 consecutive failures away mode disables itself and posts a reason. |
| Wrong time zone schedule | The window uses the device local time zone. |

## Tests

The JVM suite covers the parts that must not regress:

| Test | Covers |
| --- | --- |
| `AwaySchedulerTest` | Per-chat debounce and burst merge (one batch timed from the last message), stale/outgoing/bot/channel filtering, addressed-only groups, `Never`, master switch, cancellation, pruning. Uses an in-memory Room database and WorkManager test initialization. |
| `AwayEngineTest` | End-to-end decision path with `FakeMessageChannel`: auto-reply with disclosure, unknown-sender draft, read-only chat draft, OTP/payment escalation, prompt-injection escalation + watchlist, provider failure with no send, confidence threshold, caps and cooldown, group threading, no double reply, supersede, per-chat context isolation, AI-disabled rule, master switch. |
| `AwayDecisionParserTest` | Strict JSON: valid/invalid shapes, prose wrapping, braces in strings, numeric confidence range, reply length, risks array, one-shot round trip. |
| `AwaySafetyTest` | OTP/payment/credential/personal-data/legal/admin detection in English and Persian (including ZWNJ), reply-risk scanning, disclosure de-duplication, wrapper escaping, reply cleanup. |
| `AwayScheduleTest` | Overnight windows, weekday toggles, equal start/end, invalid values. |

Run them with `./gradlew :app:testDebugUnitTest --tests "com.agentphone.app.telegram.*"`.

---

## راهنمای فارسی

### حالت دور از دسترس تلگرام

این قابلیت به‌صورت پیش‌فرض خاموش است. وقتی روشنش کنید، AgentPhone پیام‌های تلگرام را
**با حساب خودتان** (نه به‌عنوان ربات) و از طریق TDLib می‌خواند و در صورت لزوم پاسخ می‌دهد.
`api_id` و `api_hash` را خودتان از [my.telegram.org/apps](https://my.telegram.org/apps)
می‌سازید و در **تنظیمات ← تلگرام** وارد می‌کنید؛ برنامه هیچ کلید آماده‌ای ندارد.

**راه‌اندازی:** در my.telegram.org/apps یک اپلیکیشن بسازید، api_id و api_hash را بردارید، در
برنامه به همراه شماره با کد کشور (`+98…`) وارد کنید و «اتصال» را بزنید. کد ورود داخل خود
تلگرام (روی نشست‌های دیگر) می‌آید. اگر تأیید دو مرحله‌ای دارید، رمز را وارد کنید.

**قواعد ایمنی:**

- پیام هر چت فقط وقتی پاسخ می‌گیرد که آن چت را در فهرست مجاز اضافه کرده باشید:
  **خودکار**، **فقط پیش‌نویس** یا **هرگز**. پیش‌فرض، هیچ چتی مجاز نیست.
- کانال‌ها و ربات‌ها هرگز پاسخ نمی‌گیرند. در گروه‌ها فقط وقتی پاسخ می‌دهد که شما را صدا زده
  یا منشن کرده باشند و پاسخ به همان پیام پاسخ داده می‌شود.
- فرستنده ناشناس، پیام دارای فایل یا لینک فقط پیش‌نویس می‌شود، نه ارسال خودکار.
- رمز یک‌بارمصرف، اطلاعات بانکی و کارت، رمز عبور، اطلاعات شخصی، موضوعات حقوقی و مدیریتی
  هرگز پاسخ خودکار نمی‌گیرند و به صف تأیید می‌روند.
- اگر پیام سعی کند به مدل «دستور» بدهد (prompt injection)، به صف تأیید می‌رود و چت در
  فهرست مشکوک ثبت می‌شود.
- خروجی مدل باید دقیقاً همان JSON تعیین‌شده باشد؛ خروجی نامعتبر یک بار دوباره پرسیده می‌شود و
  بعد به تأیید انسانی می‌رود. در صورت خطای ارائه‌دهنده هیچ پیامی ارسال نمی‌شود.
- سقف پیش‌فرض: ۳ پاسخ در روز برای هر چت، ۲۰ پاسخ در روز برای همه چت‌ها و ۱۵ دقیقه فاصله بین
  پاسخ‌ها (در گروه‌ها سخت‌گیرانه‌تر). با رسیدن به سقف، دلیلش در گزارش ثبت می‌شود.
- بعد از سه خطای پشت‌سرهم، حالت دور از دسترس خودکار خاموش می‌شود و اطلاع می‌دهد.
- کلید اصلی در صفحه Away، در کاشی تنظیمات سریع و دکمه «توقف» اعلان در دسترس است.

**زمان‌بندی:** پنجره زمانی می‌تواند از شب تا صبح (مثلاً ۲۲:۰۰ تا ۰۶:۰۰) باشد و برای هر روز
هفته جدا تنظیم شود. اگر نوبت پاسخ خارج از پنجره برسد، به شروع پنجره بعدی موکول می‌شود.
سرویس پیش‌زمینه اتصال را زنده نگه می‌دارد، تایمرها با WorkManager بعد از ری‌استارت هم برمی‌گردند
و هر ۱۵ دقیقه یک نگهبان سلامت سرویس را بررسی می‌کند.

**امنیت داده:** api_hash و کلید رمزنگاری پایگاه‌داده TDLib با AES-256-GCM و کلیدی که در
Android Keystore نگهداری می‌شود رمز می‌شوند. پایگاه‌داده TDLib در حافظه خصوصی برنامه است و
همه داده‌ها از پشتیبان‌گیری ابری و انتقال دستگاه مستثنا هستند. لاگ داخلی TDLib کامل خاموش است
تا متن پیام‌ها هرگز در logcat دیده نشود. متن پیام‌ها فقط به ارائه‌دهنده هوش مصنوعی خودتان
می‌رود.

**خطر محدودیت حساب:** ارسال خودکار از حساب شخصی مشمول قوانین ضداسپم تلگرام است. این قابلیت
را فقط روی حساب خودتان و با افرادی که انتظار شنیدن از شما را دارند استفاده کنید. سقف‌ها را
کم نگه دارید و برای افراد ناشناس «فقط پیش‌نویس» را انتخاب کنید. تلگرام ممکن است حساب را
محدود یا مسدود کند؛ مسئولیت استفاده با خود شماست.

**محدودیت‌ها:** فقط یک حساب، بدون گفتگوی محرمانه، فقط پیام متنی، نیاز به شبکه و زنده‌بودن
سرویس پیش‌زمینه. در صورت ممنوعیت ارسال در چت یا `FLOOD_WAIT` طولانی، پاسخ به‌صورت پیش‌نویس
ذخیره می‌شود. برای حل مشکل «سرویس متوقف شده» برنامه را از بهینه‌سازی باتری معاف کنید.
