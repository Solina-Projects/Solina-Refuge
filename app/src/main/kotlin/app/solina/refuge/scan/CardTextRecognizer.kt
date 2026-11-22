package app.solina.refuge.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * تشخیص متن روی دستگاه با ML Kit Text Recognition (مدل همراه، نسخهٔ
 * `com.google.mlkit:text-recognition`).
 *
 * حریم خصوصی: مدل داخل خود برنامه است؛ هیچ تصویری ذخیره، آپلود یا به هیچ
 * سروری فرستاده نمی‌شود، هیچ فراخوانی شبکه‌ای در این مسیر وجود ندارد و فریم
 * دوربین فقط در حافظه پردازش و همان‌جا رها می‌شود.
 *
 * فریم دوربین عمداً از صفحه‌های YUV به Bitmap تبدیل می‌شود، نه از
 * ImageProxy.image — آن API آزمایشی است (ExperimentalGetImage) و lint پروژه
 * (abortOnError) هیچ‌یک از شکل‌های opt-in آن را نمی‌پذیرد.
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
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = runCatching { imageProxyToBitmap(image) }.getOrNull()
        image.close()
        if (bitmap == null) {
            executor.execute { onResult(emptyList()) }
            return
        }
        val input = InputImage.fromBitmap(bitmap, rotation)
        recognizer.process(input)
            .addOnSuccessListener(executor) { visionText ->
                onResult(visionText.textBlocks.flatMap { block -> block.lines.map { it.text } })
            }
            .addOnFailureListener(executor) { onResult(emptyList()) }
            .addOnCompleteListener { bitmap.recycle() }
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

    /**
     * تبدیل فریم YUV_420_888 به Bitmap از راه NV21 و JPEG.
     * سطرهای Y ممکن است padding داشته باشند (rowStride بزرگ‌تر از width) و
     * صفحه‌های رنگی pixelStride دلخواه؛ هر دو رعایت می‌شود. NV21 یعنی
     * صفحهٔ Y و بعد V و U درهم‌تنیده (اول V، بعد U).
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || image.planes.size < 3) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val nv21 = ByteArray(width * height * 3 / 2)
        for (row in 0 until height) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(nv21, row * width, width)
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        var outPos = width * height
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                nv21[outPos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[outPos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val stream = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, stream)) return null
        val jpeg = stream.toByteArray()
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    /** آزاد کردن recognizer (مثلاً هنگام خروج از برنامه)؛ فراخوانی‌اش اختیاری است. */
    fun close() {
        runCatching { recognizer.close() }
    }
}
