package com.mitch.lumen;

import android.app.Activity;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lumen: a home screen (launcher) whose UI is assets/index.html.
 * The page talks to the TV through the "LumenAndroid" bridge below.
 */
public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 7;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private final Map<String, String> iconCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0C0D12"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    startActivityForResult(i, PICK_IMAGE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    js("window.lumenToast && lumenToast('This TV has no file picker.')");
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "LumenAndroid");
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        hideSystemBars();
        web.requestFocus();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void js(final String code) {
        if (web != null) web.post(() -> web.evaluateJavascript(code, null));
    }

    /** The remote's Home button delivers a HOME intent to this (singleTask) activity. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) js("window.lumenHome && lumenHome()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (web != null) web.requestFocus();
        js("window.lumenResume && lumenResume()");
    }

    /** Back never leaves the launcher; OK is forwarded so hold-to-open-options works. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (!down) js("window.lumenBack && lumenBack()");
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!down || e.getRepeatCount() == 0) js("window.lumenOk && lumenOk(" + down + ")");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMAGE && fileCallback != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCallback.onReceiveValue(u == null ? null : new Uri[]{u});
            fileCallback = null;
        }
    }

    private String iconFor(ResolveInfo ri, PackageManager pm) {
        String pkg = ri.activityInfo.packageName;
        String cached = iconCache.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable d = ri.loadIcon(pm);
            int size = 192;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            String url = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            iconCache.put(pkg, url);
            return url;
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstNumber(String s) {
        Matcher m = Pattern.compile("\\d+").matcher(s == null ? "" : s);
        return m.find() ? m.group() : "";
    }

    /** Everything the page can ask the TV for. */
    private class Bridge {
        @JavascriptInterface
        public String getApps() {
            PackageManager pm = getPackageManager();
            Map<String, ResolveInfo> seen = new LinkedHashMap<>();
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String p = ri.activityInfo.packageName;
                    if (!p.equals(getPackageName()) && !seen.containsKey(p)) seen.put(p, ri);
                }
            }
            JSONArray out = new JSONArray();
            for (ResolveInfo ri : seen.values()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("pkg", ri.activityInfo.packageName);
                    o.put("name", ri.loadLabel(pm).toString());
                    o.put("icon", iconFor(ri, pm));
                    out.put(o);
                } catch (Exception ignored) { }
            }
            return out.toString();
        }

        /** Last-used time per package. Needs: appops set com.mitch.lumen GET_USAGE_STATS allow */
        @JavascriptInterface
        public String getRecents() {
            JSONObject o = new JSONObject();
            try {
                UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 86400000L, now);
                if (list != null) for (UsageStats u : list) {
                    if (u.getTotalTimeInForeground() <= 0 || u.getPackageName().equals(getPackageName())) continue;
                    long prev = o.optLong(u.getPackageName(), 0);
                    if (u.getLastTimeUsed() > prev) o.put(u.getPackageName(), u.getLastTimeUsed());
                }
            } catch (Exception ignored) { }
            return o.toString();
        }

        /** HDMI and other pass-through inputs with their connection state (0 connected, 1 standby, 2 disconnected). */
        @JavascriptInterface
        public String getInputs() {
            JSONArray out = new JSONArray();
            try {
                TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                for (TvInputInfo ti : tim.getTvInputList()) {
                    if (!ti.isPassthroughInput()) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", ti.getId());
                    o.put("label", String.valueOf(ti.loadLabel(MainActivity.this)));
                    o.put("type", ti.getType());
                    o.put("state", tim.getInputState(ti.getId()));
                    out.put(o);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        @JavascriptInterface
        public boolean launch(String pkg) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final Intent fi = i;
            runOnUiThread(() -> { try { startActivity(fi); } catch (Exception ignored) { } });
            return true;
        }

        /** label is "HDMI 1".."HDMI 4"; matched to the TV's own input by its port number. */
        @JavascriptInterface
        public String switchInput(String label) {
            try {
                TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                String want = firstNumber(label);
                String id = null;
                int hdmiIndex = 0;
                String byOrder = null;
                for (TvInputInfo ti : tim.getTvInputList()) {
                    if (!ti.isPassthroughInput() || ti.getType() != TvInputInfo.TYPE_HDMI) continue;
                    hdmiIndex++;
                    String l = String.valueOf(ti.loadLabel(MainActivity.this));
                    if (want.equals(firstNumber(l))) { id = ti.getId(); break; }
                    if (String.valueOf(hdmiIndex).equals(want)) byOrder = ti.getId();
                }
                if (id == null) id = byOrder;
                if (id == null) return "not found";
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> {
                    try { startActivity(i); } catch (Exception e) { js("window.lumenToast && lumenToast('The TV refused to switch input.')"); }
                });
                return id;
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }
}
