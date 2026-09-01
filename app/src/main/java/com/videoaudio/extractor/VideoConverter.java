package com.videoaudio.extractor;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

/**
 * 视频格式转换工具类
 *
 * 支持 MP4、MKV、MOV、AVI、WEBM、FLV 等格式互转
 * 进度方案与 AudioExtractor 一致：ffprobe 获取时长 + statistics 回调实时更新
 */
public class VideoConverter {

    private static final String TAG = "VideoConverter";
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    // 当前 FFmpeg 会话，用于取消操作
    private static volatile FFmpegSession currentSession;

    /**
     * 视频转换回调接口（与 AudioExtractor.Callback 结构一致）
     */
    public interface Callback {
        void onProgress(int progress);
        void onEtaUpdate(String etaText);
        void onSuccess(File outputFile);
        void onFailure(String message);
    }

    /**
     * 取消当前正在执行的转换任务
     */
    public static void cancel() {
        if (currentSession != null) {
            AppLog.i(TAG, "取消 FFmpeg 会话: " + currentSession.getSessionId());
            FFmpegKit.cancel(currentSession.getSessionId());
            currentSession = null;
        }
    }

    /**
     * 查询是否有正在执行的任务
     */
    public static boolean isRunning() {
        return currentSession != null;
    }

    /**
     * 视频格式转换
     *
     * @param inputPath    输入视频路径
     * @param outputPath   输出视频路径
     * @param targetFormat 目标格式（mp4/mkv/mov/avi/webm/flv）
     * @param callback     回调
     */
    public static void convertVideo(String inputPath, String outputPath,
                                    String targetFormat, Callback callback) {
        AppLog.d(TAG, "开始视频转换, 目标格式: " + targetFormat + ", CPU核心数: " + CPU_CORES);

        String command = buildCommand(inputPath, outputPath, targetFormat);
        AppLog.d(TAG, "FFmpeg command: " + command);

        // 同步获取视频总时长
        double durationSec = getDurationSync(inputPath);
        AppLog.d(TAG, "视频时长: " + durationSec + "秒");

        final double[] convertDuration = {durationSec};

        FFmpegSession session = FFmpegKit.executeAsync(command, session1 -> {
            AppLog.d(TAG, "FFmpeg 完成, Return code: " + session1.getReturnCode());
            currentSession = null; // 清理会话引用

            if (ReturnCode.isSuccess(session1.getReturnCode())) {
                callback.onProgress(100);
                callback.onEtaUpdate("已完成");
                File outputFile = new File(outputPath);
                if (outputFile.exists()) {
                    callback.onSuccess(outputFile);
                } else {
                    callback.onFailure("输出文件未生成");
                }
            } else if (ReturnCode.isCancel(session1.getReturnCode())) {
                callback.onFailure("已取消");
            } else {
                String failMsg = "FFmpeg 执行失败 (code: " + session1.getReturnCode() + ")";
                String logs = session1.getAllLogsAsString();
                if (logs != null && logs.length() > 200) {
                    failMsg += "\n" + logs.substring(logs.length() - 200);
                }
                callback.onFailure(failMsg);
            }
        }, log -> {
            AppLog.v(TAG, log.getMessage());
        }, statistics -> {
            try {
                double currentTime = statistics.getTime();
                double speed = statistics.getSpeed();

                AppLog.v(TAG, String.format("statistics: time=%.2f, speed=%.2f",
                        currentTime, speed));

                if (currentTime > 0 && convertDuration[0] > 0) {
                    int progress = (int) Math.min(100, (currentTime * 100.0 / convertDuration[0]));
                    callback.onProgress(progress);

                    if (speed > 0) {
                        String eta = formatEta(currentTime, convertDuration[0], speed);
                        if (eta != null) callback.onEtaUpdate(eta);
                    }
                } else if (currentTime > 0 && convertDuration[0] <= 0) {
                    int estimatedProgress = (int) Math.min(95, (currentTime * 100.0 / 600.0));
                    callback.onProgress(estimatedProgress);
                    callback.onEtaUpdate(String.format("已处理 %.0f秒...", currentTime));
                }
            } catch (Exception e) {
                AppLog.w(TAG, "statistics回调异常: " + e.getMessage());
            }
        });
        currentSession = session; // 保存会话引用
    }

    /**
     * 同步执行 ffprobe 获取视频时长（秒）
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
                    AppLog.d(TAG, "ffprobe 获取时长成功: " + duration + "秒");
                    return duration;
                }
            } else {
                AppLog.w(TAG, "ffprobe 返回码: " + probeSession.getReturnCode());
            }
        } catch (Exception e) {
            AppLog.w(TAG, "ffprobe 执行异常: " + e.getMessage());
        }

        AppLog.w(TAG, "ffprobe 获取时长失败，进度将使用估算值");
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
     * 构建 FFmpeg 视频转换命令
     *
     * 各格式编码策略：
     * - MP4/MOV: H.264 + AAC（最高兼容性，+faststart 支持流播放）
     * - MKV:     H.264 + AAC（通用容器）
     * - AVI:     MPEG4 + MP3（传统格式）
     * - WEBM:    VP9 + Opus（Web 优化）
     * - FLV:     H.264 + AAC（流媒体）
     */
    private static String buildCommand(String inputPath, String outputPath, String targetFormat) {
        String quotedInput = quotePath(inputPath);
        String quotedOutput = quotePath(outputPath);
        String threads = String.valueOf(CPU_CORES);

        switch (targetFormat.toLowerCase()) {
            case "mp4":
                return String.format(
                        "-y -threads %s -i %s -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p " +
                                "-c:a aac -b:a 192k -movflags +faststart %s",
                        threads, quotedInput, quotedOutput);

            case "mkv":
                return String.format(
                        "-y -threads %s -i %s -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p " +
                                "-c:a aac -b:a 192k %s",
                        threads, quotedInput, quotedOutput);

            case "mov":
                return String.format(
                        "-y -threads %s -i %s -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p " +
                                "-c:a aac -b:a 192k -movflags +faststart %s",
                        threads, quotedInput, quotedOutput);

            case "avi":
                return String.format(
                        "-y -threads %s -i %s -c:v mpeg4 -vtag xvid -qscale:v 3 -pix_fmt yuv420p " +
                                "-c:a libmp3lame -b:a 192k %s",
                        threads, quotedInput, quotedOutput);

            case "webm":
                return String.format(
                        "-y -threads %s -i %s -c:v libvpx-vp9 -crf 30 -b:v 0 -pix_fmt yuv420p " +
                                "-c:a libopus -b:a 192k %s",
                        threads, quotedInput, quotedOutput);

            case "flv":
                return String.format(
                        "-y -threads %s -i %s -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p " +
                                "-c:a aac -b:a 192k %s",
                        threads, quotedInput, quotedOutput);

            default:
                return String.format(
                        "-y -threads %s -i %s -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p " +
                                "-c:a aac -b:a 192k -movflags +faststart %s",
                        threads, quotedInput, quotedOutput);
        }
    }

    private static String quotePath(String path) {
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }
}
