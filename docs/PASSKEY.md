# Passkey و دامنهٔ Refuge

## دامنه و Digital Asset Links — آماده است

- دامنه: `https://solina-projects.github.io`
- فایل: `https://solina-projects.github.io/.well-known/assetlinks.json`
  (مخزن `Solina-Projects/Solina-Projects.github.io`، شاخهٔ `main`؛
  فایل `.nojekyll` هم هست تا GitHub Pages پوشهٔ `.well-known` را سرو کند.)
- محتوا: پکیج `app.solina.refuge` با اثر انگشت SHA-256 گواهی انتشار و
  هر دو relation لازم:
  `delegate_permission/common.handle_all_urls` (برای App Links با `autoVerify`)
  و `handle_all_urls` (برای ارتباط Passkey در Credential Manager).
- مانیفست: `MainActivity` یک `intent-filter` با `android:autoVerify="true"`
  برای `https://solina-projects.github.io` دارد.

بررسی زنده (۲۰۲۶-۱۱-۲۲): `curl` روی
`https://solina-projects.github.io/.well-known/assetlinks.json` کد **۲۰۰**
و `Content-Type: application/json` برمی‌گرداند و محتوا دقیقاً همان فایل بالا است.

## چه چیزی کار می‌کند و چه چیزی نه

- در Refuge، شناسهٔ طرف اعتماد (RP ID) هر Passkey، میزبانِ وب‌سایتِ همان ورودی
  خزانه است (مثلاً `github.com`)؛ یعنی Passkey هر سایت با دامنهٔ همان سایت
  کار می‌کند، نه با دامنهٔ Refuge. دامنهٔ `solina-projects.github.io` فقط
  ارتباطِ خودِ اپِ Refuge را برقرار می‌کند: App Links و Passkeyهایی که
  RP IDشان `solina-projects.github.io` باشد (مثلاً ورود به سرویسِ وبِ آیندهٔ Refuge).
- **فقط روی نصبِ امضاشده با گواهی انتشار** تأیید می‌شود. بیلدهای debug که CI
  می‌سازد با گواهی debug امضا می‌شوند و اثر انگشتشان با فایل assetlinks
  نمی‌خواند؛ پس روی آن‌ها App Links/تأیید دامنه کار نمی‌کند. این طبیعی است،
  نه باگ.
- ساخت Passkey در دستگاه به سرویس ارائه‌دهندهٔ Passkey (مثلاً Google Password
  Manager) نیاز دارد؛ اگر در دستگاه فعال نباشد، ساخت ناموفق می‌شود و اپ
  پیام مناسب نشان می‌دهد.

## هنوز مانده

- تست زندهٔ سرتاسری روی گوشی واقعی (POCO F3) با نصبِ release امضاشده:
  ساخت Passkey برای یک ورودی، ورود با آن، و باز شدن لینک
  `https://solina-projects.github.io` داخل اپ. تا وقتی این تست انجام نشده،
  این بخش «پیاده‌شده ولی تست‌نشده روی دستگاه» است.
- اگر گواهی انتشار عوض شود، اثر انگشت در `assetlinks.json`، در
  `BuildConfig.RELEASE_SIGNING_SHA256` (در `app/build.gradle.kts`) و در
  بررسی یکپارچگی باید هم‌زمان به‌روز شوند.
