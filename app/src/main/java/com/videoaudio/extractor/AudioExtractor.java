package com.videoaudio.extractor;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.SessionState;

import java.io.File;

/**
 * 音频提取工具类
 * 封装 FFmpeg 命令，支持从视频中提取音频并转换为指定格式
 *
 * 支持的输出格式：
 * - MP3: 使用 libmp3lame 编码器，有损压缩，体积小
 * - AAC: 使用原生 AAC 编码器，高质量，兼容性好
 * - WAV: PCM 无损格式，文件较大
 * - FLAC: 无损压缩，音质优秀
 * - M4A: Apple 音频格式，体积适中
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    /**
     * 提取音频的回调接口
     */
    public interface Callback {
        /**
         * 进度更新（0-100）
         */
        void onProgress(int progress);

        /**
         * 提取成功
         */
        void onSuccess(File outputFile);

        /**
         * 提取失败
         */
        void onFailure(String message);
    }

    /**
     * 从视频中提取音频
     *
     * @param inputPath  输入视频文件路径
     * @param outputPath 输出音频文件路径
     * @param format     目标格式（mp3/aac/wav/flac/m4a）
     * @param bitrate    比特率（如 192000 = 192kbps）
     * @param sampleRate 采样率（如 44100）
     * @param callback   回调接口
     */
    public static void extractAudio(String inputPath, String outputPath,
                                    String format, int bitrate, int sampleRate,
                                    Callback callback) {
        String command = buildCommand(inputPath, outputPath, format, bitrate, sampleRate);
        Log.d(TAG, "FFmpeg command: " + command);

        // 获取视频时长用于计算进度
        getVideoDuration(inputPath, durationMs -> {
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
            }, log -> {
                // 实时日志输出
                Log.d(TAG, "FFmpeg: " + log.getMessage());
            }, statistics -> {
                // 统计信息用于进度计算
                if (durationMs > 0 && statistics.getTime() > 0) {
                    int progress = (int) Math.min(100,
                            (statistics.getTime() * 100.0 / durationMs));
                    callback.onProgress(progress);
                }
            });
        });
    }

    /**
     * 构建 FFmpeg 命令
     *
     * 命令模板：
     * ffmpeg -y -i [input] -vn -c:a [codec] -b:a [bitrate] -ar [sampleRate] [output]
     *
     * -y: 覆盖输出文件
     * -vn: 禁用视频流（只提取音频）
     * -c:a: 指定音频编码器
     * -b:a: 音频比特率
     * -ar: 采样率
     */
    private static String buildCommand(String inputPath, String outputPath,
                                       String format, int bitrate, int sampleRate) {
        String quotedInput = quotePath(inputPath);
        String quotedOutput = quotePath(outputPath);

        switch (format.toLowerCase()) {
            case "mp3":
                // MP3: libmp3lame 编码器
                return String.format("-y -i %s -vn -c:a libmp3lame -b:a %d -ar %d %s",
                        quotedInput, bitrate, sampleRate, quotedOutput);

            case "aac":
                // AAC: 原生 AAC 编码器
                return String.format("-y -i %s -vn -c:a aac -b:a %d -ar %d %s",
                        quotedInput, bitrate, sampleRate, quotedOutput);

            case "wav":
                // WAV: PCM 无损，忽略比特率设置
                return String.format("-y -i %s -vn -c:a pcm_s16le -ar %d %s",
                        quotedInput, sampleRate, quotedOutput);

            case "flac":
                // FLAC: 无损压缩编码器
                return String.format("-y -i %s -vn -c:a flac -ar %d -compression_level 5 %s",
                        quotedInput, sampleRate, quotedOutput);

            case "m4a":
                // M4A: AAC 编码，MP4 容器
                return String.format("-y -i %s -vn -c:a aac -b:a %d -ar %d %s",
                        quotedInput, bitrate, sampleRate, quotedOutput);

            default:
                // 默认使用 MP3
                return String.format("-y -i %s -vn -c:a libmp3lame -b:a %d -ar %d %s",
                        quotedInput, bitrate, sampleRate, quotedOutput);
        }
    }

    /**
     * 获取视频时长（毫秒）
     */
    private static void getVideoDuration(String inputPath, DurationCallback callback) {
        FFmpegKit.getMediaInformationAsync(inputPath, mediaInformation -> {
            if (mediaInformation != null && mediaInformation.getDuration() != null) {
                try {
                    // getDuration() 返回的是毫秒
                    long durationMs = (long) (mediaInformation.getDuration().doubleValue() * 1000);
                    callback.onResult(durationMs);
                } catch (Exception e) {
                    Log.w(TAG, "解析视频时长失败", e);
                    callback.onResult(0);
                }
            } else {
                callback.onResult(0);
            }
        }, null);
    }

    /**
     * 路径引号包裹（处理空格）
     */
    private static String quotePath(String path) {
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }

    /**
     * 时长获取回调
     */
    private interface DurationCallback {
        void onResult(long durationMs);
    }
}
