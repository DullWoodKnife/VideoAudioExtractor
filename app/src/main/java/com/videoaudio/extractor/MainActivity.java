package com.videoaudio.extractor;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面：视频音频提取器
 * 参照 apadog.com/video-converter.html 界面设计
 *
 * 功能流程：
 * 1. 选择视频文件
 * 2. 选择输出音频格式（MP3/AAC/WAV/FLAC/M4A）
 * 3. 设置音频质量参数（比特率、采样率）
 * 4. 开始提取音频
 * 5. 播放/分享/保存提取结果
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ========== UI 组件 ==========
    private ImageView ivVideoThumbnail;
    private TextView tvUploadHint;
    private LinearLayout layoutVideoInfo;
    private TextView tvVideoName;
    private TextView tvVideoDetail;
    private MaterialButton btnSelectVideo;
    private MaterialButton btnExtract;
    private MaterialButton btnStop;
    private Spinner spinnerBitrate;
    private Spinner spinnerSampleRate;
    private LinearLayout layoutProgress;
    private ProgressBar progressBar;
    private TextView tvProgressPercent;
    private TextView tvProgressStatus;
    private TextView tvProgressEta;
    private LinearLayout layoutComplete;
    private TextView tvOutputInfo;
    private MaterialButton btnPlay;
    private MaterialButton btnShare;
    private MaterialButton btnSave;
    private EditText etOutputFilename;
    private TextView tvFilenameExtension;
    private EditText etTrimStartHour;
    private EditText etTrimStartMin;
    private EditText etTrimStartSec;
    private EditText etTrimEndHour;
    private EditText etTrimEndMin;
    private EditText etTrimEndSec;

    // ========== 模式切换 ==========
    private static final int MODE_AUDIO = 0;
    private static final int MODE_VIDEO = 1;
    private int currentMode = MODE_AUDIO;

    private MaterialButton btnModeAudio;
    private MaterialButton btnModeVideo;
    private Spinner spinnerVideoFormat;
    private LinearLayout layoutVideoSection;
    private LinearLayout layoutFormatGrid;
    private TextView tvAudioFormatTitle;
    private TextView tvAudioFormatSubtitle;
    private TextView tvQualityTitle;
    private CardView cardQuality;
    private TextView tvTrimTitle;
    private TextView tvTrimSubtitle;
    private CardView cardTrim;

    // ========== 日志设置 ==========
    private SwitchMaterial switchLogEnabled;
    private MaterialButton btnExportLogs;
    private MaterialButton btnClearLogs;

    // ========== 格式卡片 ==========
    private final Map<String, LinearLayout> formatCards = new HashMap<>();
    private String selectedFormat = "mp3";
    private String selectedVideoFormat = "mp4";

    // ========== 视频信息 ==========
    private Uri pickedVideoUri;
    private File localInputFile;
    private File localOutputFile;

    // ========== 比特率与采样率映射 ==========
    private static final Map<String, Integer> BITRATE_MAP = new HashMap<>();
    private static final Map<String, Integer> SAMPLE_RATE_MAP = new HashMap<>();

    static {
        BITRATE_MAP.put("128 kbps（标准）", 128000);
        BITRATE_MAP.put("192 kbps（推荐）", 192000);
        BITRATE_MAP.put("256 kbps（高品质）", 256000);
        BITRATE_MAP.put("320 kbps（最高品质）", 320000);

        SAMPLE_RATE_MAP.put("44100 Hz（CD品质）", 44100);
        SAMPLE_RATE_MAP.put("48000 Hz（DVD品质）", 48000);
        SAMPLE_RATE_MAP.put("22050 Hz（语音）", 22050);
    }

    // ========== 视频格式映射 ==========
    private static final Map<String, String> VIDEO_FORMAT_MAP = new HashMap<>();
    static {
        VIDEO_FORMAT_MAP.put("MP4 (H.264) - 推荐", "mp4");
        VIDEO_FORMAT_MAP.put("MKV (H.264)", "mkv");
        VIDEO_FORMAT_MAP.put("MOV (H.264)", "mov");
        VIDEO_FORMAT_MAP.put("AVI (MPEG4)", "avi");
        VIDEO_FORMAT_MAP.put("WEBM (VP9)", "webm");
        VIDEO_FORMAT_MAP.put("FLV (H.264)", "flv");
    }

    // ========== 视频选择器 ==========
    // 使用 ACTION_OPEN_DOCUMENT 允许浏览所有文件（包括图库不识别的格式如 .vdat）
    private final ActivityResultLauncher<Intent> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Uri uri = result.getData().getData();
                if (uri == null) {
                    AppLog.w(TAG, "视频选择返回空 URI");
                    Toast.makeText(this, R.string.error_no_video, Toast.LENGTH_SHORT).show();
                    return;
                }
                pickedVideoUri = uri;
                AppLog.i(TAG, "视频已选择: " + uri);
                onVideoSelected(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(getApplicationContext());
        AppLog.i(TAG, "onCreate");
        setContentView(R.layout.activity_main);
        initViews();
        initFormatCards();
        initSpinners();
        initVideoFormatSpinner();
        initClickListeners();
        initLogSettings();
    }

    /**
     * 初始化所有 UI 视图
     */
    private void initViews() {
        ivVideoThumbnail = findViewById(R.id.iv_video_thumbnail);
        tvUploadHint = findViewById(R.id.tv_upload_hint);
        layoutVideoInfo = findViewById(R.id.layout_video_info);
        tvVideoName = findViewById(R.id.tv_video_name);
        tvVideoDetail = findViewById(R.id.tv_video_detail);
        btnSelectVideo = findViewById(R.id.btn_select_video);
        btnExtract = findViewById(R.id.btn_extract);
        btnStop = findViewById(R.id.btn_stop);
        spinnerBitrate = findViewById(R.id.spinner_bitrate);
        spinnerSampleRate = findViewById(R.id.spinner_sample_rate);
        layoutProgress = findViewById(R.id.layout_progress);
        progressBar = findViewById(R.id.progress_bar);
        tvProgressPercent = findViewById(R.id.tv_progress_percent);
        tvProgressStatus = findViewById(R.id.tv_progress_status);
        tvProgressEta = findViewById(R.id.tv_progress_eta);
        layoutComplete = findViewById(R.id.layout_complete);
        tvOutputInfo = findViewById(R.id.tv_output_info);
        btnPlay = findViewById(R.id.btn_play);
        btnShare = findViewById(R.id.btn_share);
        btnSave = findViewById(R.id.btn_save);
        etOutputFilename = findViewById(R.id.et_output_filename);
        tvFilenameExtension = findViewById(R.id.tv_filename_extension);
        etTrimStartHour = findViewById(R.id.et_trim_start_hour);
        etTrimStartMin = findViewById(R.id.et_trim_start_min);
        etTrimStartSec = findViewById(R.id.et_trim_start_sec);
        etTrimEndHour = findViewById(R.id.et_trim_end_hour);
        etTrimEndMin = findViewById(R.id.et_trim_end_min);
        etTrimEndSec = findViewById(R.id.et_trim_end_sec);

        // 模式切换组件
        btnModeAudio = findViewById(R.id.btn_mode_audio);
        btnModeVideo = findViewById(R.id.btn_mode_video);
        spinnerVideoFormat = findViewById(R.id.spinner_video_format);
        layoutVideoSection = findViewById(R.id.layout_video_section);
        layoutFormatGrid = findViewById(R.id.layout_format_grid);
        tvAudioFormatTitle = findViewById(R.id.tv_audio_format_title);
        tvAudioFormatSubtitle = findViewById(R.id.tv_audio_format_subtitle);
        tvQualityTitle = findViewById(R.id.tv_quality_title);
        cardQuality = findViewById(R.id.card_quality);
        tvTrimTitle = findViewById(R.id.tv_trim_title);
        tvTrimSubtitle = findViewById(R.id.tv_trim_subtitle);
        cardTrim = findViewById(R.id.card_trim);

        // 日志设置组件
        switchLogEnabled = findViewById(R.id.switch_log_enabled);
        btnExportLogs = findViewById(R.id.btn_export_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
    }

    /**
     * 初始化格式选择卡片
     */
    private void initFormatCards() {
        formatCards.put("mp3", findViewById(R.id.format_mp3));
        formatCards.put("aac", findViewById(R.id.format_aac));
        formatCards.put("wav", findViewById(R.id.format_wav));
        formatCards.put("flac", findViewById(R.id.format_flac));
        formatCards.put("m4a", findViewById(R.id.format_m4a));

        // 默认选中 MP3
        updateFormatSelection("mp3");
    }

    /**
     * 初始化下拉选择器
     */
    private void initSpinners() {
        // 比特率
        ArrayAdapter<CharSequence> bitrateAdapter = ArrayAdapter.createFromResource(
                this, R.array.bitrate_options,
                android.R.layout.simple_spinner_item);
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBitrate.setAdapter(bitrateAdapter);
        spinnerBitrate.setSelection(1); // 默认 192kbps

        // 采样率
        ArrayAdapter<CharSequence> sampleRateAdapter = ArrayAdapter.createFromResource(
                this, R.array.sample_rate_options,
                android.R.layout.simple_spinner_item);
        sampleRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSampleRate.setAdapter(sampleRateAdapter);
    }

    /**
     * 初始化视频格式下拉选择器
     */
    private void initVideoFormatSpinner() {
        ArrayAdapter<CharSequence> videoFormatAdapter = ArrayAdapter.createFromResource(
                this, R.array.video_format_options,
                android.R.layout.simple_spinner_item);
        videoFormatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVideoFormat.setAdapter(videoFormatAdapter);
    }

    /**
     * 初始化日志设置：同步开关状态，绑定事件
     */
    private void initLogSettings() {
        // 同步开关状态
        switchLogEnabled.setChecked(AppLog.isEnabled());

        // 日志开关
        switchLogEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppLog.setEnabled(isChecked);
            AppLog.i(TAG, "日志开关: " + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this,
                    isChecked ? R.string.log_status_on : R.string.log_status_off,
                    Toast.LENGTH_SHORT).show();
        });

        // 导出日志
        btnExportLogs.setOnClickListener(v -> {
            AppLog.i(TAG, "开始导出日志");
            new Thread(() -> {
                String exportPath = AppLog.exportLogs(this);
                runOnUiThread(() -> {
                    if (exportPath != null) {
                        Toast.makeText(this,
                                getString(R.string.log_export_success) + "\n" + exportPath,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.log_export_empty, Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        // 清除日志
        btnClearLogs.setOnClickListener(v -> {
            AppLog.clearLogs();
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 初始化点击事件
     */
    private void initClickListeners() {
        btnSelectVideo.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // 同时接受视频 MIME 类型和所有文件，确保能浏览到图库不识别的文件
            intent.setType("*/*");
            // 优先显示视频文件，但允许选择任意文件
            String[] mimeTypes = {"video/*", "application/octet-stream"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            pickVideoLauncher.launch(intent);
        });

        // 模式切换
        btnModeAudio.setOnClickListener(v -> switchMode(MODE_AUDIO));
        btnModeVideo.setOnClickListener(v -> switchMode(MODE_VIDEO));

        // 视频格式选择
        spinnerVideoFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                selectedVideoFormat = VIDEO_FORMAT_MAP.getOrDefault(selected, "mp4");
                if (currentMode == MODE_VIDEO) {
                    tvFilenameExtension.setText("." + selectedVideoFormat);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        btnExtract.setOnClickListener(v -> {
            if (currentMode == MODE_VIDEO) {
                startVideoConversion();
            } else {
                startExtraction();
            }
        });

        btnStop.setOnClickListener(v -> {
            AppLog.i(TAG, "用户点击强制停止");
            if (currentMode == MODE_VIDEO) {
                VideoConverter.cancel();
            } else {
                AudioExtractor.cancel();
            }
            Toast.makeText(this, R.string.status_cancelled, Toast.LENGTH_SHORT).show();
        });

        btnPlay.setOnClickListener(v -> playOutputFile());

        btnShare.setOnClickListener(v -> shareOutputFile());

        btnSave.setOnClickListener(v -> saveOutputFile());
    }

    /**
     * 格式卡片点击事件（XML onClick 绑定）
     */
    public void onFormatSelected(View view) {
        String format = "";
        int id = view.getId();
        if (id == R.id.format_mp3) format = "mp3";
        else if (id == R.id.format_aac) format = "aac";
        else if (id == R.id.format_wav) format = "wav";
        else if (id == R.id.format_flac) format = "flac";
        else if (id == R.id.format_m4a) format = "m4a";

        if (!format.isEmpty()) {
            AppLog.d(TAG, "选择音频格式: " + format);
            updateFormatSelection(format);
        }
    }

    /**
     * 更新格式选中状态
     */
    private void updateFormatSelection(String format) {
        selectedFormat = format;
        tvFilenameExtension.setText("." + format);
        for (Map.Entry<String, LinearLayout> entry : formatCards.entrySet()) {
            LinearLayout card = entry.getValue();
            if (entry.getKey().equals(format)) {
                card.setBackgroundResource(R.drawable.bg_format_card_selected);
            } else {
                card.setBackgroundResource(R.drawable.bg_format_card);
            }
        }
        updateExtractButtonState();
    }

    /**
     * 切换音频提取 / 视频转换模式
     */
    private void switchMode(int mode) {
        currentMode = mode;
        AppLog.i(TAG, "切换模式: " + (mode == MODE_AUDIO ? "音频提取" : "视频转换"));
        if (mode == MODE_AUDIO) {
            // 显示音频相关区域
            tvAudioFormatTitle.setVisibility(View.VISIBLE);
            tvAudioFormatSubtitle.setVisibility(View.VISIBLE);
            layoutFormatGrid.setVisibility(View.VISIBLE);
            tvQualityTitle.setVisibility(View.VISIBLE);
            cardQuality.setVisibility(View.VISIBLE);
            tvTrimTitle.setVisibility(View.VISIBLE);
            tvTrimSubtitle.setVisibility(View.VISIBLE);
            cardTrim.setVisibility(View.VISIBLE);
            // 隐藏视频格式区域
            layoutVideoSection.setVisibility(View.GONE);
            // 更新按钮样式
            btnModeAudio.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.color_primary)));
            btnModeAudio.setTextColor(getColor(android.R.color.white));
            btnModeVideo.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.bg_mode_unselected)));
            btnModeVideo.setTextColor(getColor(R.color.text_secondary));
            // 更新按钮文字和扩展名
            btnExtract.setText(R.string.btn_extract);
            tvFilenameExtension.setText("." + selectedFormat);
        } else {
            // 隐藏音频相关区域
            tvAudioFormatTitle.setVisibility(View.GONE);
            tvAudioFormatSubtitle.setVisibility(View.GONE);
            layoutFormatGrid.setVisibility(View.GONE);
            tvQualityTitle.setVisibility(View.GONE);
            cardQuality.setVisibility(View.GONE);
            tvTrimTitle.setVisibility(View.GONE);
            tvTrimSubtitle.setVisibility(View.GONE);
            cardTrim.setVisibility(View.GONE);
            // 显示视频格式区域
            layoutVideoSection.setVisibility(View.VISIBLE);
            // 更新按钮样式
            btnModeVideo.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.color_primary)));
            btnModeVideo.setTextColor(getColor(android.R.color.white));
            btnModeAudio.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.bg_mode_unselected)));
            btnModeAudio.setTextColor(getColor(R.color.text_secondary));
            // 更新按钮文字和扩展名
            btnExtract.setText(R.string.btn_convert);
            tvFilenameExtension.setText("." + selectedVideoFormat);
        }
    }

    /**
     * 视频选择后的处理
     */
    private void onVideoSelected(Uri uri) {
        // 显示视频信息
        tvUploadHint.setVisibility(View.GONE);
        layoutVideoInfo.setVisibility(View.VISIBLE);

        String displayName = queryDisplayName(uri);
        tvVideoName.setText(displayName);

        // 自动填入文件名（不含扩展名）
        String baseName = FileUtils.getBaseName(displayName);
        etOutputFilename.setText(baseName);

        // 获取视频时长和大小
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String resolution = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) + "x"
                    + retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            retriever.release();

            long durationMs = Long.parseLong(durationStr);
            String duration = formatDuration(durationMs);
            String fileSize = getFileSize(uri);

            tvVideoDetail.setText(String.format(Locale.getDefault(), "%s | %s | %s", duration, resolution, fileSize));
        } catch (Exception e) {
            AppLog.w(TAG, "获取视频元数据失败", e);
            tvVideoDetail.setText(getFileSize(uri));
        }

        // 尝试获取缩略图
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            android.graphics.Bitmap bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap != null) {
                ivVideoThumbnail.setImageBitmap(bitmap);
            }
            retriever.release();
        } catch (Exception ignored) {
        }

        updateExtractButtonState();
    }

    /**
     * 开始提取音频
     */
    private void startExtraction() {
        if (pickedVideoUri == null) {
            AppLog.w(TAG, "开始提取时未选择视频");
            Toast.makeText(this, R.string.error_no_video, Toast.LENGTH_SHORT).show();
            return;
        }

        AppLog.i(TAG, "开始提取音频, 格式: " + selectedFormat);
        setBusyState(true);
        layoutProgress.setVisibility(View.VISIBLE);
        layoutComplete.setVisibility(View.GONE);
        tvProgressStatus.setText(R.string.status_preparing);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        tvProgressPercent.setText("0%");
        tvProgressEta.setText("");

        new Thread(() -> {
            try {
                // 1. 复制视频到本地缓存
                runOnUiThread(() -> tvProgressStatus.setText(getString(R.string.status_preparing)));
                localInputFile = FileUtils.copyUriToCache(this, pickedVideoUri, "video_input");

                // 2. 构建输出文件路径
                String baseName = FileUtils.getBaseName(queryDisplayName(pickedVideoUri));

                // 检查用户是否自定义了文件名
                String customName = etOutputFilename.getText().toString().trim();
                if (!customName.isEmpty()) {
                    // 移除可能的手动输入扩展名，统一由格式决定
                    int dotIdx = customName.lastIndexOf('.');
                    if (dotIdx > 0) {
                        customName = customName.substring(0, dotIdx);
                    }
                    baseName = customName;
                }

                String extension = "." + selectedFormat;
                localOutputFile = new File(getExternalFilesDir(null), baseName + extension);

                // 3. 获取用户选择的参数
                String bitrateStr = (String) spinnerBitrate.getSelectedItem();
                String sampleRateStr = (String) spinnerSampleRate.getSelectedItem();
                int bitrate = BITRATE_MAP.getOrDefault(bitrateStr, 192000);
                int sampleRate = SAMPLE_RATE_MAP.getOrDefault(sampleRateStr, 44100);

                // 4. 获取用户设置的时间区间
                double startTimeSec = parseTrimTime(etTrimStartHour, etTrimStartMin, etTrimStartSec);
                double endTimeSec = parseTrimTime(etTrimEndHour, etTrimEndMin, etTrimEndSec);

                // 校验时间区间
                if (startTimeSec >= 0 && endTimeSec >= 0 && endTimeSec <= startTimeSec) {
                    runOnUiThread(() -> {
                        setBusyState(false);
                        layoutProgress.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this, "结束时间必须大于开始时间", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 5. 执行 FFmpeg 提取
                runOnUiThread(() -> {
                    tvProgressStatus.setText(getString(R.string.status_extracting));
                    progressBar.setIndeterminate(false);
                });

                AudioExtractor.Callback callback = new AudioExtractor.Callback() {
                    @Override
                    public void onProgress(int progress) {
                        runOnUiThread(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(progress);
                            tvProgressPercent.setText(progress + "%");
                        });
                    }

                    @Override
                    public void onEtaUpdate(String etaText) {
                        runOnUiThread(() -> {
                            tvProgressEta.setText(etaText);
                        });
                    }

                    @Override
                    public void onSuccess(File outputFile) {
                        AppLog.i(TAG, "音频提取成功: " + outputFile.getAbsolutePath()
                                + " (" + FileUtils.formatFileSize(outputFile.length()) + ")");
                        runOnUiThread(() -> {
                            setBusyState(false);
                            layoutProgress.setVisibility(View.GONE);
                            layoutComplete.setVisibility(View.VISIBLE);

                            String size = FileUtils.formatFileSize(outputFile.length());
                            tvOutputInfo.setText(String.format(Locale.getDefault(),
                                    "%s (%s)", outputFile.getName(), size));
                        });
                    }

                    @Override
                    public void onFailure(String message) {
                        AppLog.e(TAG, "音频提取失败: " + message);
                        runOnUiThread(() -> {
                            setBusyState(false);
                            layoutProgress.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this,
                                    message != null ? message : getString(R.string.error_ffmpeg_failed),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                };

                AudioExtractor.extractAudio(
                        localInputFile.getAbsolutePath(),
                        localOutputFile.getAbsolutePath(),
                        selectedFormat,
                        bitrate,
                        sampleRate,
                        startTimeSec,
                        endTimeSec,
                        callback
                );

            } catch (Exception e) {
                AppLog.e(TAG, "提取流程异常", e);
                runOnUiThread(() -> {
                    setBusyState(false);
                    layoutProgress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.error_copy_failed) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 开始视频格式转换
     */
    private void startVideoConversion() {
        if (pickedVideoUri == null) {
            AppLog.w(TAG, "开始转换时未选择视频");
            Toast.makeText(this, R.string.error_no_video, Toast.LENGTH_SHORT).show();
            return;
        }

        AppLog.i(TAG, "开始视频转换, 目标格式: " + selectedVideoFormat);
        setBusyState(true);
        layoutProgress.setVisibility(View.VISIBLE);
        layoutComplete.setVisibility(View.GONE);
        tvProgressStatus.setText(R.string.status_converting);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        tvProgressPercent.setText("0%");
        tvProgressEta.setText("");

        new Thread(() -> {
            try {
                // 1. 复制视频到本地缓存
                runOnUiThread(() -> tvProgressStatus.setText(getString(R.string.status_converting)));
                localInputFile = FileUtils.copyUriToCache(this, pickedVideoUri, "video_input");

                // 2. 构建输出文件路径
                String baseName = FileUtils.getBaseName(queryDisplayName(pickedVideoUri));

                String customName = etOutputFilename.getText().toString().trim();
                if (!customName.isEmpty()) {
                    int dotIdx = customName.lastIndexOf('.');
                    if (dotIdx > 0) {
                        customName = customName.substring(0, dotIdx);
                    }
                    baseName = customName;
                }

                String extension = "." + selectedVideoFormat;
                localOutputFile = new File(getExternalFilesDir(null), baseName + extension);

                // 3. 执行视频转换
                runOnUiThread(() -> {
                    tvProgressStatus.setText(getString(R.string.status_converting));
                    progressBar.setIndeterminate(false);
                });

                VideoConverter.Callback callback = new VideoConverter.Callback() {
                    @Override
                    public void onProgress(int progress) {
                        runOnUiThread(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(progress);
                            tvProgressPercent.setText(progress + "%");
                        });
                    }

                    @Override
                    public void onEtaUpdate(String etaText) {
                        runOnUiThread(() -> tvProgressEta.setText(etaText));
                    }

                    @Override
                    public void onSuccess(File outputFile) {
                        AppLog.i(TAG, "视频转换成功: " + outputFile.getAbsolutePath()
                                + " (" + FileUtils.formatFileSize(outputFile.length()) + ")");
                        runOnUiThread(() -> {
                            setBusyState(false);
                            layoutProgress.setVisibility(View.GONE);
                            layoutComplete.setVisibility(View.VISIBLE);

                            String size = FileUtils.formatFileSize(outputFile.length());
                            tvOutputInfo.setText(String.format(Locale.getDefault(),
                                    "%s (%s)", outputFile.getName(), size));
                        });
                    }

                    @Override
                    public void onFailure(String message) {
                        AppLog.e(TAG, "视频转换失败: " + message);
                        runOnUiThread(() -> {
                            setBusyState(false);
                            layoutProgress.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this,
                                    message != null ? message : getString(R.string.error_ffmpeg_failed),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                };

                VideoConverter.convertVideo(
                        localInputFile.getAbsolutePath(),
                        localOutputFile.getAbsolutePath(),
                        selectedVideoFormat,
                        callback
                );

            } catch (Exception e) {
                AppLog.e(TAG, "转换流程异常", e);
                runOnUiThread(() -> {
                    setBusyState(false);
                    layoutProgress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.error_copy_failed) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 播放输出文件
     */
    private void playOutputFile() {
        if (localOutputFile != null && localOutputFile.exists()) {
            AppLog.d(TAG, "播放文件: " + localOutputFile.getName());
            FileUtils.openFile(this, localOutputFile);
        }
    }

    /**
     * 分享输出文件
     */
    private void shareOutputFile() {
        if (localOutputFile != null && localOutputFile.exists()) {
            AppLog.d(TAG, "分享文件: " + localOutputFile.getName());
            FileUtils.shareFile(this, localOutputFile, null);
        }
    }

    /**
     * 保存输出文件到公共目录
     */
    private void saveOutputFile() {
        if (localOutputFile != null && localOutputFile.exists()) {
            String format = currentMode == MODE_VIDEO ? selectedVideoFormat : selectedFormat;
            AppLog.i(TAG, "保存文件到公共目录: " + localOutputFile.getName() + ", 格式: " + format);
            boolean saved = FileUtils.saveToPublicDirectory(this, localOutputFile, format);
            if (saved) {
                AppLog.i(TAG, "文件保存成功");
                Toast.makeText(this, "文件已保存到 Download 目录", Toast.LENGTH_SHORT).show();
            } else {
                AppLog.e(TAG, "文件保存失败");
                Toast.makeText(this, R.string.error_permission, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 更新提取按钮状态
     */
    private void updateExtractButtonState() {
        btnExtract.setEnabled(pickedVideoUri != null);
    }

    /**
     * 设置忙碌状态
     */
    private void setBusyState(boolean busy) {
        btnSelectVideo.setEnabled(!busy);
        btnExtract.setEnabled(!busy && pickedVideoUri != null);
        spinnerBitrate.setEnabled(!busy);
        spinnerSampleRate.setEnabled(!busy);
        spinnerVideoFormat.setEnabled(!busy);
        btnModeAudio.setEnabled(!busy);
        btnModeVideo.setEnabled(!busy);
        
        // 显示/隐藏强制停止按钮
        btnStop.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    /**
     * 查询文件显示名
     */
    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor == null) return "unknown";
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex < 0) return "unknown";
            if (!cursor.moveToFirst()) return "unknown";
            return cursor.getString(nameIndex);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取文件大小
     */
    private String getFileSize(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor == null) return "";
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (sizeIndex < 0) return "";
            if (!cursor.moveToFirst()) return "";
            long size = cursor.getLong(sizeIndex);
            return FileUtils.formatFileSize(size);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    /**
     * 解析时间区间输入框的值
     *
     * @return 秒数，如果所有字段都为空则返回 -1（表示不限制）
     */
    private double parseTrimTime(EditText etHour, EditText etMin, EditText etSec) {
        String hourStr = etHour.getText().toString().trim();
        String minStr = etMin.getText().toString().trim();
        String secStr = etSec.getText().toString().trim();

        // 所有字段都为空表示不限制
        if (hourStr.isEmpty() && minStr.isEmpty() && secStr.isEmpty()) {
            return -1;
        }

        int hour = hourStr.isEmpty() ? 0 : Integer.parseInt(hourStr);
        int min = minStr.isEmpty() ? 0 : Integer.parseInt(minStr);
        int sec = secStr.isEmpty() ? 0 : Integer.parseInt(secStr);

        return hour * 3600 + min * 60 + sec;
    }
}
