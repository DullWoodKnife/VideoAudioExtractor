package com.videoaudio.extractor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import android.content.ContentValues;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一日志系统
 *
 * 特性：
 * 1. 日志级别控制（VERBOSE / DEBUG / INFO / WARN / ERROR）
 * 2. Debug 构建全量输出到 Logcat；Release 构建仅输出 WARN 及以上
 * 3. 同时写入文件日志（位于应用私有目录 logs/ 下，无需存储权限）
 * 4. 日志轮转：单个文件上限 5MB，保留最近 3 个历史文件
 * 5. 文件写入异步执行（单线程 Executor），不阻塞调用线程
 * 6. 线程安全
 *
 * 使用方式：
 *   AppLog.init(context);          // 在 Application/Activity 启动时调用一次
 *   AppLog.d("Tag", "message");   // 各级别日志
 *   AppLog.e("Tag", "error", e);  // 带异常堆栈
 */
public class AppLog {

    // ========== 日志级别 ==========
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    // ========== 配置 ==========
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_LOG_FILES = 4; // 1个当前 + 3个历史
    private static final String LOG_DIR = "logs";
    private static final String LOG_PREFIX = "app_log";
    private static final String LOG_EXTENSION = ".txt";
    private static final String PREF_NAME = "app_log_prefs";
    private static final String PREF_KEY_ENABLED = "log_enabled";

    // ========== 运行状态 ==========
    private static Context appContext;
    private static File logDir;
    private static File currentLogFile;
    private static final SimpleDateFormat dateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private static volatile boolean initialized = false;
    private static volatile boolean enabled = true; // 日志开关，默认开启

    /**
     * 当前日志级别阈值
     * - Debug 构建：VERBOSE（全量输出）
     * - Release 构建：WARN（仅警告和错误）
     */
    private static int minLevel = BuildConfig.DEBUG ? VERBOSE : WARN;

    // ========== 初始化 ==========

    /**
     * 初始化日志系统（在 Application 或首个 Activity 的 onCreate 中调用）
     *
     * @param context 任意 Context，内部会取 ApplicationContext
     */
    public static synchronized void init(Context context) {
        if (initialized || context == null) return;

        appContext = context.getApplicationContext();
        logDir = new File(appContext.getExternalFilesDir(null), LOG_DIR);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        // 读取持久化的开关状态
        SharedPreferences prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean(PREF_KEY_ENABLED, true);

        // 查找或创建当前日志文件
        currentLogFile = findOrCreateLogFile();
        initialized = true;

        // 记录启动信息
        if (enabled) {
            i("AppLog", "日志系统初始化完成, 设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + ", 应用版本: " + BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")"
                    + ", 日志级别: " + levelName(minLevel));
        }
    }

    /**
     * 动态设置日志级别
     */
    public static void setMinLevel(int level) {
        minLevel = level;
    }

    /**
     * 开启/关闭日志系统（持久化到 SharedPreferences）
     * 关闭后所有日志调用将被跳过
     */
    public static void setEnabled(boolean enable) {
        enabled = enable;
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(PREF_KEY_ENABLED, enable).apply();
        }
        if (enable) {
            Log.i("AppLog", "日志系统已开启");
        } else {
            Log.i("AppLog", "日志系统已关闭");
        }
    }

    /**
     * 查询日志开关状态
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取当前日志目录
     */
    public static File getLogDir() {
        return logDir;
    }

    /**
     * 获取当前日志文件
     */
    public static File getCurrentLogFile() {
        return currentLogFile;
    }

    // ========== 日志 API ==========

    public static void v(String tag, String message) {
        log(VERBOSE, tag, message, null);
    }

    public static void d(String tag, String message) {
        log(DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        log(INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        log(WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable throwable) {
        log(WARN, tag, message, throwable);
    }

    public static void e(String tag, String message) {
        log(ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        log(ERROR, tag, message, throwable);
    }

    // ========== 核心逻辑 ==========

    /**
     * 统一日志入口
     *
     * @param level    日志级别
     * @param tag      标签
     * @param message  消息
     * @param throwable 异常（可为 null）
     */
    private static void log(int level, String tag, String message, Throwable throwable) {
        if (!enabled || level < minLevel) return;

        // 1. 输出到 Logcat
        writeToLogcat(level, tag, message, throwable);

        // 2. 异步写入文件
        if (initialized && currentLogFile != null) {
            final String logLine = formatLogLine(level, tag, message, throwable);
            fileExecutor.execute(() -> writeToFile(logLine));
        }
    }

    /**
     * 输出到 Android Logcat
     */
    private static void writeToLogcat(int level, String tag, String message, Throwable throwable) {
        switch (level) {
            case VERBOSE:
                Log.v(tag, message, throwable);
                break;
            case DEBUG:
                Log.d(tag, message, throwable);
                break;
            case INFO:
                Log.i(tag, message, throwable);
                break;
            case WARN:
                Log.w(tag, message, throwable);
                break;
            case ERROR:
                Log.e(tag, message, throwable);
                break;
        }
    }

    /**
     * 格式化日志行
     *
     * 格式：[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL/TAG] [Thread] message
     */
    private static String formatLogLine(int level, String tag, String message, Throwable throwable) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[').append(dateFmt.format(new Date())).append(']');
        sb.append(" [").append(levelName(level)).append('/').append(tag).append(']');
        sb.append(" [").append(Thread.currentThread().getName()).append(']');
        sb.append(' ').append(message);

        if (throwable != null) {
            sb.append('\n');
            // 将堆栈转为字符串
            java.io.StringWriter sw = new java.io.StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }

        return sb.toString();
    }

    /**
     * 写入日志文件（在后台线程执行）
     * 如果当前文件超过大小限制，触发轮转
     */
    private static synchronized void writeToFile(String logLine) {
        if (currentLogFile == null) return;

        try {
            // 检查是否需要轮转
            if (currentLogFile.length() >= MAX_FILE_SIZE) {
                rotateLogs();
            }

            // 追加写入
            try (FileWriter writer = new FileWriter(currentLogFile, true)) {
                writer.append(logLine).append('\n');
                writer.flush();
            }
        } catch (IOException e) {
            // 文件写入失败时回退到 Logcat
            Log.e("AppLog", "写入日志文件失败: " + e.getMessage());
        }
    }

    /**
     * 日志文件轮转
     * 将当前文件重命名为历史文件，删除超出数量的旧文件，创建新的当前文件
     */
    private static void rotateLogs() {
        // 删除最老的文件（编号最大的）
        File oldest = new File(logDir, LOG_PREFIX + "_" + (MAX_LOG_FILES - 1) + LOG_EXTENSION);
        if (oldest.exists()) {
            oldest.delete();
        }

        // 从高到低依次重命名：app_log_2 -> app_log_3, app_log_1 -> app_log_2, ...
        for (int i = MAX_LOG_FILES - 2; i >= 0; i--) {
            File src = (i == 0)
                    ? new File(logDir, LOG_PREFIX + LOG_EXTENSION)
                    : new File(logDir, LOG_PREFIX + "_" + i + LOG_EXTENSION);
            File dst = new File(logDir, LOG_PREFIX + "_" + (i + 1) + LOG_EXTENSION);
            if (src.exists()) {
                src.renameTo(dst);
            }
        }

        // 创建新的当前日志文件
        currentLogFile = new File(logDir, LOG_PREFIX + LOG_EXTENSION);
        try {
            currentLogFile.createNewFile();
        } catch (IOException e) {
            Log.e("AppLog", "创建日志文件失败: " + e.getMessage());
        }
    }

    /**
     * 查找或创建当前日志文件
     * 优先复用最新的 app_log.txt，如果不存在则创建
     */
    private static File findOrCreateLogFile() {
        File logFile = new File(logDir, LOG_PREFIX + LOG_EXTENSION);
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                Log.e("AppLog", "初始化日志文件失败: " + e.getMessage());
            }
        }
        return logFile;
    }

    /**
     * 获取级别名称
     */
    private static String levelName(int level) {
        switch (level) {
            case VERBOSE: return "V";
            case DEBUG: return "D";
            case INFO: return "I";
            case WARN: return "W";
            case ERROR: return "E";
            default: return "?";
        }
    }

    /**
     * 清除所有日志文件（可在设置中调用）
     */
    public static void clearLogs() {
        if (logDir == null || !logDir.exists()) return;

        File[] files = logDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.getName().startsWith(LOG_PREFIX)) {
                file.delete();
            }
        }

        currentLogFile = findOrCreateLogFile();
        i("AppLog", "日志已清除");
    }

    /**
     * 导出所有日志文件到公共 Download 目录
     *
     * 将 logs 目录下的所有日志文件合并为一个文件，保存到
     * Download/VideoAudioExtractor/logs/ 目录下，文件名包含时间戳。
     *
     * @param context 上下文
     * @return 导出的文件路径描述，失败返回 null
     */
    public static String exportLogs(Context context) {
        if (logDir == null || !logDir.exists()) {
            return null;
        }

        File[] logFiles = logDir.listFiles((dir, name) ->
                name.startsWith(LOG_PREFIX) && name.endsWith(LOG_EXTENSION));
        if (logFiles == null || logFiles.length == 0) {
            return null;
        }

        // 按文件名排序：app_log.txt -> app_log_1.txt -> app_log_2.txt -> ...
        java.util.Arrays.sort(logFiles, (a, b) -> {
            // app_log.txt 排最前（最新），其余按编号降序
            String aName = a.getName();
            String bName = b.getName();
            if (aName.equals(LOG_PREFIX + LOG_EXTENSION)) return -1;
            if (bName.equals(LOG_PREFIX + LOG_EXTENSION)) return 1;
            return bName.compareTo(aName);
        });

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String exportFileName = "log_export_" + timestamp + LOG_EXTENSION;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, exportFileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/VideoAudioExtractor/logs");

                android.net.Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;

                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return null;
                    writeMergedLogs(out, logFiles);
                }
                return "Download/VideoAudioExtractor/logs/" + exportFileName;
            } else {
                // Android 9 及以下直接写入
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File exportDir = new File(downloadDir, "VideoAudioExtractor/logs");
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }
                File destFile = new File(exportDir, exportFileName);

                try (OutputStream out = new FileOutputStream(destFile)) {
                    writeMergedLogs(out, logFiles);
                }

                // 通知 MediaScanner
                android.content.Intent scanIntent = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(android.net.Uri.fromFile(destFile));
                context.sendBroadcast(scanIntent);

                return destFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e("AppLog", "导出日志失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将多个日志文件合并写入输出流
     * 按从旧到新的顺序合并（编号大的先写，当前文件最后写）
     */
    private static void writeMergedLogs(OutputStream out, File[] logFiles) throws IOException {
        // 反转数组：从最老的文件开始写
        java.util.List<File> reversed = new java.util.ArrayList<>(java.util.Arrays.asList(logFiles));
        java.util.Collections.reverse(reversed);

        for (File logFile : reversed) {
            if (!logFile.exists()) continue;
            // 写入文件分隔标题
            String header = "\n========== " + logFile.getName() + " ==========\n";
            out.write(header.getBytes());

            try (FileInputStream in = new FileInputStream(logFile)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
        }
        out.flush();
    }
}
