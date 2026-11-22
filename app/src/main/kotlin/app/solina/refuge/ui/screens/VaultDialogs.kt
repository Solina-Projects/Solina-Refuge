package app.solina.refuge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.solina.refuge.core.security.VaultInfo
import app.solina.refuge.ui.theme.Refuge

/** تعویض خزانه: رمز همان خزانهٔ مقصد پرسیده می‌شود؛ رمزِ خزانهٔ دیگر کار نمی‌کند. */
@Composable
fun VaultSwitchDialog(
    vault: VaultInfo,
    busy: Boolean,
    message: String?,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Refuge.colors
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.container,
        title = { Text("تعویض خزانه", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "به «${vault.name}» می‌روید. رمز همان خزانه را بزنید؛ خزانهٔ فعلی قفل می‌شود.",
                    color = c.muted, fontSize = 13.sp, lineHeight = 21.sp,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("رمز خزانهٔ «${vault.name}»") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message != null) Text(message, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            Text(
                if (busy) "…" else "باز کردن",
                color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = !busy && password.isNotEmpty()) {
                    onSubmit(password.toCharArray())
                }.padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "انصراف", color = c.muted, fontSize = 15.sp,
                modifier = Modifier.clickable(enabled = !busy, onClick = onDismiss).padding(12.dp),
            )
        },
    )
}

/** ساخت خزانهٔ جدید: نام + رمز مخصوص خودش. عبارت بازیابی ۲۴ کلمه‌ای بعد نشان داده می‌شود. */
@Composable
fun VaultCreateDialog(
    busy: Boolean,
    message: String?,
    onSubmit: (String, CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Refuge.colors
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val canSubmit = !busy && name.isNotBlank() && password.isNotEmpty() && password == confirm
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.container,
        title = { Text("خزانهٔ جدید", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "هر خزانه رمز و ۲۴ کلمهٔ بازیابی مخصوص خودش را دارد. خزانهٔ جدید خالی ساخته می‌شود و فعال می‌گردد.",
                    color = c.muted, fontSize = 13.sp, lineHeight = 21.sp,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام خزانه (مثلاً «کار» یا «خانواده»)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("رمز این خزانه") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("تکرار رمز") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (password.isNotEmpty() && confirm.isNotEmpty() && password != confirm) {
                    Text("دو رمز با هم یکی نیستند.", color = c.secondary, fontSize = 13.sp)
                }
                if (message != null) Text(message, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            Text(
                if (busy) "…" else "ساختن",
                color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = canSubmit) {
                    onSubmit(name.trim(), password.toCharArray())
                }.padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "انصراف", color = c.muted, fontSize = 15.sp,
                modifier = Modifier.clickable(enabled = !busy, onClick = onDismiss).padding(12.dp),
            )
        },
    )
}

/** نمایش ۲۴ کلمهٔ بازیابیِ خزانهٔ تازه؛ تا کاربر تأیید نکند، خزانه ثبت نمی‌شود. */
@Composable
fun VaultWordsDialog(
    vaultName: String,
    words: List<String>,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Refuge.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.container,
        title = { Text("۲۴ کلمهٔ «$vaultName»", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "این ۲۴ کلمه فقط برای همین خزانه است و فقط همین یک‌بار نشان داده می‌شود. آن‌ها را روی کاغذ بنویسید و جای امن نگه دارید؛ اگر رمز را فراموش کنید، فقط با همین کلمات می‌شود خزانه را باز کرد.",
                    color = c.muted, fontSize = 13.sp, lineHeight = 21.sp,
                )
                words.forEachIndexed { i, w ->
                    Text("${i + 1}. $w", color = c.text, fontSize = 15.sp)
                }
            }
        },
        confirmButton = {
            Text(
                "نوشتم و نگه داشتم", color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onConfirmed).padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "انصراف (خزانه ساخته نمی‌شود)", color = c.muted, fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
            )
        },
    )
}

/** حذف خزانه: تأیید صریح؛ همهٔ ورودی‌های آن خزانه برای همیشه پاک می‌شوند. */
@Composable
fun VaultDeleteDialog(
    vault: VaultInfo,
    busy: Boolean,
    message: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Refuge.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.container,
        title = { Text("حذف خزانهٔ «${vault.name}»؟", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "همهٔ ورودی‌ها، تاریخچه و یادآورهای این خزانه برای همیشه پاک می‌شوند و برنمی‌گردند. خزانه‌های دیگر دست نمی‌خورند.",
                    color = c.muted, fontSize = 13.sp, lineHeight = 21.sp,
                )
                if (message != null) Text(message, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            Text(
                if (busy) "…" else "حذف برای همیشه",
                color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = !busy, onClick = onConfirm).padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "انصراف", color = c.muted, fontSize = 15.sp,
                modifier = Modifier.clickable(enabled = !busy, onClick = onDismiss).padding(12.dp),
            )
        },
    )
}
