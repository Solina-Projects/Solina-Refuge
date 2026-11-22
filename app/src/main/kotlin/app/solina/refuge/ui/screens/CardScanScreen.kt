package app.solina.refuge.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.solina.refuge.MainActivity
import app.solina.refuge.scan.CardScanParser
import app.solina.refuge.scan.CardScanResult
import app.solina.refuge.scan.CardTextRecognizer
import app.solina.refuge.ui.components.IconAction
import app.solina.refuge.ui.theme.Refuge
import app.solina.refuge.ui.theme.RefugeIcons
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * اسکن کارت بانکی با دوربین + OCR روی دستگاه (ML Kit Text Recognition، مدل همراه).
 *
 * - دوربین فقط وقتی این صفحه باز است روشن است؛ هیچ عکسی ذخیره، کپی یا ارسال نمی‌شود
 *   و هیچ فراخوانی شبکه‌ای در این مسیر نیست.
 * - شماره فقط وقتی قبول می‌شود که آزمون Luhn را بگذراند؛ نتیجهٔ OCR هرگز مستقیم
 *   ذخیره نمی‌شود — اول در صفحهٔ تأیید نشان داده می‌شود تا کاربر بررسی و اصلاح کند.
 * - اگر OCR چیزی پیدا نکرد یا کاربر نخواست، «ورود دستی» مستقیم به ویرایشگر می‌رود.
 * [onDetected] فقط یک‌بار، با اولین شمارهٔ معتبرِ خوانده‌شده، صدا زده می‌شود.
 */
@Composable
fun CardScanScreen(
    activity: MainActivity,
    onDetected: (CardScanResult) -> Unit,
    onManualEntry: () -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
) {
    val c = Refuge.colors
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val done = remember { AtomicBoolean(false) }
    fun deliver(result: CardScanResult) { if (done.compareAndSet(false, true)) onDetected(result) }

    // مجوز دوربین فقط با ضربهٔ کاربر خواسته می‌شود، نه خودکار (مثل ScanScreen).
    fun requestCamera() {
        activity.requestPermissionOnce(
            Manifest.permission.CAMERA,
            MainActivity.RC_CAMERA,
            handler = { ok -> granted = ok; denied = !ok },
            onFail = { denied = true; onToast("درخواست مجوز دوربین انجام نشد") },
        )
    }

    fun pickGalleryImage() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        activity.launchForResult(
            intent,
            MainActivity.RC_CARD_GALLERY_IMAGE,
            handler = { resultCode, data ->
                val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                if (uri != null) {
                    val bmp = runCatching { decodeBitmap(ctx.contentResolver, uri) }.getOrNull()
                    if (bmp == null) {
                        onToast("خواندن این عکس ممکن نشد؛ دستی وارد کنید")
                    } else {
                        CardTextRecognizer.recognizeBitmap(bmp, ContextCompat.getMainExecutor(ctx)) { lines ->
                            val result = CardScanParser.parseCardText(lines.joinToString("\n"))
                            if (result != null) deliver(result)
                            else {
                                failed = true
                                onToast("شمارهٔ کارت در این عکس پیدا نشد؛ دستی وارد کنید")
                            }
                        }
                    }
                }
            },
            onFail = { onToast("انتخاب‌گر عکس در دسترس نیست") },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CardCameraPreview(onLines = { lines ->
            CardScanParser.parseCardText(lines.joinToString("\n"))?.let { deliver(it) }
        })

        Row(Modifier.statusBarsPadding().padding(top = 12.dp, start = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(RefugeIcons.ChevronRight, "بازگشت", tint = Color.White, onClick = onCancel)
            Text("اسکن کارت بانکی", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        if (granted) {
            // کادر کارت: نسبت تقریبی کارت بانکی (۸۵٫۶ × ۵۳٫۹۸ میلی‌متر).
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(320.dp)
                    .height(202.dp)
                    .border(3.dp, c.primary, RoundedCornerShape(16.dp)),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!granted) {
                Text(
                    if (denied) "اجازهٔ دوربین داده نشد. می‌توانید عکس کارت را از گالری انتخاب کنید یا دستی وارد کنید."
                    else "برای اسکن کارت به دوربین نیاز است؛ عکس کارت هرگز ذخیره یا ارسال نمی‌شود.",
                    color = Color.White, fontSize = 15.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                )
                Text(
                    if (denied) "دوباره اجازه بده" else "اجازهٔ دوربین",
                    color = c.primaryContainer, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(role = Role.Button) { requestCamera() }.padding(8.dp),
                )
            } else {
                Text(
                    if (failed) "شماره خوانا نبود؛ کارت را نزدیک‌تر و در نور بهتر بگیرید یا دستی وارد کنید."
                    else "کارت را داخل کادر بگیرید",
                    color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.primary)
                    .clickable(role = Role.Button) { pickGalleryImage() },
                contentAlignment = Alignment.Center,
            ) { Text("انتخاب عکس کارت از گالری", color = c.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            Text(
                "ورود دستی",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(role = Role.Button) { onManualEntry() }.padding(8.dp),
            )
        }
    }
}

/** عکس گالری را کوچک‌شده می‌خواند تا حافظه پر نشود (مثل QrDecoder.decodeImage). */
private fun decodeBitmap(resolver: ContentResolver, uri: Uri, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

@Composable
private fun CardCameraPreview(onLines: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // فریم‌ها حداکثر هر ~۵۰۰ میلی‌ثانیه به OCR می‌روند و هم‌زمان فقط یک فریم در حال پردازش است.
    val busy = remember { AtomicBoolean(false) }
    val lastSubmit = remember { AtomicLong(0L) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val now = SystemClock.elapsedRealtime()
                if (busy.get() || now - lastSubmit.get() < 500L || !busy.compareAndSet(false, true)) {
                    image.close()
                    return@setAnalyzer
                }
                lastSubmit.set(now)
                CardTextRecognizer.recognizeImage(image, mainExecutor) { lines ->
                    busy.set(false)
                    if (lines.isNotEmpty()) onLines(lines)
                }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // دوربین در دسترس نیست؛ کاربر می‌تواند از گالری یا ورود دستی استفاده کند.
            }
        }, mainExecutor)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
