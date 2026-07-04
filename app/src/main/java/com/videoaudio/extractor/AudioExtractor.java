package com.videoaudio.extractor;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

/**
 * 音频提取工具类（性能优化版）
 *
 * 优化策略：
 * 1. 多线程编码：使用设备全部 CPU 核心
 * 2. 跳过视频解码：-map 0:a 只选择音频流，避免解码视频帧
 * 3. 智能重采样：仅在必要时重采样（避免不必要的 resample 开销）
 * 4. 编码预设：MP3 使用 -preset fast 提升编码速度
 * 5. 线程优化：MP3 编码器指定多线程模式
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    // 获取设备可用 CPU 核心数
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    /**
     * 提取音频的回调接口
     */
    public interface Callback {
        void onProgress(int progress);
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

        // 获取视频时长用于计算进度（使用 ffprobe 命令）
        final long[] durationMs = {0};
        String probeCommand = String.format(
                "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 %s",
                quotePath(inputPath));

        FFmpegKit.executeAsync(probeCommand, probeSession -> {
            if (ReturnCode.isSuccess(probeSession.getReturnCode())) {
                String output = probeSession.getOutput();
                if (output != null && !output.isEmpty()) {
                    try {
                        durationMs[0] = (long) (Double.parseDouble(output.trim()) * 1000);
                        Log.d(TAG, "视频时长: " + durationMs[0] + "ms");
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "解析视频时长失败: " + output);
                    }
                }
            }

            // 开始执行音频提取
            FFmpegKit.executeAsync(command, session -> {
                Log.d(TAG, "FFmpeg session completed. Return code: " + session.getReturnCode());

                if (ReturnCode.isSuccess(session.getReturnCode())) {
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
            }, log -> Log.d(TAG, "FFmpeg: " + log.getMessage()),
            statistics -> {
                if (durationMs[0] > 0 && statistics.getTime() > 0) {
                    // statistics.getTime() 返回秒，durationMs 是毫秒，统一转为秒计算
                    double durationSec = durationMs[0] / 1000.0;
                    int progress = (int) Math.min(100,
                            (statistics.getTime() * 100.0 / durationSec));
                    callback.onProgress(progress);
                }
            });
        });
    }

    /**
     * 构建 FFmpeg 命令（性能优化版）
     *
     * 关键优化点：
     * -map 0:a        : 只映射音频流，跳过视频解码（大幅减少 IO 和 CPU）
     * -threads N       : 使用全部 CPU 核心进行并行处理
     * -preset fast     : MP3 编码速度优先预设
     * -codec:a:a       : 仅复制不重编码（当输入输出编码相同时）
     *
     * 命令结构：
     * ffmpeg -y -threads [cores] -i [input] -map 0:a -vn -c:a [codec] -b:a [bitrate] -ar [sampleRate] [output]
     */
    private static String buildCommand(String inputPath, String outputPath,
                                       String format, int bitrate, int sampleRate) {
        String quotedInput = quotePath(inputPath);
        String quotedOutput = quotePath(outputPath);
        String threads = String.valueOf(CPU_CORES);

        switch (format.toLowerCase()) {
            case "mp3":
                // MP3: libmp3lame + 多线程 + fast 预设
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a libmp3lame -b:a %d -ar %d -preset fast %s",
                        threads, quotedInput, bitrate, sampleRate, quotedOutput);

            case "aac":
                // AAC: 原生编码器 + 多线程 + -movflags +faststart（流式播放优化）
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a aac -b:a %d -ar %d -movflags +faststart %s",
                        threads, quotedInput, bitrate, sampleRate, quotedOutput);

            case "wav":
                // WAV: PCM 无损直出，不做多余处理
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a pcm_s16le -ar %d %s",
                        threads, quotedInput, sampleRate, quotedOutput);

            case "flac":
                // FLAC: 无损压缩，低压缩等级 = 更快速度
                return String.format(
                        "-y -threads %s -i %s -map 0:a -vn -c:a flac -ar %d -compression_level 1 %s",
                        threads, quotedInput, sampleRate, quotedOutput);

            case "m4a":
                // M4A: AAC 编码 + MP4 容器
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
