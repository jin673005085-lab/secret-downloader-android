package cn.secret.downloader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Exercises the original minified APK through framework views and the real picker callback. */
public final class ReleaseUiSmokeTest {
    private final Instrumentation instrumentation;
    private Activity activity;
    private interface Check { boolean ok(); }
    private ReleaseUiSmokeTest(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    public static String run(Instrumentation instrumentation) throws Exception {
        return new ReleaseUiSmokeTest(instrumentation).execute();
    }

    private String execute() throws Exception {
        String name = "ReleaseUiSmoke_" + System.currentTimeMillis();
        File folder = new File(instrumentation.getTargetContext().getCacheDir(), name);
        if (!folder.mkdirs()) throw new AssertionError("Cannot create owned fixture directory");
        File source = new File(folder, name + ".mp4");
        Instrumentation.ActivityMonitor monitor = null;
        try {
            try (InputStream in = instrumentation.getContext().getAssets().open("h264-aac-9s.mp4");
                 FileOutputStream out = new FileOutputStream(source)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            String pkg = instrumentation.getTargetContext().getPackageName();
            Uri uri = new Uri.Builder().scheme("content").authority(pkg + ".files")
                    .appendPath("player_cache").appendPath(name).appendPath(source.getName()).build();
            try (InputStream in = instrumentation.getTargetContext().getContentResolver().openInputStream(uri)) {
                if (in == null || in.read() < 0) throw new AssertionError("Fixture provider unreadable");
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.provider.action.PICK_IMAGES");
            filter.addAction(Intent.ACTION_OPEN_DOCUMENT);
            filter.addCategory(Intent.CATEGORY_OPENABLE); filter.addDataType("video/*");
            Intent result = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            result.setClipData(ClipData.newRawUri(name, uri));
            monitor = instrumentation.addMonitor(filter,
                    new Instrumentation.ActivityResult(Activity.RESULT_OK, result), true);
            activity = instrumentation.startActivitySync(new Intent().setClassName(pkg, "cn.secret.downloader.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            click("tab_button_1");
            checkSort("work_sort");
            create(false, name);
            await("animated GIF", 15000, () -> animation(root()) != null && animation(root()).isRunning());
            click("work_toggle");
            await("GIF paused", 3000, () -> animation(root()) != null && !animation(root()).isRunning());
            click("work_toggle");
            await("GIF resumed", 3000, () -> animation(root()) != null && animation(root()).isRunning());
            status("GIF high-quality conversion and playback PASS");
            backToWorks();
            create(true, name);
            await("live first frame", 15000, this::livePlaying);
            await("live automatically returns to cover", 20000, this::liveCover);
            click("work_replay");
            await("live replay", 10000, this::livePlaying);
            click("work_toggle");
            await("live cover after pause", 3000, this::liveCover);
            View gesture = require("work_gesture_area"); long down = SystemClock.uptimeMillis();
            touch(gesture, down, MotionEvent.ACTION_DOWN);
            try {
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L);
                await("live long press", 10000, this::livePlaying);
            } finally { touch(gesture, down, MotionEvent.ACTION_UP); }
            await("live release returns to cover", 3000, this::liveCover);
            status("Full-length live auto-play, replay and hold/release PASS");
            backToWorks(); bulkSelection("work", "work_filter_ALL");
            click("tab_button_2"); checkSort("video_sort");
            bulkSelection("video", "video_filter_ALL");
            if (monitor.getHits() != 2) throw new AssertionError("Expected two intercepted pickers, got " + monitor.getHits());
            return "PASS: original Release UI, sorting/selection, high GIF and full live. "
                    + "Generated works retained; source title prefix: " + name;
        } finally {
            if (monitor != null) instrumentation.removeMonitor(monitor);
            // Only these two paths were created by this invocation; generated media stays for review.
            source.delete(); folder.delete();
        }
    }

    private void create(boolean live, String name) throws Exception {
        click(live ? "open_CREATE_LIVE" : "open_CREATE_GIF");
        await("creator route", 5000, () -> find(live ? "route_CREATE_LIVE" : "route_CREATE_GIF") != null);
        click("creator_pick_video");
        await("fixture ready", 20000, () -> find("creator_create") != null && find("creator_create").isEnabled());
        if (!live) click("creator_quality_high");
        click("creator_create");
        await("task detail", 5000, () -> find("route_TASK_DETAIL") != null);
        await("conversion complete", 120000, () -> {
            if (find("task_action_重试") != null) throw new AssertionError("Conversion failed: " + text(root()));
            return find("task_action_打开") != null;
        });
        onMain(() -> { if (!text(root()).contains(name)) throw new AssertionError("Task is not owned fixture"); });
        click("task_action_打开");
        await("work viewer", 6000, () -> find(live ? "route_VIEW_LIVE" : "route_VIEW_GIF") != null);
        await("viewer error check", 1000, () -> {
            if (find("work_error") != null) throw new AssertionError("Viewer error: " + text(find("work_error")));
            return true;
        });
    }

    private boolean livePlaying() {
        View route = find("route_VIEW_LIVE"), cover = find("work_viewer_cover");
        if (find("work_error") != null) throw new AssertionError("Live viewer error: " + text(find("work_error")));
        return route != null && route.getKeepScreenOn() && cover == null;
    }
    private boolean liveCover() {
        View route = find("route_VIEW_LIVE");
        return route != null && !route.getKeepScreenOn() && find("work_viewer_cover") != null && find("work_toggle") == null;
    }
    private void checkSort(String tag) throws Exception {
        await("sort layout " + tag, 5000, () -> find(tag) != null && find(tag).getWidth() > 0 && find(tag).getHeight() > 0);
        onMain(() -> {
            View view = find(tag); float minimum = 48 * view.getResources().getDisplayMetrics().density;
            if (!(view instanceof TextView) || !((TextView)view).getText().toString().contains("排序")
                    || view.getWidth() + .5f < minimum || view.getHeight() + .5f < minimum)
                throw new AssertionError(tag + " must say 排序 and have a 48dp hit area");
        });
    }
    private void bulkSelection(String prefix, String filter) throws Exception {
        if (tag(filter) != null) click(filter);
        SystemClock.sleep(500);
        final View[] card = new View[1]; onMain(() -> card[0] = findPrefix(root(), prefix + "_card_"));
        if (card[0] == null) { status(prefix + " selection skipped: empty library"); return; }
        onMain(() -> { if (!card[0].performLongClick()) throw new AssertionError("Cannot enter multi-select"); });
        require(prefix + "_bulk_all");
        if ("全选".equals(label(prefix + "_bulk_all"))) click(prefix + "_bulk_all");
        await("select all", 3000, () -> selected(prefix) > 0);
        click(prefix + "_bulk_all"); await("deselect all", 3000, () -> selected(prefix) == 0);
        click(prefix + "_bulk_done"); status(prefix + " select all/deselect/done PASS");
    }
    private int selected(String prefix) {
        View count = find(prefix + "_selection_count");
        return count instanceof TextView ? Integer.parseInt(((TextView)count).getText().toString().replaceAll("\\D", "")) : -1;
    }
    private void backToWorks() throws Exception {
        for (int i = 0; i < 5 && tag("tab_button_1") == null; i++) {
            View previous = require("route_back"); click("route_back");
            await("back transition", 5000, () -> find("route_back") != previous || find("tab_button_1") != null);
        }
        click("tab_button_1"); require("open_CREATE_LIVE");
    }
    private void click(String tag) throws Exception {
        require(tag); await("enabled " + tag, 10000, () -> find(tag) != null && find(tag).isEnabled());
        onMain(() -> {
            View view = find(tag); view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
            if (!view.performClick()) throw new AssertionError("No click handler: " + tag);
        });
    }
    private void touch(View view, long down, int action) {
        onMain(() -> {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.getWidth()/2f, view.getHeight()/2f, 0);
            try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
        });
    }
    private View require(String tag) throws Exception { await(tag, 10000, () -> find(tag) != null); return tag(tag); }
    private View tag(String tag) { final View[] value = new View[1]; onMain(() -> value[0] = find(tag)); return value[0]; }
    private String label(String tag) { final String[] value = new String[1]; onMain(() -> value[0] = text(find(tag))); return value[0]; }
    private View root() { return activity.getWindow().getDecorView(); }
    private View find(String tag) { return walk(root(), tag, false); }
    private View findPrefix(View view, String prefix) { return walk(view, prefix, true); }
    private View walk(View view, String value, boolean prefix) {
        if (!view.isShown()) return null;
        String tag = String.valueOf(view.getTag());
        if (prefix ? tag.startsWith(value) : tag.equals(value)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = walk(((ViewGroup)view).getChildAt(i), value, prefix); if (found != null) return found;
        }
        return null;
    }
    private Animatable animation(View view) {
        if (!view.isShown()) return null;
        if (view instanceof ImageView && ((ImageView)view).getDrawable() instanceof Animatable) return (Animatable)((ImageView)view).getDrawable();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            Animatable found = animation(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private String text(View view) {
        if (view == null || !view.isShown()) return "";
        if (view instanceof TextView) return ((TextView)view).getText().toString();
        StringBuilder value = new StringBuilder();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) value.append(text(((ViewGroup)view).getChildAt(i))).append(' ');
        return value.toString();
    }
    private void await(String message, long timeout, Check check) throws Exception {
        long until = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < until) {
            final boolean[] done = new boolean[1]; onMain(() -> done[0] = check.ok());
            if (done[0]) return; SystemClock.sleep(100);
        }
        throw new AssertionError("Timed out: " + message);
    }
    private void onMain(Runnable action) {
        final Throwable[] error = new Throwable[1];
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private void status(String message) { Bundle result = new Bundle(); result.putString("stream", message + "\n"); instrumentation.sendStatus(0, result); }
}
