package com.example.subtitleplayer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.example.subtitleplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null

    private var cues: List<SubtitleCue> = emptyList()
    private var currentCueIndex = -1
    private val cache = HashMap<String, String>()

    private var translator: Translator? = null
    private var currentTargetTag = TranslateLanguage.PERSIAN

    // زبان‌های قابل انتخاب برای ترجمه (منبع: انگلیسی فرض شده)
    private val languages = listOf(
        "فارسی" to TranslateLanguage.PERSIAN,
        "انگلیسی" to TranslateLanguage.ENGLISH,
        "عربی" to TranslateLanguage.ARABIC,
        "فرانسوی" to TranslateLanguage.FRENCH
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCurrentCue()
            handler.postDelayed(this, 200)
        }
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadVideo(it) }
    }

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadSubtitle(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player

        binding.btnPickVideo.setOnClickListener { pickVideoLauncher.launch("video/*") }
        binding.btnPickSubtitle.setOnClickListener { pickSubtitleLauncher.launch("*/*") }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.first }
        )
        binding.spinnerLang.adapter = adapter
        binding.spinnerLang.setSelection(0) // فارسی به‌صورت پیش‌فرض

        binding.btnTranslate.isEnabled = false
        binding.btnTranslate.setOnClickListener { translateCurrentCue() }

        handler.post(tickRunnable)
    }

    private fun loadVideo(uri: Uri) {
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun loadSubtitle(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { input ->
            val text = input.bufferedReader().readText()
            cues = SrtParser.parse(text)
            binding.tvStatus.text = "${cues.size} خط زیرنویس بارگذاری شد."
            cache.clear()
            currentCueIndex = -1
        }
    }

    private fun updateCurrentCue() {
        val pos = player?.currentPosition ?: return
        val idx = cues.indexOfFirst { pos in it.startMs..it.endMs }
        if (idx == currentCueIndex) return
        currentCueIndex = idx

        if (idx == -1) {
            binding.tvOriginal.text = ""
            binding.tvTranslated.text = ""
            binding.btnTranslate.isEnabled = false
        } else {
            binding.tvOriginal.text = cues[idx].text
            val targetTag = languages[binding.spinnerLang.selectedItemPosition].second
            binding.tvTranslated.text = cache["$idx|$targetTag"] ?: ""
            binding.btnTranslate.isEnabled = true
        }
    }

    private fun translateCurrentCue() {
        if (currentCueIndex == -1) return
        val targetTag = languages[binding.spinnerLang.selectedItemPosition].second
        val key = "$currentCueIndex|$targetTag"

        cache[key]?.let {
            binding.tvTranslated.text = it
            return
        }

        val original = cues[currentCueIndex].text
        binding.tvStatus.text = "در حال ترجمه…"
        binding.btnTranslate.isEnabled = false

        ensureTranslator(targetTag) { translator, ok ->
            if (!ok || translator == null) {
                binding.tvStatus.text = "دانلود مدل ترجمه ناموفق بود (اتصال اینترنت را بررسی کنید)."
                binding.btnTranslate.isEnabled = true
                return@ensureTranslator
            }
            translator.translate(original)
                .addOnSuccessListener { translated ->
                    cache[key] = translated
                    binding.tvTranslated.text = translated
                    binding.tvStatus.text = ""
                    binding.btnTranslate.isEnabled = true
                }
                .addOnFailureListener {
                    binding.tvStatus.text = "خطا در ترجمه: ${it.localizedMessage}"
                    binding.btnTranslate.isEnabled = true
                }
        }
    }

    // منبع را انگلیسی فرض می‌کنیم؛ مدل موردنیاز را در صورت نبود دانلود می‌کند
    private fun ensureTranslator(targetTag: String, callback: (Translator?, Boolean) -> Unit) {
        if (translator != null && currentTargetTag == targetTag) {
            callback(translator, true)
            return
        }
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetTag)
            .build()
        val newTranslator = Translation.getClient(options)
        newTranslator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator = newTranslator
                currentTargetTag = targetTag
                callback(newTranslator, true)
            }
            .addOnFailureListener {
                callback(null, false)
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        player?.release()
        translator?.close()
    }
}
