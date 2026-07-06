package com.videoaudio.extractor;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

/**
 * 音频提取工具类
 *
 * 进度方案：
 * 1. 同步执行 ffprobe 获取视频总时长（最可靠）
 * 2. statistics 回调实时更新进度（getTime 返回秒，getSpeed 返回倍速）
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

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
     * 从视频中提取音频（全量提取）
     */
    public static void extractAudio(String inputPath, String outputPath,
                                    String format, int bitrate, int sampleRate,
                                    Callback callback) {
        extractAudio(inputPath, outputPath, format, bitrate, sampleRate,
                -1, -1, callback);
    }

    /**
     * 从视频中提取音频（支持时间区间）
     *
     * @param inputPath    输入视频路径
     * @param outputPath   输出音频路径
     * @param format       目标格式
     * @param bitrate      比特率
     * @param sampleRate   采样率
     * @param startTimeSec 开始时间（秒），-1 表示从头开始
     * @param endTimeSec   结束时间（秒），-1 表示到结尾
     * @param callback     回调
     */
    public static void extractAudio(String inputPath, String outputPath,
                                    String format, int bitrate, int sampleRate,
                                    double startTimeSec, double endTimeSec,
                                    Callback callback) {
        Log.d(TAG, "开始提取音频, CPU核心数: " + CPU_CORES);
        if (startTimeSec >= 0) Log.d(TAG, "开始时间: " + startTimeSec + "秒");
        if (endTimeSec >= 0) Log.d(TAG, "结束时间: " + endTimeSec + "秒");
        String command = buildCommand(inputPath, outputPath, format, bitrate, sampleRate,
                startTimeSec, endTimeSec);
        Log.d(TAG, "FFmpeg command: " + command);

        // 同步获取视频总时长（在后台线程调用，不会阻塞 UI）
        double durationSec = getDurationSync(inputPath);
        Log.d(TAG, "视频时长: " + durationSec + "秒");

        // 计算实际提取区间时长（用于进度计算）
        double extractDurationSec = durationSec;
        if (startTimeSec >= 0 && endTimeSec >= 0 && endTimeSec > startTimeSec) {
            extractDurationSec = endTimeSec - startTimeSec;
        } else if (startTimeSec >= 0 && durationSec > startTimeSec) {
            extractDurationSec = durationSec - startTimeSec;
        } else if (endTimeSec >= 0 && endTimeSec <= durationSec) {
            extractDurationSec = endTimeSec;
        }
        Log.d(TAG, "提取区间时长: " + extractDurationSec + "秒");

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
            // 仅输出日志，不做进度解析
            Log.v(TAG, log.getMessage());
        }, statistics -> {
            try {
                double currentTime = statistics.getTime();
                double speed = statistics.getSpeed();

                Log.v(TAG, String.format("statistics: time=%.2f, speed=%.2f",
                        currentTime, speed));

                if (currentTime > 0 && extractDurationSec > 0) {
                    int progress = (int) Math.min(100, (currentTime * 100.0 / extractDurationSec));
                    callback.onProgress(progress);

                    if (speed > 0) {
                        String eta = formatEta(currentTime, extractDurationSec, speed);
                        if (eta != null) callback.onEtaUpdate(eta);
                    }
                } else if (currentTime > 0 && extractDurationSec <= 0) {
                    // 时长未知：根据已处理时间估算进度（假设最大 10 分钟）
                    int estimatedProgress = (int) Math.min(95, (currentTime * 100.0 / 600.0));
                    callback.onProgress(estimatedProgress);
                    callback.onEtaUpdate(String.format("已处理 %.0f秒...", currentTime));
                }
            } catch (Exception e) {
                Log.w(TAG, "statistics回调异常: " + e.getMessage());
            }
        });
    }

    /**
     * 同步执行 ffprobe 获取视频时长（秒）
     *
     * @return 视频时长（秒），获取失败返回 0
     */
    private static double getDurationSync(String inputPath) {
        String probeCommand = String.format(
                "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 %s",
                quotePath(inputPath));

        try {
            FFmpegSession probeSession = FFmpegKit.execute(probeCommand);
            if (ReturnCode.isSuccess(probeSession.getReturnCode())) {
                String output = probeSession.getOutput();
                if (output != null && !output.isEmpty()) {
                    double duration = Double.parseDouble(output.trim());
                    Log.d(TAG, "ffprobe 获取时长成功: " + duration + "秒");
                    return duration;
                }
            } else {
                Log.w(TAG, "ffprobe 返回码: " + probeSession.getReturnCode());
            }
        } catch (Exception e) {
            Log.w(TAG, "ffprobe 执行异常: " + e.getMessage());
        }

        Log.w(TAG, "ffprobe 获取时长失败，进度将使用估算值");
        return 0;
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
     * 构建 FFmpeg 命令（支持时间区间）
     *
     * 时间区间通过 -ss（开始）和 -to（结束）实现，放在 -i 之前实现快速定位
     */
    private static String buildCommand(String inputPath, String outputPath,
                                       String format, int bitrate, int sampleRate,
                                       double startTimeSec, double endTimeSec) {
        String quotedInput = quotePath(inputPath);
        String quotedOutput = quotePath(outputPath);
        String threads = String.valueOf(CPU_CORES);

        // 构建时间参数
        String timeParams = "";
        if (startTimeSec >= 0) {
            timeParams += String.format("-ss %.3f ", startTimeSec);
        }
        if (endTimeSec >= 0) {
            timeParams += String.format("-to %.3f ", endTimeSec);
        }

        switch (format.toLowerCase()) {
            case "mp3":
                return String.format(
                        "-y -threads %s %s-i %s -map 0:a -vn -c:a libmp3lame -b:a %d -ar %d -preset fast %s",
                        threads, timeParams, quotedInput, bitrate, sampleRate, quotedOutput);

            case "aac":
                return String.format(
                        "-y -threads %s %s-i %s -map 0:a -vn -c:a aac -b:a %d -ar %d -movflags +faststart %s",
                        threads, timeParams, quotedInput, bitrate, sampleRate, quotedOutput);

            case "wav":
                return String.format(
                        "-y -threads %s %s-i %s -map 0:a -vn -c:a pcm_s16le -ar %d %s",
                        threads, timeParams, quotedInput, sampleRate, quotedOutput);

            case "flac":
                return String.format(
                        "-y -threads %s %s-i %s -map 0:a -vn -c:a flac -ar %d -compression_level 1 %s",
                        threads, timeParams, quotedInput, sampleRate, quotedOutput);

            case "m4a":
                return String.format(
                        "-y -threads %s %s-i %s -map 0:a -vn -c:a aac -b:a %d -ar %d -movflags +faststart %s",
                        threads, timeParams, quotedInput, bitrate, sampleRate, quotedOutput);

            default:
                return String.format(
                        "-y -threads %s %s-i %s -map 0:a -vn -c:a libmp3lame -b:a %d -ar %d -preset fast %s",
                        threads, timeParams, quotedInput, bitrate, sampleRate, quotedOutput);
        }
    }

    private static String quotePath(String path) {
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }
}
