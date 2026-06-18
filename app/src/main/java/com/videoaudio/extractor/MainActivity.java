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

import com.google.android.material.button.MaterialButton;

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

    // ========== UI 组件 ==========
    private ImageView ivVideoThumbnail;
    private TextView tvUploadHint;
    private LinearLayout layoutVideoInfo;
    private TextView tvVideoName;
    private TextView tvVideoDetail;
    private MaterialButton btnSelectVideo;
    private MaterialButton btnExtract;
    private Spinner spinnerBitrate;
    private Spinner spinnerSampleRate;
    private LinearLayout layoutProgress;
    private ProgressBar progressBar;
    private TextView tvProgressPercent;
    private TextView tvProgressStatus;
    private LinearLayout layoutComplete;
    private TextView tvOutputInfo;
    private MaterialButton btnPlay;
    private MaterialButton btnShare;
    private MaterialButton btnSave;
    private EditText etOutputFilename;
    private TextView tvFilenameExtension;

    // ========== 格式卡片 ==========
    private final Map<String, LinearLayout> formatCards = new HashMap<>();
    private String selectedFormat = "mp3";

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

    // ========== 视频选择器 ==========
    // 使用 ACTION_OPEN_DOCUMENT 允许浏览所有文件（包括图库不识别的格式如 .vdat）
    private final ActivityResultLauncher<Intent> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Uri uri = result.getData().getData();
                if (uri == null) {
                    Toast.makeText(this, R.string.error_no_video, Toast.LENGTH_SHORT).show();
                    return;
                }
                pickedVideoUri = uri;
                onVideoSelected(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        initFormatCards();
        initSpinners();
        initClickListeners();
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
        spinnerBitrate = findViewById(R.id.spinner_bitrate);
        spinnerSampleRate = findViewById(R.id.spinner_sample_rate);
        layoutProgress = findViewById(R.id.layout_progress);
        progressBar = findViewById(R.id.progress_bar);
        tvProgressPercent = findViewById(R.id.tv_progress_percent);
        tvProgressStatus = findViewById(R.id.tv_progress_status);
        layoutComplete = findViewById(R.id.layout_complete);
        tvOutputInfo = findViewById(R.id.tv_output_info);
        btnPlay = findViewById(R.id.btn_play);
        btnShare = findViewById(R.id.btn_share);
        btnSave = findViewById(R.id.btn_save);
        etOutputFilename = findViewById(R.id.et_output_filename);
        tvFilenameExtension = findViewById(R.id.tv_filename_extension);
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

        btnExtract.setOnClickListener(v -> startExtraction());

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
            Toast.makeText(this, R.string.error_no_video, Toast.LENGTH_SHORT).show();
            return;
        }

        setBusyState(true);
        layoutProgress.setVisibility(View.VISIBLE);
        layoutComplete.setVisibility(View.GONE);
        tvProgressStatus.setText(R.string.status_preparing);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        tvProgressPercent.setText("0%");

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

                // 4. 执行 FFmpeg 提取
                runOnUiThread(() -> {
                    tvProgressStatus.setText(getString(R.string.status_extracting));
                    progressBar.setIndeterminate(false);
                });

                AudioExtractor.Callback callback = new AudioExtractor.Callback() {
                    @Override
                    public void onProgress(int progress) {
                        runOnUiThread(() -> {
                            progressBar.setProgress(progress);
                            tvProgressPercent.setText(progress + "%");
                        });
                    }

                    @Override
                    public void onSuccess(File outputFile) {
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
                        callback
                );

            } catch (Exception e) {
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
            FileUtils.openFile(this, localOutputFile);
        }
    }

    /**
     * 分享输出文件
     */
    private void shareOutputFile() {
        if (localOutputFile != null && localOutputFile.exists()) {
            FileUtils.shareFile(this, localOutputFile, "audio/*");
        }
    }

    /**
     * 保存输出文件到公共目录
     */
    private void saveOutputFile() {
        if (localOutputFile != null && localOutputFile.exists()) {
            boolean saved = FileUtils.saveToPublicDirectory(this, localOutputFile, selectedFormat);
            if (saved) {
                Toast.makeText(this, "文件已保存到 Download 目录", Toast.LENGTH_SHORT).show();
            } else {
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
}
