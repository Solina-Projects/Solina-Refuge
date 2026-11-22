// ImageProxy.image نیاز به opt-in دارد (ExperimentalGetImage)؛ lint فقط opt-inِ
// سطح فایل را می‌پذیرد، نه @OptIn روی تابع. حاشیه‌نویسیِ @file باید پیش از package بیاید.
@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package app.solina.refuge.scan

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/**
 * تشخیص متن روی دستگاه با ML Kit Text Recognition (مدل همراه، نسخهٔ
 * `com.google.mlkit:text-recognition`).
 *
 * حریم خصوصی: مدل داخل خود برنامه است؛ هیچ تصویری ذخیره، آپلود یا به هیچ
 * سروری فرستاده نمی‌شود، هیچ فراخوانی شبکه‌ای در این مسیر وجود ندارد و فریم
 * دوربین فقط در حافظه پردازش و همان‌جا رها می‌شود.
 */
object CardTextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * یک فریم دوربین را تشخیص می‌دهد و خط‌های متن را برمی‌گرداند.
     * [image] در همهٔ مسیرها (موفق/ناموفق) بسته می‌شود؛ [onResult] روی [executor]
     * صدا زده می‌شود. در صورت خطا، لیست خالی برمی‌گردد تا صفحهٔ اسکن بتواند
     * ورودی دستی را پیشنهاد کند.
     */
    fun recognizeImage(image: ImageProxy, executor: Executor, onResult: (List<String>) -> Unit) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            executor.execute { onResult(emptyList()) }
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener(executor) { visionText ->
                onResult(visionText.textBlocks.flatMap { block -> block.lines.map { it.text } })
            }
            .addOnFailureListener(executor) { onResult(emptyList()) }
            .addOnCompleteListener { image.close() }
    }

    /** تشخیص متن یک عکس گالری (بیت‌مپِ کوچک‌شده)؛ بیت‌مپ پس از کار بازیافت می‌شود. */
    fun recognizeBitmap(bitmap: Bitmap, executor: Executor, onResult: (List<String>) -> Unit) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener(executor) { visionText ->
                onResult(visionText.textBlocks.flatMap { block -> block.lines.map { it.text } })
            }
            .addOnFailureListener(executor) { onResult(emptyList()) }
            .addOnCompleteListener { bitmap.recycle() }
    }

    /** آزاد کردن recognizer (مثلاً هنگام خروج از برنامه)؛ فراخوانی‌اش اختیاری است. */
    fun close() {
        runCatching { recognizer.close() }
    }
}
