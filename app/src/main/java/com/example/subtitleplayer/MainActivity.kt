package com.example.subtitleplayer

import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.subtitleplayer.databinding.ActivityMainBinding
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager

    private var cues: List<SubtitleCue> = emptyList()
    private var currentCueIndex = -1
    private val cache = HashMap<String, String>()

    private var translator: Translator? = null
    private var currentTargetTag = TranslateLanguage.PERSIAN
    private var autoTranslateEnabled = false

    private val languages = listOf(
        "فارسی" to TranslateLanguage.PERSIAN,
        "انگلیسی" to TranslateLanguage.ENGLISH,
        "عربی" to TranslateLanguage.ARABIC,
        "فرانسوی" to TranslateLanguage.FRENCH
    )

    private var controlsVisible = false
    private var currentBrightness = 0.5f
    private var subtitleDragStartY = 0f
    private var subtitleDragStartMargin = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideIndicatorsRunnable = Runnable {
        binding.brightnessIndicator.visibility = View.GONE
        binding.volumeIndicator.visibility = View.GONE
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCurrentCue()
            updateSeekBar()
            handler.postDelayed(this, 200)
        }
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadVideo(it) }
    }
    private val pickSubtitleLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadSubtitle(it) }
    }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val width = binding.rootFrame.width
                val height = binding.rootFrame.height
                if (width == 0 || height == 0) return true
                val touchX = e1?.x ?: e2.x
                val delta = distanceY / height
                if (touchX < width / 2f) adjustBrightness(delta) else adjustVolume(delta)
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        currentBrightness = window.attributes.screenBrightness.let { if (it in 0f..1f) it else 0.5f }

        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player

        setupTouchHandling()
        setupControls()
        setupSettingsPanel()

        handler.post(tickRunnable)
    }

    private fun setupTouchHandling() {
        binding.touchOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                handler.postDelayed(hideIndicatorsRunnable, 600)
            }
            true
        }

        binding.subtitleContainer.setOnTouchListener { view, event ->
            val params = view.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    subtitleDragStartY = event.rawY
                    subtitleDragStartMargin = params.bottomMargin
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (subtitleDragStartY - event.rawY).roundToInt()
                    params.bottomMargin = (subtitleDragStartMargin + dy).coerceIn(20, binding.rootFrame.height - 100)
                    view.layoutParams = params
                }
            }
            true
        }
    }

    private fun adjustBrightness(delta: Float) {
        currentBrightness = (currentBrightness + delta * 2f).coerceIn(0.02f, 1f)
        val attrs = window.attributes
        attrs.screenBrightness = currentBrightness
        window.attributes = attrs
        binding.brightnessIndicator.visibility = View.VISIBLE
        binding.volumeIndicator.visibility = View.GONE
        binding.tvBrightnessValue.text = "☀ ${(currentBrightness * 100).roundToInt()}%"
        handler.removeCallbacks(hideIndicatorsRunnable)
    }

    private fun adjustVolume(delta: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVol = (curVol + delta * 2f * maxVol).roundToInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        binding.volumeIndicator.visibility = View.VISIBLE
        binding.brightnessIndicator.visibility = View.GONE
        binding.tvVolumeValue.text = "🔊 ${(newVol * 100 / maxVol)}%"
        handler.removeCallbacks(hideIndicatorsRunnable)
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        val vis = if (controlsVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = vis
        binding.bottomBar.visibility = vis
        if (!controlsVisible) binding.settingsPanel.visibility = View.GONE
    }

    private fun setupControls() {
        binding.btnPickVideo.setOnClickListener { pickVideoLauncher.launch("video/*") }
        binding.btnPickSubtitle.setOnClickListener { pickSubtitleLauncher.launch("*/*") }

        binding.btnSettings.setOnClickListener {
            binding.settingsPanel.visibility =
                if (binding.settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnPlayPause.setOnClickListener {
            player?.let {
                it.playWhenReady = !it.playWhenReady
                binding.btnPlayPause.setImageResource(
                    if (it.playWhenReady) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0) player?.seekTo(duration * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchAutoTranslate.setOnCheckedChangeListener { _, checked ->
            autoTranslateEnabled = checked
        }
    }

    private fun setupSettingsPanel() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
        binding.spinnerLang.adapter = adapter
        binding.spinnerLang.setSelection(0)

        binding.seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = (12 + progress).toFloat()
                binding.tvOriginal.textSize = size
                binding.tvTranslated.textSize = size - 2
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val colorMap = mapOf(
            binding.colorWhite.id to "#FFFFFF",
            binding.colorYellow.id to "#FFEB3B",
            binding.colorCyan.id to "#00E5FF",
            binding.colorGreen.id to "#69F0AE"
        )
        listOf(binding.colorWhite, binding.colorYellow, binding.colorCyan, binding.colorGreen).forEach { btn ->
            btn.setOnClickListener {
                binding.tvOriginal.setTextColor(android.graphics.Color.parseColor(colorMap[btn.id]))
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = true
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
    }

    // تنها نسخه‌ی loadSubtitle: تشخیص encoding قبل از پارس کردن
    private fun loadSubtitle(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val text = decodeSubtitleBytes(bytes)
            cues = SrtParser.parse(text)
            binding.tvStatus.text = "${cues.size} خط زیرنویس بارگذاری شد."
            cache.clear()
            currentCueIndex = -1
        }
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
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
        } else {
            binding.tvOriginal.text = cues[idx].text
            val targetTag = languages[binding.spinnerLang.selectedItemPosition].second
            val cached = cache["$idx|$targetTag"]
            binding.tvTranslated.text = cached ?: ""
            if (autoTranslateEnabled && cached == null) {
                translateCue(idx, targetTag)
            }
        }
    }

    private fun updateSeekBar() {
        val duration = player?.duration ?: 0L
        val position = player?.currentPosition ?: 0L
        if (duration > 0) {
            binding.seekBar.progress = ((position * 1000) / duration).toInt()
            binding.tvCurrentTime.text = formatTime(position)
            binding.tvDuration.text = formatTime(duration)
        }
    }

    private fun translateCue(index: Int, targetTag: String) {
        val key = "$index|$targetTag"
        val original = cues.getOrNull(index)?.text ?: return

        ensureTranslator(targetTag) { tr, ok ->
            if (!ok || tr == null) {
                binding.tvStatus.text = "دانلود مدل ترجمه ناموفق بود."
                return@ensureTranslator
            }
            tr.translate(original)
                .addOnSuccessListener { translated ->
                    cache[key] = translated
                    if (index == currentCueIndex) binding.tvTranslated.text = translated
                }
                .addOnFailureListener {
                    binding.tvStatus.text = "خطا در ترجمه: ${it.localizedMessage}"
                }
        }
    }

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
            .addOnFailureListener { callback(null, false) }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        translator?.close()
    }
}
