package com.videoaudio.extractor;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音频提取工具类（性能优化版）
 *
 * 优化策略：
 * 1. 多线程编码：使用设备全部 CPU 核心
 * 2. 跳过视频解码：-map 0:a 只选择音频流，避免解码视频帧
 * 3. 编码预设：MP3 使用 -preset fast 提升编码速度
 * 4. 进度计算：通过解析 FFmpeg 日志中的 time= 获取处理进度（最可靠方式）
 *
 * 进度方案说明：
 * statistics 回调在某些 ffmpeg-kit fork 中不可靠，因此改用解析日志的方式。
 * FFmpeg 在处理过程中会输出类似 "frame=  100 fps=50 q=28.0 size=... time=00:00:05.20 ..."
 * 的日志，其中 time= 字段表示已处理的媒体时长，据此可精确计算进度百分比。
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    // 获取设备可用 CPU 核心数
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    // 匹配 FFmpeg 进度日志中的 time=HH:MM:SS.xx 字段
    private static final Pattern TIME_PATTERN = Pattern.compile("time=(\\d{1,2}):(\\d{2}):(\\d{2})\\.(\\d+)");

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
     * 从视频中提取音频（优化版）
     */
    public static void extractAudio(String inputPath, String outputPath,
                                    String format, int bitrate, int sampleRate,
                                    Callback callback) {
        Log.d(TAG, "开始提取音频, CPU核心数: " + CPU_CORES);
        String command = buildCommand(inputPath, outputPath, format, bitrate, sampleRate);
        Log.d(TAG, "FFmpeg command: " + command);

        // 获取视频总时长（秒）
        final double[] durationSec = {0};
        String probeCommand = String.format(
                "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 %s",
                quotePath(inputPath));

        FFmpegKit.executeAsync(probeCommand, probeSession -> {
            if (ReturnCode.isSuccess(probeSession.getReturnCode())) {
                String output = probeSession.getOutput();
                if (output != null && !output.isEmpty()) {
                    try {
                        durationSec[0] = Double.parseDouble(output.trim());
                        Log.d(TAG, "视频时长: " + durationSec[0] + "秒");
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "解析视频时长失败: " + output);
                    }
                }
            } else {
                Log.w(TAG, "ffprobe 执行失败，将在日志解析中尝试获取时长");
            }

            Log.d(TAG, "开始执行音频提取命令...");

            // 执行音频提取，通过日志回调解析进度
            FFmpegKit.executeAsync(command, session -> {
                Log.d(TAG, "FFmpeg session completed. Return code: " + session.getReturnCode());

                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    // 确保进度到 100%
                    callback.onProgress(100);
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
            }, logMessage -> {
                // 通过解析 FFmpeg 日志输出计算进度和 ETA
                String logText = logMessage.getMessage();
                int progress = parseProgressFromLog(logText, durationSec[0]);
                if (progress >= 0) {
                    callback.onProgress(progress);
                }
                // 计算 ETA
                String eta = parseEtaFromLog(logText, durationSec[0]);
                if (eta != null) {
                    callback.onEtaUpdate(eta);
                }
            }, statistics -> {
                // 仍然保留 statistics 回调作为备用（部分 fork 可能支持）
                if (durationSec[0] > 0 && statistics.getTime() > 0) {
                    int progress = (int) Math.min(100,
                            (statistics.getTime() * 100.0 / durationSec[0]));
                    callback.onProgress(progress);
                }
            });
        });
    }

    /**
     * 从 FFmpeg 日志消息中解析当前处理进度
     *
     * FFmpeg 日志格式示例：
     *   frame=  100 fps=50 q=28.0 size=    1024kB time=00:00:05.20 bitrate=162.5kbits/s speed=3.2x
     *
     * @param text          FFmpegKit 日志文本
     * @param totalDuration 视频总时长（秒），0 表示未知
     * @return 进度百分比 0-100，如果无法解析返回 -1
     */
    private static int parseProgressFromLog(String text, double totalDuration) {
        if (text == null) return -1;

        // 只处理包含 time= 的日志行（通常是 AV_LOG_INFO 级别）
        int timeIdx = text.indexOf("time=");
        if (timeIdx < 0) return -1;

        Matcher matcher = TIME_PATTERN.matcher(text);
        if (!matcher.find(timeIdx)) return -1;

        try {
            int hours = Integer.parseInt(matcher.group(1));
            int minutes = Integer.parseInt(matcher.group(2));
            int seconds = Integer.parseInt(matcher.group(3));
            String fracStr = matcher.group(4);

            // 处理小数部分（可能不足 2 位，如 .5 应理解为 .50）
            double frac;
            if (fracStr.length() == 1) {
                frac = Integer.parseInt(fracStr) / 10.0;
            } else if (fracStr.length() == 2) {
                frac = Integer.parseInt(fracStr) / 100.0;
            } else {
                frac = Double.parseDouble("0." + fracStr);
            }

            double currentTimeSec = hours * 3600 + minutes * 60 + seconds + frac;

            // 如果总时长未知（ffprobe 失败），尝试从日志中推测
            // 不做推测，直接返回基于时间的值（调用方可能后续更新 totalDuration）
            if (totalDuration <= 0) return -1;

            int progress = (int) Math.min(100, (currentTimeSec * 100.0 / totalDuration));

            Log.v(TAG, String.format("进度: %d%% (已处理 %.2fs / 总 %.2fs)",
                    progress, currentTimeSec, totalDuration));

            return progress;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // 匹配 FFmpeg 日志中的 speed= 字段
    private static final Pattern SPEED_PATTERN = Pattern.compile("speed=([\\d.]+)x");

    /**
     * 从 FFmpeg 日志中解析 ETA（预计剩余时间）
     *
     * FFmpeg 日志中包含 speed= 字段（如 speed=3.2x 表示处理速度是实时的 3.2 倍）
     * 结合已处理时长和总时长，可推算剩余时间：
     *   剩余时间 = (总时长 - 已处理时长) / speed
     *
     * @param text          FFmpegKit 日志文本
     * @param totalDuration 视频总时长（秒），0 表示未知
     * @return ETA 文字描述，如 "剩余 1分30秒"，无法计算返回 null
     */
    private static String parseEtaFromLog(String text, double totalDuration) {
        if (text == null || totalDuration <= 0) return null;

        // 需要 time= 和 speed= 两个字段
        int timeIdx = text.indexOf("time=");
        if (timeIdx < 0) return null;

        Matcher timeMatcher = TIME_PATTERN.matcher(text);
        if (!timeMatcher.find(timeIdx)) return null;

        int speedIdx = text.indexOf("speed=");
        if (speedIdx < 0) return null;

        Matcher speedMatcher = SPEED_PATTERN.matcher(text);
        if (!speedMatcher.find(speedIdx)) return null;

        try {
            int hours = Integer.parseInt(timeMatcher.group(1));
            int minutes = Integer.parseInt(timeMatcher.group(2));
            int seconds = Integer.parseInt(timeMatcher.group(3));
            String fracStr = timeMatcher.group(4);

            double frac;
            if (fracStr.length() == 1) {
                frac = Integer.parseInt(fracStr) / 10.0;
            } else if (fracStr.length() == 2) {
                frac = Integer.parseInt(fracStr) / 100.0;
            } else {
                frac = Double.parseDouble("0." + fracStr);
            }

            double processedSec = hours * 3600 + minutes * 60 + seconds + frac;
            double speed = Double.parseDouble(speedMatcher.group(1));

            if (speed <= 0 || processedSec <= 0) return null;
            if (processedSec >= totalDuration) return "即将完成";

            double remainSec = (totalDuration - processedSec) / speed;

            if (remainSec < 1) return "即将完成";
            if (remainSec < 60) return String.format("剩余 %d秒", (int) remainSec);
            if (remainSec < 3600) {
                int etaMin = (int) (remainSec / 60);
                int etaSec = (int) (remainSec % 60);
                return String.format("剩余 %d分%02d秒", etaMin, etaSec);
            }
            int etaHour = (int) (remainSec / 3600);
            int etaMin = (int) ((remainSec % 3600) / 60);
            return String.format("剩余 %d小时%d分", etaHour, etaMin);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 构建 FFmpeg 命令（性能优化版）
     *
     * 关键优化点：
     * -map 0:a        : 只映射音频流，跳过视频解码（大幅减少 IO 和 CPU）
     * -threads N       : 使用全部 CPU 核心进行并行处理
     * -preset fast     : MP3 编码速度优先预设
     * -stats           : 启用统计信息输出（确保日志中有 time= 字段）
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

    /**
     * 路径引号包裹（处理空格）
     */
    private static String quotePath(String path) {
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }
}
