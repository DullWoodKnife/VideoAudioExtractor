package com.videoaudio.extractor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件操作工具类
 * 处理文件复制、保存、分享等通用操作
 */
public class FileUtils {

    private static final String MIME_AUDIO_PREFIX = "audio/";

    /**
     * 将 URI 内容复制到应用缓存目录
     *
     * @param context 上下文
     * @param uri     内容 URI
     * @param prefix  临时文件前缀
     * @return 缓存中的文件
     */
    public static File copyUriToCache(Context context, Uri uri, String prefix) throws Exception {
        String displayName = queryDisplayName(context, uri);
        String suffix = ".bin";
        if (displayName != null) {
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot < displayName.length() - 1) {
                suffix = displayName.substring(dot);
            }
        }

        File outFile = File.createTempFile(prefix + "_", suffix, context.getCacheDir());

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) {
                throw new IllegalStateException("无法打开输入流");
            }
            byte[] buf = new byte[1024 * 1024]; // 1MB 缓冲
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
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
            context.startActivity(Intent.createChooser(intent, "分享音频文件"));
        } catch (Exception e) {
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
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 获取文件的 MIME 类型
     */
    private static String getMimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return MIME_AUDIO_PREFIX + name.substring(dot + 1).toLowerCase();
        }
        return "audio/*";
    }

    /**
     * 根据格式获取 MIME 类型
     */
    private static String getMimeType(String format) {
        return MIME_AUDIO_PREFIX + format.toLowerCase();
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
