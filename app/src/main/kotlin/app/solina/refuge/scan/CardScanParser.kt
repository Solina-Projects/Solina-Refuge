package app.solina.refuge.scan

/**
 * تجزیهٔ خالصِ متن OCR برای اسکن کارت بانکی — بدون هیچ وابستگی اندرویدی، تا با
 * تست واحد قابل بررسی باشد.
 *
 * قوانین امنیتی:
 * - هیچ‌چیز به سرور نمی‌رود؛ این فایل فقط رشته‌ها را در حافظه پردازش می‌کند.
 * - شمارهٔ کارت فقط وقتی پذیرفته می‌شود که آزمون Luhn را بگذراند؛ حدس‌های OCR
 * (مثلاً O به‌جای 0) فقط در صورتی قبول‌اند که پس از جایگذاری، Luhn برقرار شود.
 * - تاریخ انقضا فقط به‌صورت MM/YY پذیرفته می‌شود و ماه باید 01 تا 12 باشد.
 */
data class CardScanResult(
    /** شمارهٔ کارت، فقط رقم، بدون فاصله (۱۳ تا ۱۹ رقم). */
    val cardNumber: String,
    /** تاریخ انقضا به‌صورت "MM/YY" یا null اگر در متن پیدا نشد. */
    val expiry: String?,
)

/** نگاشت ارقام فارسی/عربی به لاتین و اشتباه‌های رایج OCR به رقم. */
private val OCR_DIGIT_MAP = mapOf(
    '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
    '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
    '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
    '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
    'O' to '0', 'o' to '0',
    'I' to '1', 'l' to '1', '|' to '1',
    'S' to '5', 's' to '5',
    'B' to '8',
    'G' to '6',
    'Z' to '2', 'z' to '2',
)

/** آیا کاراکتر می‌تواند بخشی از یک عددِ خوانده‌شده با OCR باشد؟ */
private fun isDigitLike(ch: Char): Boolean = ch.isDigit() || ch in OCR_DIGIT_MAP

/** فقط رقم‌های لاتین متن را نگه می‌دارد (بدون حدس OCR). */
fun digitsOnly(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        when {
            ch in '0'..'9' -> sb.append(ch)
            ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
            ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
        }
    }
    return sb.toString()
}

/** ارقام فارسی/عربی و اشتباه‌های رایج OCR را به رقم لاتین تبدیل می‌کند. */
fun normalizeOcrDigits(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        when {
            ch in '0'..'9' -> sb.append(ch)
            ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
            ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
            ch in OCR_DIGIT_MAP -> sb.append(OCR_DIGIT_MAP.getValue(ch))
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** آزمون Luhn؛ [digits] فقط رقم و ۱۳ تا ۱۹ رقم باشد. */
fun luhnValid(digits: String): Boolean {
    if (digits.length !in 13..19 || digits.any { it !in '0'..'9' }) return false
    var sum = 0
    var double = false
    for (i in digits.length - 1 downTo 0) {
        var d = digits[i] - '0'
        if (double) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
        double = !double
    }
    return sum % 10 == 0
}

/** شماره را به گروه‌های ۴رقمی با فاصله تبدیل می‌کند («4111111111111111» → «4111 1111 1111 1111»). */
fun formatCardNumber(digits: String): String {
    val clean = digitsOnly(digits)
    if (clean.isEmpty()) return ""
    return clean.chunked(4).joinToString(" ")
}

/** ورودی خام تاریخ را به «MM/YY» تبدیل می‌کند («1228» → «12/28»). */
fun formatExpiry(raw: String): String {
    val d = digitsOnly(raw).take(4)
    return if (d.length > 2) d.substring(0, 2) + "/" + d.substring(2) else d
}

/** آیا «MM/YY» معتبر است (ماه 01–12)؟ */
fun isValidExpiry(expiry: String): Boolean {
    val m = Regex("""^(0[1-9]|1[0-2])/(\d{2})$""").matchEntire(expiry.trim()) ?: return false
    return m.groupValues[1].toInt() in 1..12
}

object CardScanParser {

    /** متن کامل OCR (چند خط) را به شمارهٔ کارت و تاریخ انقضا تبدیل می‌کند. */
    fun parseCardText(text: String): CardScanResult? {
        val number = findCardNumber(text) ?: return null
        return CardScanResult(cardNumber = number, expiry = parseExpiry(text))
    }

    /**
     * شمارهٔ کارت را از متن OCR پیدا می‌کند.
     * اول رشته‌های رقم‌مانندِ هر خط، بعد (برای شماره‌های شکسته بین دو خط) پنجره‌های
     * لغزان روی جریان رقم‌های کل متن؛ هر نامزد فقط با Luhn قبول می‌شود.
     */
    fun findCardNumber(text: String): String? {
        // نامزدهای خط‌به‌خط: رشته‌هایی از رقم/حرفِ شبیه‌رقم/فاصله/خط‌تیره، به‌ترتیب طول.
        val lineCandidates = text.lines()
            .flatMap { line -> Regex("""[0-9۰-۹٠-٩OoIl|SsBGZz][0-9۰-۹٠-٩OoIl|SsBGZz \-.]{11,25}""").findAll(line).map { it.value } }
            .sortedByDescending { digitsOnly(it).length }
        for (candidate in lineCandidates) {
            validateCandidate(candidate)?.let { return it }
        }
        // شمارهٔ شکسته بین خط‌ها: جریان رقم‌های کل متن، پنجرهٔ ۱۶رقمی اول.
        val stream = buildString {
            for (ch in text) {
                if (ch.isDigit() || ch in OCR_DIGIT_MAP) append(normalizeOcrDigits(ch.toString()))
            }
        }
        for (len in listOf(16, 15, 19, 14, 13, 17, 18)) {
            var i = 0
            while (i + len <= stream.length) {
                val window = stream.substring(i, i + len)
                if (window[0] in '3'..'6' && luhnValid(window)) return window
                i++
            }
        }
        return null
    }

    /** یک رشتهٔ نامزد را بررسی می‌کند: اول رقم‌های خام، بعد با حدس OCR؛ فقط Luhn قبول است. */
    fun validateCandidate(candidate: String): String? {
        val raw = digitsOnly(candidate)
        if (raw.length in 13..19 && raw[0] in '3'..'6' && luhnValid(raw)) return raw
        val mapped = normalizeOcrDigits(candidate).filter { it in '0'..'9' }
        if (mapped.length in 13..19 && mapped != raw && mapped[0] in '3'..'6' && luhnValid(mapped)) return mapped
        return null
    }

    /**
     * تاریخ انقضا را از متن پیدا می‌کند: «MM/YY» یا «MM-YY» یا «MM/YYYY» و
     * «MMYY» فقط وقتی کنار کلمهٔ انقضا (EXP / VALID THRU / انقضا) آمده باشد.
     */
    fun parseExpiry(text: String): String? {
        val normalized = normalizeOcrDigits(text)
        // lookbehind جلوِ تطابقِ تکه‌ای از شمارهٔ کارتِ خط‌تیره‌دار («4111-1111…» → ‏«11/11») را می‌گیرد.
        val separated = Regex("""(?<![0-9.\-])(0[1-9]|1[0-2])\s*[/\-.]\s*(20)?([0-9]{2})(?![0-9])""")
            .find(normalized)
        if (separated != null) {
            return "${separated.groupValues[1]}/${separated.groupValues[3]}"
        }
        val keyworded = Regex(
            """(?i)(exp|expiry|valid\s*thru|thru|انقضا|تاریخ)[^\d]{0,12}(0[1-9]|1[0-2])\s*[/\-.]?\s*(20)?([0-9]{2})(?![0-9])""",
        ).find(normalized)
        if (keyworded != null) {
            return "${keyworded.groupValues[2]}/${keyworded.groupValues[4]}"
        }
        return null
    }
}
