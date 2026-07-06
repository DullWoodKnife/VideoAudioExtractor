package com.videoaudio.extractor;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音频提取工具类
 *
 * 进度方案：
 * 1. statistics 回调为主（已确认 com.mrljdx fork 完整支持 getTime/getSpeed）
 * 2. log 回调中解析 Duration: 获取总时长（备用）
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    // 匹配 FFmpeg 输入探测阶段的 Duration: HH:MM:SS.MM
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration: (\\d{1,2}):(\\d{2}):(\\d{2})\\.(\\d+)");

    /**
     * 提取音频的回调接口
     */
    public interface Callback {
        void onProgress(int progress);
        void onEtaUpdate(String etaText);
        void onSuccess(File outputFile);
        void onFailure(String message);
    }

    /**
     * 从视频中提取音频
     */
    public static void extractAudio(String inputPath, String outputPath,
                                    String format, int bitrate, int sampleRate,
                                    Callback callback) {
        Log.d(TAG, "开始提取音频, CPU核心数: " + CPU_CORES);
        String command = buildCommand(inputPath, outputPath, format, bitrate, sampleRate);
        Log.d(TAG, "FFmpeg command: " + command);

        final double[] durationSec = {0};

        FFmpegKit.executeAsync(command, session -> {
            Log.d(TAG, "FFmpeg 完成, Return code: " + session.getReturnCode());

            if (ReturnCode.isSuccess(session.getReturnCode())) {
                callback.onProgress(100);
                callback.onEtaUpdate("已完成");
                File outputFile = new File(outputPath);
                if (outputFile.exists()) {
                    callback.onSuccess(outputFile);
                } else {
                    callback.onFailure("输出文件未生成");
                }
            } else {
                String failMsg = "FFmpeg 执行失败 (code: " + session.getReturnCode() + ")";
                String logs = session.getAllLogsAsString();
                if (logs != null && logs.length() > 200) {
                    failMsg += "\n" + logs.substring(logs.length() - 200);
                }
                callback.onFailure(failMsg);
            }
        }, log -> {
            // 从日志中捕获视频总时长
            try {
                String message = log.getMessage();
                if (message == null || durationSec[0] > 0) return;

                Matcher matcher = DURATION_PATTERN.matcher(message);
                if (matcher.find()) {
                    try {
                        int h = Integer.parseInt(matcher.group(1));
                        int m = Integer.parseInt(matcher.group(2));
                        int s = Integer.parseInt(matcher.group(3));
                        String frac = matcher.group(4);
                        double fracVal = frac.length() <= 2
                                ? Integer.parseInt(frac) / Math.pow(10, frac.length())
                                : Double.parseDouble("0." + frac);
                        durationSec[0] = h * 3600 + m * 60 + s + fracVal;
                        Log.d(TAG, "从日志获取视频时长: " + durationSec[0] + "秒");
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "解析日志异常: " + e.getMessage());
            }
        }, statistics -> {
            // statistics 回调：计算进度和 ETA
            try {
                double currentTime = statistics.getTime();
                double speed = statistics.getSpeed();

                Log.v(TAG, String.format("statistics: time=%.2f, speed=%.2f, bitrate=%.2f",
                        currentTime, speed, statistics.getBitrate()));

                if (durationSec[0] > 0 && currentTime > 0) {
                    int progress = (int) Math.min(100, (currentTime * 100.0 / durationSec[0]));
                    callback.onProgress(progress);

                    if (speed > 0) {
                        String eta = formatEta(currentTime, durationSec[0], speed);
                        if (eta != null) callback.onEtaUpdate(eta);
                    }
                } else if (currentTime > 0 && durationSec[0] <= 0) {
                    // 时长未知时仍显示处理时间
                    callback.onEtaUpdate(String.format("已处理 %.0f秒...", currentTime));
                }
            } catch (Exception e) {
                Log.w(TAG, "statistics回调异常: " + e.getMessage());
            }
        });
    }

    /**
     * 格式化 ETA 文字
     */
    private static String formatEta(double processedSec, double totalSec, double speed) {
        if (processedSec >= totalSec) return "即将完成";

        double remainSec = (totalSec - processedSec) / speed;
        if (remainSec < 1) return "即将完成";
        if (remainSec < 60) return String.format("剩余 %d秒", (int) remainSec);
        if (remainSec < 3600) {
            int min = (int) (remainSec / 60);
            int sec = (int) (remainSec % 60);
            return String.format("剩余 %d分%02d秒", min, sec);
        }
        int hour = (int) (remainSec / 3600);
        int min = (int) ((remainSec % 3600) / 60);
        return String.format("剩余 %d小时%d分", hour, min);
    }

    /**
     * 构建 FFmpeg 命令
     */
    private static String buildCommand(String inputPath, String outputPath,
                                       String format, int bitrate, int sampleRate) {
        String quotedInput = quotePath(inputPath);
        String quotedOutput = quotePath(outputPath);
        String threads = String.valueOf(CPU_CORES);

        switch (format.toLowerCase()) {
            case "mp3":
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a libmp3lame -b:a %d -ar %d -preset fast %s",
                        threads, quotedInput, bitrate, sampleRate, quotedOutput);

            case "aac":
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a aac -b:a %d -ar %d -movflags +faststart %s",
                        threads, quotedInput, bitrate, sampleRate, quotedOutput);

            case "wav":
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a pcm_s16le -ar %d %s",
                        threads, quotedInput, sampleRate, quotedOutput);

            case "flac":
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a flac -ar %d -compression_level 1 %s",
                        threads, quotedInput, sampleRate, quotedOutput);

            case "m4a":
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a aac -b:a %d -ar %d -movflags +faststart %s",
                        threads, quotedInput, bitrate, sampleRate, quotedOutput);

            default:
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a libmp3lame -b:a %d -ar %d -preset fast %s",
                        threads, quotedInput, bitrate, sampleRate, quotedOutput);
        }
    }

    private static String quotePath(String path) {
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }
}
