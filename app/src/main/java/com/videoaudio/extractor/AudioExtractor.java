package com.videoaudio.extractor;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.Level;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音频提取工具类
 *
 * 进度方案：从 FFmpeg 执行日志中提取 Duration 和 time= 来计算进度
 * - 不依赖 ffprobe 预查询（某些文件格式 ffprobe 可能失败）
 * - 不依赖 statistics 回调（某些 fork 不可靠）
 * - 仅依赖 LogCallback（所有版本都支持）
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    // 匹配 FFmpeg 启动时的输入信息行：Duration: HH:MM:SS.MM
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration: (\\d{1,2}):(\\d{2}):(\\d{2})\\.(\\d+)");

    // 匹配 FFmpeg 进度日志中的 time=HH:MM:SS.xx
    private static final Pattern TIME_PATTERN = Pattern.compile("time=(\\d{1,2}):(\\d{2}):(\\d{2})\\.(\\d+)");

    // 匹配 FFmpeg 日志中的 speed= 字段
    private static final Pattern SPEED_PATTERN = Pattern.compile("speed=([\\d.]+)x");

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

        // 在 log 回调中捕获视频总时长
        final double[] durationSec = {0};

        FFmpegKit.executeAsync(command, session -> {
            Log.d(TAG, "FFmpeg session completed. Return code: " + session.getReturnCode());

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
            try {
                String message = log.getMessage();
                if (message == null) return;

                Level level = log.getLevel();

                // 1. 从输入探测阶段捕获 Duration
                if (durationSec[0] <= 0 && level == Level.AV_LOG_INFO) {
                    Matcher durationMatcher = DURATION_PATTERN.matcher(message);
                    if (durationMatcher.find()) {
                        try {
                            int h = Integer.parseInt(durationMatcher.group(1));
                            int m = Integer.parseInt(durationMatcher.group(2));
                            int s = Integer.parseInt(durationMatcher.group(3));
                            String frac = durationMatcher.group(4);
                            double fracVal = frac.length() <= 2
                                    ? Integer.parseInt(frac) / Math.pow(10, frac.length())
                                    : Double.parseDouble("0." + frac);
                            durationSec[0] = h * 3600 + m * 60 + s + fracVal;
                            Log.d(TAG, "从日志获取视频时长: " + durationSec[0] + "秒");
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "解析 Duration 失败");
                        }
                    }
                }

                // 2. 从进度行计算进度百分比和 ETA
                int timeIdx = message.indexOf("time=");
                if (timeIdx >= 0 && durationSec[0] > 0) {
                    Matcher timeMatcher = TIME_PATTERN.matcher(message);
                    if (timeMatcher.find(timeIdx)) {
                        int progress = parseProgress(timeMatcher, durationSec[0]);
                        if (progress >= 0 && progress <= 100) {
                            callback.onProgress(progress);
                        }

                        String eta = parseEta(timeMatcher, message, durationSec[0]);
                        if (eta != null) {
                            callback.onEtaUpdate(eta);
                        }
                    }
                }
            } catch (Exception e) {
                // 防止回调异常中断 FFmpeg 执行
                Log.w(TAG, "解析日志异常: " + e.getMessage());
            }
        }, statistics -> {
            // 备用：statistics 回调（部分版本可能支持）
            if (durationSec[0] > 0 && statistics.getTime() > 0) {
                int progress = (int) Math.min(100,
                        (statistics.getTime() * 100.0 / durationSec[0]));
                callback.onProgress(progress);
            }
        });
    }

    /**
     * 根据已匹配的 time= 数据计算进度
     */
    private static int parseProgress(Matcher timeMatcher, double totalDuration) {
        try {
            int h = Integer.parseInt(timeMatcher.group(1));
            int m = Integer.parseInt(timeMatcher.group(2));
            int s = Integer.parseInt(timeMatcher.group(3));
            String frac = timeMatcher.group(4);
            double fracVal = frac.length() <= 2
                    ? Integer.parseInt(frac) / Math.pow(10, frac.length())
                    : Double.parseDouble("0." + frac);
            double current = h * 3600 + m * 60 + s + fracVal;
            return (int) Math.min(100, (current * 100.0 / totalDuration));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 根据已匹配的 time= 和 speed= 数据计算 ETA
     */
    private static String parseEta(Matcher timeMatcher, String fullText, double totalDuration) {
        int speedIdx = fullText.indexOf("speed=");
        if (speedIdx < 0) return null;

        Matcher speedMatcher = SPEED_PATTERN.matcher(fullText);
        if (!speedMatcher.find(speedIdx)) return null;

        try {
            int h = Integer.parseInt(timeMatcher.group(1));
            int m = Integer.parseInt(timeMatcher.group(2));
            int s = Integer.parseInt(timeMatcher.group(3));
            String frac = timeMatcher.group(4);
            double fracVal = frac.length() <= 2
                    ? Integer.parseInt(frac) / Math.pow(10, frac.length())
                    : Double.parseDouble("0." + frac);
            double processed = h * 3600 + m * 60 + s + fracVal;

            double speed = Double.parseDouble(speedMatcher.group(1));
            if (speed <= 0 || processed <= 0 || processed >= totalDuration) {
                return processed >= totalDuration ? "即将完成" : null;
            }

            double remain = (totalDuration - processed) / speed;
            if (remain < 1) return "即将完成";
            if (remain < 60) return String.format("剩余 %d秒", (int) remain);
            if (remain < 3600) {
                int min = (int) (remain / 60);
                int sec = (int) (remain % 60);
                return String.format("剩余 %d分%02d秒", min, sec);
            }
            int hour = (int) (remain / 3600);
            int min = (int) ((remain % 3600) / 60);
            return String.format("剩余 %d小时%d分", hour, min);
        } catch (NumberFormatException e) {
            return null;
        }
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
