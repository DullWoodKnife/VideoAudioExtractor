package com.videoaudio.extractor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 文件操作工具类
 * 处理文件复制、保存、分享等通用操作
 */
public class FileUtils {

    private static final String TAG = "FileUtils";

    private static final String MIME_AUDIO_PREFIX = "audio/";
    private static final String MIME_VIDEO_PREFIX = "video/";

    private static final Set<String> VIDEO_FORMATS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "mov", "avi", "webm", "flv"
    ));

    /**
     * 将 URI 内容复制到应用缓存目录（性能优化版）
     *
     * 优化策略：
     * 1. 优先使用 FileChannel.transferTo 零拷贝（内核态直接传输，避免用户态拷贝）
     * 2. 回退时使用 8MB 大缓冲区（原为 1MB）
     *
     * @param context 上下文
     * @param uri     内容 URI
     * @param prefix  临时文件前缀
     * @return 缓存中的文件
     */
    public static File copyUriToCache(Context context, Uri uri, String prefix) throws Exception {
        String displayName = queryDisplayName(context, uri);
        AppLog.d(TAG, "复制文件到缓存: " + displayName);
        String suffix = ".bin";
        if (displayName != null) {
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot < displayName.length() - 1) {
                suffix = displayName.substring(dot);
            }
        }

        File outFile = File.createTempFile(prefix + "_", suffix, context.getCacheDir());

        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("无法打开输入流");
            }

            long startTime = System.currentTimeMillis();
            long fileSize;

            // 如果输入是 FileInputStream（文件 URI），使用 FileChannel 零拷贝
            if (in instanceof FileInputStream) {
                try (FileChannel srcChannel = ((FileInputStream) in).getChannel();
                     FileOutputStream fos = new FileOutputStream(outFile);
                     FileChannel dstChannel = fos.getChannel()) {
                    dstChannel.transferFrom(srcChannel, 0, Long.MAX_VALUE);
                }
                AppLog.d(TAG, "零拷贝完成: " + outFile.getName());
            } else {
                // 非文件输入流（如 content://），使用大缓冲区
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8 * 1024 * 1024]; // 8MB 缓冲
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }
                AppLog.d(TAG, "缓冲区拷贝完成: " + outFile.getName());
            }

            fileSize = outFile.length();
            AppLog.i(TAG, "文件复制完成: " + outFile.getName()
                    + ", 大小: " + formatFileSize(fileSize)
                    + ", 耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return outFile;
    }

    /**
     * 查询文件显示名
     */
    public static String queryDisplayName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor == null) return "unknown";
            int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            if (nameIndex < 0) return "unknown";
            if (!cursor.moveToFirst()) return "unknown";
            return cursor.getString(nameIndex);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取文件基础名（不含扩展名）
     */
    public static String getBaseName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "audio";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    /**
     * 打开文件（使用系统应用）
     */
    public static void openFile(Context context, File file) {
        try {
            Uri uri = getFileUri(context, file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "打开文件失败: " + file.getName(), e);
            Toast.makeText(context, "无法打开文件", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 分享文件
     */
    public static void shareFile(Context context, File file, String mimeType) {
        try {
            Uri uri = getFileUri(context, file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType != null ? mimeType : getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, "分享文件"));
        } catch (Exception e) {
            AppLog.e(TAG, "分享文件失败: " + file.getName(), e);
            Toast.makeText(context, "无法分享文件", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 保存文件到公共 Download 目录
     */
    public static boolean saveToPublicDirectory(Context context, File sourceFile, String format) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.getName());
                values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(format));
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoAudioExtractor");

                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;

                try (OutputStream out = context.getContentResolver().openOutputStream(uri);
                     FileInputStream in = new FileInputStream(sourceFile)) {
                    if (out == null) return false;
                    byte[] buf = new byte[1024 * 1024];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }
                return true;
            } else {
                // Android 9 及以下直接写入
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File appDir = new File(downloadDir, "VideoAudioExtractor");
                if (!appDir.exists()) {
                    appDir.mkdirs();
                }
                File destFile = new File(appDir, sourceFile.getName());

                try (FileInputStream in = new FileInputStream(sourceFile);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buf = new byte[1024 * 1024];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }

                // 通知 MediaScanner 扫描新文件
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(destFile));
                context.sendBroadcast(scanIntent);

                return true;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "保存文件到公共目录失败: " + sourceFile.getName(), e);
            return false;
        }
    }

    /**
     * 判断格式是否为视频格式
     */
    public static boolean isVideoFormat(String format) {
        return format != null && VIDEO_FORMATS.contains(format.toLowerCase());
    }

    /**
     * 获取文件的 MIME 类型
     */
    private static String getMimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (VIDEO_FORMATS.contains(ext)) {
                return MIME_VIDEO_PREFIX + ext;
            }
            return MIME_AUDIO_PREFIX + ext;
        }
        return "audio/*";
    }

    /**
     * 根据格式获取 MIME 类型
     */
    private static String getMimeType(String format) {
        String f = format.toLowerCase();
        if (VIDEO_FORMATS.contains(f)) {
            return MIME_VIDEO_PREFIX + f;
        }
        return MIME_AUDIO_PREFIX + f;
    }

    /**
     * 获取文件 URI（兼容 Scoped Storage）
     */
    private static Uri getFileUri(Context context, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file);
        } else {
            return Uri.fromFile(file);
        }
    }
}
