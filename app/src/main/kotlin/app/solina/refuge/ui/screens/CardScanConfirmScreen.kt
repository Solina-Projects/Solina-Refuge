package app.solina.refuge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.solina.refuge.scan.CardScanResult
import app.solina.refuge.scan.formatCardNumber
import app.solina.refuge.scan.formatExpiry
import app.solina.refuge.scan.isValidExpiry
import app.solina.refuge.scan.luhnValid
import app.solina.refuge.ui.components.IconAction
import app.solina.refuge.ui.theme.Refuge
import app.solina.refuge.ui.theme.RefugeIcons

/**
 * تأیید دستیِ نتیجهٔ اسکن کارت، پیش از هر ذخیره‌ای.
 *
 * OCR ممکن است اشتباه بخواند، پس شماره و تاریخِ خوانده‌شده اینجا قابل ویرایش‌اند و
 * دکمهٔ تأیید فقط وقتی فعال است که شماره آزمون Luhn را بگذراند. تا وقتی کاربر
 * «تأیید و ادامه» را نزند، هیچ‌چیز — نه در خزانه، نه هیچ‌جای دیگر — ذخیره نمی‌شود.
 * CVV2 و PIN هرگز از روی کارت خوانده نمی‌شوند و اینجا هم فیلدی ندارند؛ کاربر
 * آن‌ها را جدا در ویرایشگر وارد می‌کند.
 */
@Composable
fun CardScanConfirmScreen(
    result: CardScanResult,
    initialBank: String,
    initialHolder: String,
    onConfirm: (number: String, expiry: String, bank: String, holder: String) -> Unit,
    onRescan: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Refuge.colors
    val str = Refuge.strings
    var number by remember { mutableStateOf(formatCardNumber(result.cardNumber)) }
    var expiry by remember { mutableStateOf(result.expiry.orEmpty()) }
    var bank by remember { mutableStateOf(initialBank) }
    var holder by remember { mutableStateOf(initialHolder) }
    var tried by remember { mutableStateOf(false) }

    val digits = number.filter { it.isDigit() }
    val numberError = when {
        !tried -> null
        digits.isEmpty() -> if (str.isFa) "شمارهٔ کارت لازم است" else "Card number is required"
        digits.length !in 13..19 -> if (str.isFa) "شمارهٔ کارت باید ۱۳ تا ۱۹ رقم باشد" else "Card number must be 13–19 digits"
        !luhnValid(digits) -> if (str.isFa) "شمارهٔ کارت معتبر نیست؛ دوباره بررسی کنید" else "Card number is not valid; please check it"
        else -> null
    }
    val expiryError = when {
        !tried || expiry.isBlank() -> null
        !isValidExpiry(expiry) -> if (str.isFa) "تاریخ انقضا باید MM/YY معتبر باشد (ماه ۰۱ تا ۱۲)" else "Expiry must be a valid MM/YY (month 01–12)"
        else -> null
    }
    val canConfirm = numberError == null && expiryError == null && digits.isNotEmpty()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.statusBarsPadding().padding(top = 12.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(RefugeIcons.ChevronRight, str.cancel, tint = c.text, onClick = onCancel)
            Text(
                if (str.isFa) "بررسی شمارهٔ کارت" else "Check card number",
                color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            if (str.isFa) "شماره و تاریخ را با کارت مقایسه کنید و اگر OCR اشتباه خوانده، اصلاح کنید. تا تأیید نکنید چیزی ذخیره نمی‌شود؛ عکس کارت هم ذخیره یا ارسال نشده است."
            else "Compare the number and date with the card and fix any OCR mistake. Nothing is saved until you confirm; the card photo was never stored or sent.",
            color = c.muted, fontSize = 14.sp, lineHeight = 24.sp,
        )

        Field(
            if (str.isFa) "شمارهٔ کارت" else "Card Number",
            number,
            { number = formatCardNumber(it.filter { ch -> ch.isDigit() }.take(19)) },
            mono = true, keyboard = KeyboardType.Number, error = numberError,
        )
        Field(
            if (str.isFa) "انقضا (MM/YY)" else "Expiry (MM/YY)",
            expiry,
            { expiry = formatExpiry(it) },
            mono = true, keyboard = KeyboardType.Number, error = expiryError,
        )
        Field(if (str.isFa) "نام بانک" else "Bank Name", bank, { bank = it })
        Field(if (str.isFa) "نام دارندهٔ کارت" else "Cardholder Name", holder, { holder = it })

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(if (canConfirm || !tried) c.primary else c.divider)
                .clickable(role = Role.Button) {
                    tried = true
                    if (numberError == null && expiryError == null && digits.isNotEmpty()) {
                        onConfirm(digits, expiry.trim(), bank.trim(), holder.trim())
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (str.isFa) "تأیید و ادامه" else "Confirm and continue",
                color = c.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(c.container)
                .clickable(role = Role.Button) { onRescan() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (str.isFa) "اسکن دوباره" else "Scan again",
                color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
        }

        Text(
            if (str.isFa) "بازگشت" else "Back",
            color = c.muted, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button) { onCancel() }.padding(8.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}
