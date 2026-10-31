package io.github.quanbughunter.subzoro

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * Lượt nghe thứ hai: Vosk cho chữ hiện ngay, xong mỗi câu thì đưa đúng đoạn tiếng đó cho mô hình lớn hơn
 * (SenseVoice cho Trung/Anh — có dấu câu; GigaAM v2 cho Nga) để sửa lại câu cho chính xác.
 * Chạy bằng sherpa-onnx ngay trên máy.
 */
class Refiner private constructor(private val rec: OfflineRecognizer, val lang: String) {
    companion object {
        /** null nếu chưa tải mô hình chính xác cho ngôn ngữ này. */
        fun create(ctx: Context, lang: String): Refiner? {
            val p = Packs.accurate(lang) ?: return null
            if (!Packs.ready(ctx, p)) return null
            val dir = Packs.dir(ctx, p)
            val model = File(dir, "model.int8.onnx")
            val tokens = File(dir, "tokens.txt")
            // sherpa-onnx thoát cả app nếu thiếu file, nên phải kiểm tra trước
            if (!model.isFile || !tokens.isFile || model.length() < 1_000_000) return null
            val threads = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
            val mc = if (p === Packs.SENSEVOICE) {
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = model.absolutePath, language = lang, useInverseTextNormalization = true),
                    tokens = tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                )
            } else {
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                )
            }
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = mc,
                decodingMethod = "greedy_search"
            )
            return Refiner(OfflineRecognizer(null, cfg), lang)
        }
    }

    /** Kết quả nghe lại: chữ + từng token và giây bắt đầu của token (tính từ đầu đoạn tiếng). */
    class Result(val text: String, val tokens: Array<String>, val times: FloatArray)

    /** samples: tiếng 16 kHz, giá trị -1..1. */
    fun recognize(samples: FloatArray): Result {
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            rec.decode(s)
            val r = rec.getResult(s)
            // phòng thư viện trả mảng rỗng/null cho mốc giờ: khi đó chỉ dùng chữ
            @Suppress("USELESS_CAST")
            val tokens: Array<String> = (r.tokens as Array<String>?) ?: emptyArray()
            @Suppress("USELESS_CAST")
            val times: FloatArray = (r.timestamps as FloatArray?) ?: FloatArray(0)
            return Result(r.text.trim(), tokens, times)
        } finally {
            s.release()
        }
    }

    fun release() {
        try { rec.release() } catch (e: Throwable) { }
    }
}
