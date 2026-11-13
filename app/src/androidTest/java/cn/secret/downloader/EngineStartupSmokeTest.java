package cn.secret.downloader;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import com.yausername.aria2c.Aria2c;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test-only diagnostic usable with the existing minified APK; no app-internal APIs. */
public final class EngineStartupSmokeTest {
    private static final String TAG = "EngineStartupSmoke";
    private EngineStartupSmokeTest() { }

    public static String run(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext().getApplicationContext();
        long started = SystemClock.elapsedRealtime();
        String phase = "task status counts";
        try {
            counts(instrumentation, context);
            phase = "ZipUtils.unzip fresh ZIP";
            long probeStarted = SystemClock.elapsedRealtime();
            status(instrumentation, "START " + phase);
            freshZipProbe(context);
            status(instrumentation, "OK " + phase + " elapsedMs=" + (SystemClock.elapsedRealtime() - probeStarted));
            String[] names = {"YoutubeDL.init", "FFmpeg.init", "Aria2c.init"};
            for (int index = 0; index < names.length; index++) {
                phase = names[index];
                long step = SystemClock.elapsedRealtime();
                status(instrumentation, "START " + phase);
                // Class initialization occurs inside this try, so linkage/static-init Errors are preserved.
                switch (index) {
                    case 0: YoutubeDL.getInstance().init(context); break;
                    case 1: FFmpeg.getInstance().init(context); break;
                    case 2: Aria2c.getInstance().init(context); break;
                    default: throw new AssertionError("Unknown engine step");
                }
                status(instrumentation, "OK " + phase + " elapsedMs=" + (SystemClock.elapsedRealtime() - step));
            }
            phase = "final task status counts";
            counts(instrumentation, context);
            return "engine startup completed; elapsedMs=" + (SystemClock.elapsedRealtime() - started);
        } catch (Throwable failure) {
            String report = "FAIL " + phase + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                    + "\n" + Log.getStackTraceString(failure);
            Log.e(TAG, report);
            Bundle result = new Bundle(); result.putString("stream", report + "\n");
            instrumentation.sendStatus(0, result);
            throw new AssertionError("Engine startup diagnostic failed at " + phase, failure);
        }
    }

    private static void freshZipProbe(Context context) throws Exception {
        File archive = File.createTempFile("engine-startup-probe-", ".zip", context.getCacheDir());
        File output = new File(context.getCacheDir(), archive.getName() + ".unpacked");
        File probe = new File(output, "probe.txt");
        byte[] expected = "Fresh ZIP reflection constructor regression\n".getBytes(StandardCharsets.UTF_8);
        try {
            if (!output.mkdir()) throw new AssertionError("Cannot create owned ZIP probe directory");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("probe.txt"));
                zip.write(expected); zip.closeEntry();
            }
            // Use the same Commons Compress path as real engine extraction, even with warm engine caches.
            Class<?> zipUtils = Class.forName("com.yausername.youtubedl_common.utils.ZipUtils");
            zipUtils.getMethod("unzip", File.class, File.class)
                    .invoke(zipUtils.getField("INSTANCE").get(null), archive, output);
            if (!probe.isFile()) throw new AssertionError("ZIP probe was not extracted");
            byte[] actual = new byte[expected.length];
            try (DataInputStream input = new DataInputStream(new FileInputStream(probe))) {
                input.readFully(actual);
                if (input.read() != -1 || !Arrays.equals(expected, actual))
                    throw new AssertionError("Extracted ZIP probe content differs");
            }
        } finally {
            // Fixed paths owned by this invocation only: never recurse or enumerate user directories.
            probe.delete(); output.delete(); archive.delete();
        }
    }

    private static void counts(Instrumentation instrumentation, Context context) throws Exception {
        File file = context.getDatabasePath("creative_tasks.db");
        if (!file.isFile()) { status(instrumentation, "TASK COUNTS database absent"); return; }
        Map<String, Integer> groups = new TreeMap<>();
        // Read only existing records. Never instantiate the app repository or restore/change task state.
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
             Cursor rows = database.query("tasks", new String[]{"task_json"}, null, null, null, null, null)) {
            while (rows.moveToNext()) {
                JSONObject task = new JSONObject(rows.getString(0));
                String key = state(task.optString("status"), "QUEUED|PARSING|RUNNING|PAUSED|COMPLETED|FAILED|CANCELED|INTERRUPTED")
                        + "/" + state(task.optString("stage"), "PARSING|DOWNLOADING|MERGING|SAVING|READING|ENCODING");
                Integer previous = groups.get(key); groups.put(key, previous == null ? 1 : previous + 1);
            }
        }
        status(instrumentation, "TASK COUNTS status/stage=" + groups);
    }

    private static String state(String value, String allowed) {
        return ("|" + allowed + "|").contains("|" + value + "|") && !value.isEmpty() ? value : "UNKNOWN";
    }

    private static void status(Instrumentation instrumentation, String message) {
        Log.i(TAG, message);
        Bundle result = new Bundle(); result.putString("stream", message + "\n");
        instrumentation.sendStatus(0, result);
    }
}
