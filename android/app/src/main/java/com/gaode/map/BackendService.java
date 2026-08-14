package com.gaode.map;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * Android 前台服务 — 管理 FastAPI 后端进程生命周期
 *
 * 职责：
 * 1. APP 启动时自动运行编译好的 Python 二进制程序
 * 2. 确保后端进程在后台持续运行（前台服务保活）
 * 3. APP 退出 / 服务销毁时，自动杀掉后端进程
 * 4. 将 .env 配置文件复制到 APP 私有目录，供后端读取
 *
 * 关键设计：
 * - 使用前台 Service + 常驻通知，降低被系统查杀概率
 * - 进程通过 ProcessBuilder 启动，PID 保存在成员变量中
 * - onDestroy() 时主动 Process.destroy() 确保无残留
 */
public class BackendService extends Service {

    private static final String TAG = "BackendService";
    private static final String CHANNEL_ID = "gaode_backend_channel";
    private static final int NOTIFICATION_ID = 2001;

    private Process backendProcess;
    private Thread backendThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "后端服务 onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "后端服务 onStartCommand");

        // 启动前台通知（必须，否则 5 秒内被系统 kill）
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);

        // 启动后端进程
        startBackendProcess();

        // START_STICKY: 服务被 kill 后自动重启
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;  // 不支持绑定
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "后端服务 onDestroy — 停止后端进程");
        stopBackendProcess();
    }

    // ================================================================
    // 后端进程管理
    // ================================================================

    /**
     * 启动 FastAPI 编译后的二进制程序
     *
     * 二进制文件位置：assets/amap_server.bin
     * 运行时复制到：/data/data/com.gaode.map/files/amap_server
     * .env 位置：/data/data/com.gaode.map/files/.env
     */
    private void startBackendProcess() {
        if (backendProcess != null && backendProcess.isAlive()) {
            Log.w(TAG, "后端进程已在运行，跳过");
            return;
        }

        backendThread = new Thread(() -> {
            try {
                // 1. 准备二进制文件
                String binaryPath = prepareBinary();

                // 2. 准备 .env 配置文件
                prepareEnvFile();

                // 3. 设置环境变量（指定 .env 路径）
                String filesDir = getFilesDir().getAbsolutePath();
                String envPath = filesDir + "/.env";

                // 4. 构建启动命令
                ProcessBuilder pb = new ProcessBuilder(
                    binaryPath
                );
                pb.environment().put("AMAP_ENV_FILE", envPath);
                pb.environment().put("ANDROID_DATA", "/data");
                pb.environment().put("ANDROID_ROOT", "/system");
                pb.environment().put("HOME", filesDir);
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                pb.directory(new java.io.File(filesDir));

                // 重定向输出（避免阻塞）
                pb.redirectErrorStream(true);

                Log.i(TAG, "启动后端: " + binaryPath);
                Log.i(TAG, "环境变量 AMAP_ENV_FILE=" + envPath);

                backendProcess = pb.start();

                // 5. 读取进程输出（后台线程）
                readProcessOutput(backendProcess);

                // 6. 等待进程结束
                int exitCode = backendProcess.waitFor();
                Log.w(TAG, "后端进程退出，退出码: " + exitCode);

                // 异常退出时重启
                if (exitCode != 0 && exitCode != 143) {  // 143 = SIGTERM 正常终止
                    Log.w(TAG, "后端异常退出，3 秒后重启...");
                    Thread.sleep(3000);
                    startBackendProcess();
                }

            } catch (Exception e) {
                Log.e(TAG, "启动后端进程失败: " + e.getMessage(), e);
            }
        }, "BackendProcessThread");

        backendThread.setDaemon(true);
        backendThread.start();
    }

    /**
     * 停止后端进程
     */
    private void stopBackendProcess() {
        try {
            if (backendProcess != null && backendProcess.isAlive()) {
                Log.i(TAG, "正在终止后端进程...");
                backendProcess.destroy();  // SIGTERM
                // 等待最多 3 秒
                Thread.sleep(500);
                if (backendProcess.isAlive()) {
                    backendProcess.destroyForcibly();  // SIGKILL
                    Log.w(TAG, "后端进程被强制终止");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "停止后端进程异常: " + e.getMessage(), e);
        }
    }

    /**
     * 从 assets 复制二进制文件到 APP 私有目录并设置可执行权限
     *
     * @return 可执行文件的绝对路径
     */
    private String prepareBinary() throws Exception {
        String filesDir = getFilesDir().getAbsolutePath();
        String binaryName = "amap_server";
        String destPath = filesDir + "/" + binaryName;

        java.io.File destFile = new java.io.File(destPath);

        // 检查是否已存在且为最新版本
        if (destFile.exists()) {
            Log.i(TAG, "二进制已存在: " + destPath);
            // 确保可执行权限
            destFile.setExecutable(true, false);
            return destPath;
        }

        // 从 assets 复制
        Log.i(TAG, "从 assets 复制二进制文件...");
        java.io.InputStream in = getAssets().open("amap_server.bin");
        java.io.FileOutputStream out = new java.io.FileOutputStream(destFile);

        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) > 0) {
            out.write(buffer, 0, length);
        }
        out.flush();
        out.close();
        in.close();

        // 设置可执行权限 (chmod 755)
        destFile.setExecutable(true, false);
        destFile.setReadable(true, false);

        Log.i(TAG, "✓ 二进制文件就绪: " + destPath + " (" + destFile.length() + " bytes)");
        return destPath;
    }

    /**
     * 准备 .env 配置文件
     *
     * 优先从 assets/.env 复制内置配置；
     * 如果 APP 私有目录已有 .env（用户通过登录页面保存的），则保留。
     */
    private void prepareEnvFile() throws Exception {
        java.io.File envFile = new java.io.File(getFilesDir(), ".env");

        if (envFile.exists()) {
            Log.i(TAG, ".env 已存在: " + envFile.getAbsolutePath());
            return;
        }

        // 尝试从 assets 复制内置 .env
        try {
            java.io.InputStream in = getAssets().open(".env");
            java.io.FileOutputStream out = new java.io.FileOutputStream(envFile);

            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush();
            out.close();
            in.close();

            Log.i(TAG, "✓ .env 已从 assets 复制: " + envFile.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Log.w(TAG, "assets/.env 不存在，创建空 .env");
            envFile.createNewFile();
        }
    }

    /**
     * 读取后端进程输出（用于调试）
     */
    private void readProcessOutput(Process process) {
        new Thread(() -> {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream())
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, "[Backend] " + line);
                }
            } catch (Exception e) {
                // 进程结束时会抛出异常，忽略
            }
        }, "BackendOutputReader").start();
    }

    // ================================================================
    // 通知管理
    // ================================================================

    /**
     * 创建通知渠道（Android 8.0+ 必须）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "gaode地图后台服务",
                NotificationManager.IMPORTANCE_LOW  // LOW 级别不发出声音
            );
            channel.setDescription("保持地图后端服务运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 构建前台通知
     */
    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("gaode地图")
            .setContentText("地图服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // 不可滑动删除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
