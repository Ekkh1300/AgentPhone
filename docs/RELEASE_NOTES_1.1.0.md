# AgentPhone 1.1.0 — SMS auto-replies and Telegram away mode

## English

This release adds two opt-in assistants that can answer for you when you are unavailable. Both are
**off by default**, both can be stopped instantly, and neither is an agent tool: the model cannot
enable them or send through them.

### Automatic SMS replies (new)

- If you have not answered a text within the configured delay (30 minutes by default), the agent
  reads that thread and replies with your instructions and long-term memory notes.
- Never answers your own number, shortcodes or alphanumeric senders; skips verification/OTP
  messages; cancels as soon as you reply yourself; refuses messages flagged as prompt injection.
- One pending reply per address (a newer message restarts the timer), daily limit, visible history
  screen, notification and an `auto_reply` audit entry for every send or skip.
- Uses the active provider with no tools at a 0.4 temperature; nothing is sent when provider
  settings fail.

### Telegram away mode (new)

- Sign in with **your own** api_id/api_hash from my.telegram.org and reply **as your own account**
  through TDLib (not Bot API). The TDLib database is encrypted with a Keystore-protected key; the
  api_hash is encrypted; TDLib logging is disabled so message text never reaches logcat.
- **Default-deny allow-list** with per-chat `Auto` / `Draft` / `Never`. Channels, bots and secret
  chats are never answered. Groups answer only when you are mentioned, replied to or called by a
  configured nickname, and their replies are threaded, short, capped lower and disclosed.
- Message bursts are merged into one batch timed from the **last** message. A newer message, your
  own reply, reading the chat, a closed schedule window, caps, cooldown or the master switch
  cancel the batch.
- **Hard escalations to the approval queue:** OTP/verification codes, bank/card/payment data,
  credentials, personal-data requests, legal and admin topics are never auto-sent. Prompt
  injection escalates, is audited and adds the chat to a watchlist.
- Strict JSON decisions with validation, one retry, then escalation. No send on provider failure;
  three consecutive failures disable away mode automatically.
- Per-chat/global daily caps, cooldown, slow-mode respect, disclosure suffix, schedule windows
  with overnight ranges and weekday toggles, morning summary at 08:00, Quick Settings tile,
  notification pause action, connection screen and service-health indicator.
- Approval queue (Approve / Edit / Reject / Never) and a decision log with Undo, Correct and
  Never actions.

### Also in this release

- Room schema v3 with migration for the away-mode tables; drafts and escalations included in the
  queue.
- New JVM tests for debounce/burst merge, cancellation, schedule windows, caps/cooldown, strict
  JSON, prompt injection, OTP/payment detection, per-chat context isolation, no double replies and
  a replayable `FakeMessageChannel`; Persian safety patterns now tolerate the zero-width
  non-joiner.
- New docs: [TELEGRAM.md](TELEGRAM.md); updated ARCHITECTURE, SECURITY, README (EN/FA).
- R8 keep rules for the TDLib JNI classes.

### Notes and limits

- Telegram automation is subject to Telegram's anti-spam rules. Use it only on your own account,
  keep the caps and cooldown, and prefer drafts for unknown senders. Ban risk is documented in
  [TELEGRAM.md](TELEGRAM.md#ban-risk-and-terms-of-service).
- SMS replies cover SMS only (not RCS or other messengers). Telegram replies are text-only and
  need the foreground service to stay alive; some OEM battery managers must exempt the app.
- Delays are best-effort under Doze/WorkManager.

### Asset

- `AgentPhone-1.1.0-release.apk` — release build (R8-minified), signed with the same AgentPhone
  release key.
- versionCode 4, versionName 1.1.0, minSdk 26, targetSdk 36.

---

## فارسی

این نسخه دو دستیار اختیاری اضافه می‌کند که وقتی در دسترس نیستید می‌توانند به‌جای شما پاسخ دهند.
هر دو **پیش‌فرض خاموش‌اند**، هر لحظه متوقف می‌شوند و هیچ‌کدام ابزار ایجنت نیستند؛ مدل نمی‌تواند
آن‌ها را روشن کند یا از آن‌ها پیام بفرستد.

### پاسخ خودکار پیامک (جدید)

- اگر تا مدت تعیین‌شده (پیش‌فرض ۳۰ دقیقه) به پیامکی پاسخ نداده باشید، عامل همان گفتگو را
  می‌خواند و با دستورها و حافظهٔ بلندمدت شما جواب می‌دهد.
- هرگز به شمارهٔ خودتان، سرشماره‌ها و فرستنده‌های متنی پاسخ نمی‌دهد؛ کدهای تأیید را رد می‌کند؛
  به‌محض پاسخ خودتان لغو می‌شود؛ پیام‌های مشکوک به تزریق دستور را نمی‌پذیرد.
- برای هر شماره فقط یک پاسخ در انتظار (پیام جدید تایمر را از نو شروع می‌کند)، سقف روزانه،
  صفحهٔ تاریخچه، اعلان و ثبت `auto_reply` در گزارش ممیزی برای هر ارسال یا رد.
- از ارائه‌دهندهٔ فعال با دمای ۰٫۴ و بدون هیچ ابزاری استفاده می‌کند؛ اگر تنظیمات ارائه‌دهنده
  خطا بدهد چیزی ارسال نمی‌شود.

### حالت دور از دسترس تلگرام (جدید)

- با **api_id/api_hash خودتان** از my.telegram.org وارد می‌شوید و از طریق TDLib (نه Bot API)
  **با حساب خودتان** پاسخ می‌دهید. پایگاه‌دادهٔ TDLib با کلید محافظت‌شدهٔ Keystore رمز می‌شود،
  api_hash رمزنگاری می‌شود و لاگ TDLib خاموش است تا متن پیام‌ها در logcat دیده نشود.
- **فهرست مجاز با پیش‌فرض بسته** و حالت‌های `خودکار` / `فقط پیش‌نویس` / `هرگز` برای هر چت.
  کانال‌ها، ربات‌ها و گفتگوهای محرمانه هرگز پاسخ نمی‌گیرند. گروه‌ها فقط با منشن، پاسخ به شما یا
  صدا زدن با لقب تنظیم‌شده جواب می‌گیرند؛ پاسخ رشته‌ای، کوتاه، با سقف کمتر و دارای پسوند
  «پاسخ خودکار» است.
- پیام‌های پشت‌سرهم یک دسته می‌شوند و تایمر از **آخرین** پیام حساب می‌شود. پیام جدیدتر، پاسخ
  خودتان، خواندن چت، بسته‌بودن پنجرهٔ زمانی، سقف، فاصلهٔ پاسخ یا کلید اصلی، دسته را لغو می‌کند.
- **موارد حساس به صف تأیید می‌روند:** رمز یک‌بارمصرف، اطلاعات بانکی/کارت/پرداخت، رمز عبور،
  درخواست اطلاعات شخصی، موضوعات حقوقی و مدیریتی هرگز خودکار ارسال نمی‌شوند. تزریق پرامپت
  باعث ارجاع، ثبت در گزارش و افزودن چت به فهرست مشکوک می‌شود.
- تصمیم‌ها JSON سخت‌گیرانه با اعتبارسنجی، یک بار تلاش دوباره و سپس ارجاع هستند. در خطای
  ارائه‌دهنده چیزی ارسال نمی‌شود و سه خطای پیاپی حالت را خودکار خاموش می‌کند.
- سقف روزانهٔ هر چت و کل، فاصلهٔ پاسخ، احترام به slow mode، پسوند افشا، پنجرهٔ زمانی با بازهٔ
  شبانه و انتخاب روزها، خلاصهٔ صبحگاهی ساعت ۰۸:۰۰، کاشی تنظیمات سریع، دکمهٔ توقف در اعلان،
  صفحهٔ اتصال و نشانگر سلامت سرویس.
- صف تأیید (تأیید / ویرایش / رد / هرگز) و گزارش تصمیم‌ها با بازگردانی، اصلاح و «هرگز».

### همچنین در این نسخه

- طرح Room نسخهٔ ۳ با مهاجرت جدول‌های حالت دور از دسترس؛ پیش‌نویس‌ها و موارد ارجاع‌شده در صف.
- تست‌های JVM برای debounce و ادغام دسته، لغو، پنجرهٔ زمانی، سقف/فاصله، JSON سخت‌گیرانه،
  تزریق پرامپت، تشخیص کد یک‌بارمصرف/پرداخت، جداسازی زمینهٔ هر چت، جلوگیری از پاسخ تکراری و
  `FakeMessageChannel` قابل‌پخش؛ الگوهای فارسی حالا نیم‌فاصله را هم می‌شناسند.
- مستندات جدید: [TELEGRAM.md](TELEGRAM.md) و به‌روزرسانی ARCHITECTURE، SECURITY و README
  (انگلیسی/فارسی).
- قواعد R8 برای کلاس‌های JNI کتابخانهٔ TDLib.

### نکته‌ها و محدودیت‌ها

- ارسال خودکار در تلگرام مشمول قوانین ضداسپم تلگرام است. فقط روی حساب خودتان استفاده کنید،
  سقف و فاصله را کم نگه دارید و برای افراد ناشناس «فقط پیش‌نویس» را انتخاب کنید. خطر محدودیت
  حساب در [TELEGRAM.md](TELEGRAM.md#ban-risk-and-terms-of-service) توضیح داده شده است.
- پاسخ خودکار فقط پیامک را پوشش می‌دهد (نه RCS و اپ‌های دیگر). پاسخ تلگرام فقط متنی است و به
  زنده‌بودن سرویس پیش‌زمینه نیاز دارد؛ در برخی گوشی‌ها باید برنامه را از بهینه‌سازی باتری معاف
  کنید. تأخیرها تحت Doze و WorkManager تقریبی هستند.

### فایل

- `AgentPhone-1.1.0-release.apk` — نسخهٔ انتشار (کوچک‌شده با R8)، امضاشده با همان کلید انتشار
  AgentPhone.
- versionCode 4، versionName 1.1.0، minSdk 26، targetSdk 36.
