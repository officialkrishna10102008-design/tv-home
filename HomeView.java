package local.tv.home;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole home screen is drawn by this one view: no blur, no video, no polling.
 * Navigation is done by hand (not by Android focus search) so it is deterministic.
 */
public class HomeView extends View {

    static class App {
        String pkg, label;
        Bitmap bmp;
        int color;
        Intent intent;
    }

    static class Item {
        String label;
        Intent intent;
    }

    private static final int TOP = 0, DOCK = 1, GRID = 2;

    private final Context ctx;
    private final float d;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Typeface outfit;
    private final Paint pClock, pBig, pBody, pSmall;
    private final Path clip = new Path();
    private final RectF tmp = new RectF();

    private final List<App> dock = new ArrayList<>();
    private final List<App> grid = new ArrayList<>();
    private int cols = 4;

    private int area = DOCK, idx = 0, lastDock = 0;
    private final RectF hl = new RectF(), hlA = new RectF(), hlB = new RectF();
    private float scroll = 0, scrollA = 0, scrollB = 0;
    private ValueAnimator anim;

    private final String[] topLabels = {"Inputs", "Wi-Fi", "Settings"};
    private final RectF[] pill = {new RectF(), new RectF(), new RectF()};
    private String netName = "Wi-Fi";
    private boolean dropOpen = false;
    private int dropIdx = 0;
    private final List<Item> inputs = new ArrayList<>();

    private String clockText = "", dateText = "";
    private float tile, gap, left, dockTop, gridTop, rowH, maxScroll;
    private App glowFor;
    private RadialGradient glow;
    private boolean dirty = false;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            dirty = true;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            invalidate();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60000 - (now % 60000) + 200);
        }
    };

    public HomeView(Context context) {
        super(context);
        ctx = context;
        d = context.getResources().getDisplayMetrics().density;
        pm = context.getPackageManager();
        prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        setFocusable(true);
        setBackgroundColor(Color.BLACK);

        try {
            Typeface t = Typeface.createFromAsset(context.getAssets(), "Outfit.ttf");
            if (t != null) outfit = t;
        } catch (Throwable ignored) {
        }
        pClock = mkPaint(200, "sans-serif-thin");
        pBig = mkPaint(250, "sans-serif-light");
        pBody = mkPaint(300, "sans-serif-light");
        pSmall = mkPaint(400, "sans-serif");

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        stroke.setColor(0x66FFFFFF);

        loadApps();
        updateNet();
        updateTime();
    }

    private Paint mkPaint(int wght, String fallback) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        if (outfit != null) {
            p.setTypeface(outfit);
            try {
                p.setFontVariationSettings("'wght' " + wght);
            } catch (Throwable ignored) {
            }
        } else {
            p.setTypeface(Typeface.create(fallback, Typeface.NORMAL));
        }
        return p;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        try {
            ctx.registerReceiver(pkgReceiver, f);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            ctx.unregisterReceiver(pkgReceiver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(tick);
    }

    public void start() {
        handler.removeCallbacks(tick);
        tick.run();
    }

    public void stop() {
        handler.removeCallbacks(tick);
    }

    public void refresh() {
        dropOpen = false;
        if (dirty) {
            dirty = false;
            loadApps();
        }
        updateNet();
        updateTime();
        if (getWidth() > 0) {
            computeLayout();
            snap();
        }
    }

    public void resetToDock() {
        dropOpen = false;
        area = DOCK;
        idx = Math.min(lastDock, Math.max(0, dock.size() - 1));
        go();
    }

    // ------------------------------------------------------------ data

    private void loadApps() {
        dock.clear();
        grid.clear();
        Map<String, App> all = new LinkedHashMap<>();
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, all);
        collect(Intent.CATEGORY_LAUNCHER, all);
        all.remove(ctx.getPackageName());

        cols = Math.max(1, ctx.getResources().getInteger(R.integer.columns));
        String[] want = ctx.getResources().getStringArray(R.array.dock_apps);
        List<App> rest = new ArrayList<>(all.values());
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));

        for (String w : want) {
            App a = all.get(w);
            if (a != null && !dock.contains(a)) dock.add(a);
        }
        rest.removeAll(dock);
        while (dock.size() < cols && !rest.isEmpty()) dock.add(rest.remove(0));
        while (dock.size() > cols) rest.add(0, dock.remove(dock.size() - 1));
        grid.addAll(rest);

        // Remember the last focused tile.
        String sel = prefs.getString("sel", null);
        area = DOCK;
        idx = 0;
        if (sel != null) {
            for (int i = 0; i < dock.size(); i++)
                if (dock.get(i).pkg.equals(sel)) {
                    area = DOCK;
                    idx = i;
                }
            for (int i = 0; i < grid.size(); i++)
                if (grid.get(i).pkg.equals(sel)) {
                    area = GRID;
                    idx = i;
                }
        }
        lastDock = area == DOCK ? idx : 0;
        glowFor = null;
    }

    private void collect(String category, Map<String, App> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        int px = Math.round(76 * d);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                Drawable dr = ri.loadIcon(pm);
                Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                dr.setBounds(0, 0, px, px);
                dr.draw(c);
                a.bmp = b;
                a.color = averageColor(b);
            } catch (Throwable t) {
                continue;
            }
            out.put(pkg, a);
        }
    }

    private int averageColor(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 1, 1, true);
        int c = s.getPixel(0, 0);
        if (s != b) s.recycle();
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.15f) return 0xFF7C8CA8;
        if (hsv[2] < 0.4f) hsv[2] = 0.65f;
        return Color.HSVToColor(hsv);
    }

    private void loadInputs() {
        inputs.clear();
        try {
            TvInputManager m = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo i : m.getInputList()) {
                if (!i.isPassthroughInput()) continue;
                Item it = new Item();
                CharSequence cl = i.loadCustomLabel(ctx);
                CharSequence l = (cl != null && cl.length() > 0) ? cl : i.loadLabel(ctx);
                it.label = String.valueOf(l);
                it.intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(i.getId()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                inputs.add(it);
            }
        } catch (Throwable ignored) {
        }
        Item live = new Item();
        live.label = "Live TV";
        live.intent = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        inputs.add(live);
    }

    private void updateNet() {
        String n = "Wi-Fi";
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                n = "Ethernet";
            } else {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                String s = wi != null ? wi.getSSID() : null;
                if (s != null && s.length() > 0 && !s.contains("unknown ssid")) {
                    if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\""))
                        s = s.substring(1, s.length() - 1);
                    n = s;
                }
            }
        } catch (Throwable ignored) {
        }
        netName = n;
        topLabels[1] = n;
    }

    private void updateTime() {
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(ctx);
        clockText = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        dateText = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        computeLayout();
        snap();
    }

    private void computeLayout() {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        tile = 76 * d;
        gap = 30 * d;
        float total = cols * tile + (cols - 1) * gap;
        left = (W - total) / 2f;
        dockTop = H - 140 * d;
        gridTop = H + 60 * d;
        rowH = tile + 64 * d;
        int rows = (grid.size() + cols - 1) / cols;
        maxScroll = Math.max(0, gridTop + rows * rowH - H - 10 * d);

        pSmall.setTextSize(15 * d);
        float x = W - 48 * d;
        for (int i = 2; i >= 0; i--) {
            float w = pSmall.measureText(topLabels[i]) + 36 * d;
            pill[i].set(x - w, 28 * d, x, 60 * d);
            x -= w + 12 * d;
        }
    }

    private RectF rect(int a, int i) {
        RectF o = new RectF();
        if (a == TOP) {
            o.set(pill[i]);
        } else if (a == DOCK) {
            float x = left + i * (tile + gap);
            o.set(x, dockTop + 24 * d, x + tile, dockTop + 24 * d + tile);
        } else {
            int row = i / cols, col = i % cols;
            float x = left + col * (tile + gap);
            float y = gridTop + row * rowH;
            o.set(x, y, x + tile, y + tile);
        }
        return o;
    }

    private RectF target() {
        RectF t = rect(area, idx);
        float inf = area == TOP ? 5 * d : 14 * d;
        t.inset(-inf, -inf);
        return t;
    }

    private float scrollFor(int a, int i) {
        if (a != GRID) return 0;
        float rowTop = gridTop + (i / cols) * rowH;
        return Math.max(0, Math.min(maxScroll, rowTop - getHeight() * 0.42f));
    }

    private void snap() {
        if (anim != null) anim.cancel();
        hl.set(target());
        scroll = scrollFor(area, idx);
        invalidate();
    }

    private void go() {
        if (area == DOCK && idx < dock.size()) {
            lastDock = idx;
            prefs.edit().putString("sel", dock.get(idx).pkg).apply();
        } else if (area == GRID && idx < grid.size()) {
            prefs.edit().putString("sel", grid.get(idx).pkg).apply();
        }
        if (getWidth() == 0) return;
        hlA.set(hl);
        hlB.set(target());
        scrollA = scroll;
        scrollB = scrollFor(area, idx);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.setInterpolator(new LinearInterpolator());
        final OvershootInterpolator os = new OvershootInterpolator(1.1f);
        final DecelerateInterpolator dc = new DecelerateInterpolator(1.6f);
        anim.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            float o = os.getInterpolation(f);
            float s = dc.getInterpolation(f);
            hl.set(hlA.left + (hlB.left - hlA.left) * o,
                    hlA.top + (hlB.top - hlA.top) * o,
                    hlA.right + (hlB.right - hlA.right) * o,
                    hlA.bottom + (hlB.bottom - hlA.bottom) * o);
            scroll = scrollA + (scrollB - scrollA) * s;
            invalidate();
        });
        anim.start();
    }

    // ------------------------------------------------------------ keys

    public boolean onKey(int code) {
        if (dropOpen) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    dropIdx = Math.max(0, dropIdx - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    dropIdx = Math.min(inputs.size() - 1, dropIdx + 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    dropOpen = false;
                    if (dropIdx < inputs.size()) open(inputs.get(dropIdx).intent);
                    break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    dropOpen = false;
                    break;
                default:
                    return false;
            }
            invalidate();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveH(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveH(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveV(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveV(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private void moveH(int dir) {
        int old = idx;
        if (area == TOP) {
            idx = Math.max(0, Math.min(2, idx + dir));
        } else if (area == DOCK) {
            idx = Math.max(0, Math.min(dock.size() - 1, idx + dir));
        } else {
            int col = idx % cols;
            if (dir < 0 && col > 0) idx--;
            if (dir > 0 && col < cols - 1 && idx + 1 < grid.size()) idx++;
        }
        if (idx != old) go();
    }

    private void moveV(int dir) {
        if (dir < 0) {
            if (area == GRID) {
                int row = idx / cols, col = idx % cols;
                if (row > 0) {
                    idx -= cols;
                } else {
                    area = DOCK;
                    idx = Math.min(col, Math.max(0, dock.size() - 1));
                }
                go();
            } else if (area == DOCK) {
                float cx = rect(DOCK, idx).centerX();
                int best = 0;
                float bd = Float.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    float dd = Math.abs(pill[i].centerX() - cx);
                    if (dd < bd) {
                        bd = dd;
                        best = i;
                    }
                }
                area = TOP;
                idx = best;
                go();
            }
        } else {
            if (area == TOP) {
                area = DOCK;
                idx = Math.min(lastDock, Math.max(0, dock.size() - 1));
                go();
            } else if (area == DOCK) {
                if (!grid.isEmpty()) {
                    area = GRID;
                    idx = Math.min(idx, grid.size() - 1);
                    go();
                }
            } else {
                int next = (idx / cols + 1) * cols;
                if (next < grid.size()) {
                    idx = Math.min(idx + cols, grid.size() - 1);
                    go();
                }
            }
        }
    }

    private void activate() {
        if (area == TOP) {
            if (idx == 0) {
                loadInputs();
                dropIdx = 0;
                dropOpen = true;
                invalidate();
            } else if (idx == 1) {
                open(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                open(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } else if (area == DOCK && idx < dock.size()) {
            open(dock.get(idx).intent);
        } else if (area == GRID && idx < grid.size()) {
            open(grid.get(idx).intent);
        }
    }

    private void open(Intent i) {
        try {
            ctx.startActivity(i);
        } catch (Throwable t) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        int W = getWidth(), H = getHeight();
        c.save();
        c.translate(0, -scroll);

        // Ambient glow behind the focused dock app, colour sampled from its icon.
        App cur = (area == DOCK && idx < dock.size()) ? dock.get(idx) : null;
        if (cur != null) {
            if (glowFor != cur) {
                glowFor = cur;
                glow = new RadialGradient(W / 2f, dockTop - 70 * d, 320 * d,
                        (0x66 << 24) | (cur.color & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP);
            }
            fill.setShader(glow);
            c.drawCircle(W / 2f, dockTop - 70 * d, 320 * d, fill);
            fill.setShader(null);
        }

        // Greeting, clock, date.
        pBody.setTextAlign(Paint.Align.LEFT);
        pBody.setColor(0xB3FFFFFF);
        pBody.setTextSize(18 * d);
        c.drawText(greeting(), 50 * d, 56 * d, pBody);
        pClock.setTextAlign(Paint.Align.LEFT);
        pClock.setColor(Color.WHITE);
        pClock.setTextSize(76 * d);
        c.drawText(clockText, 46 * d, 136 * d, pClock);
        pBody.setTextSize(18 * d);
        c.drawText(dateText, 50 * d, 168 * d, pBody);

        // Frosted pills (flat translucent fills, no blur).
        pSmall.setTextAlign(Paint.Align.CENTER);
        pSmall.setTextSize(15 * d);
        pSmall.setColor(Color.WHITE);
        for (int i = 0; i < 3; i++) {
            RectF p = pill[i];
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(p, p.height() / 2, p.height() / 2, fill);
            c.drawText(topLabels[i], p.centerX(), p.centerY() + 5.2f * d, pSmall);
        }

        // Dock band.
        float total = cols * tile + (cols - 1) * gap;
        tmp.set(left - 26 * d, dockTop, left + total + 26 * d, H - 16 * d);
        fill.setColor(0x2EFFFFFF);
        c.drawRoundRect(tmp, 30 * d, 30 * d, fill);

        // The single glass highlight that springs between tiles.
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(hl, 20 * d, 20 * d, fill);
        c.drawRoundRect(hl, 20 * d, 20 * d, stroke);

        for (int i = 0; i < dock.size(); i++) {
            drawTile(c, dock.get(i), rect(DOCK, i), area == DOCK && idx == i);
        }

        if (cur != null) {
            pBig.setTextAlign(Paint.Align.CENTER);
            pBig.setColor(Color.WHITE);
            pBig.setTextSize(38 * d);
            c.drawText(cur.label, W / 2f, dockTop - 40 * d, pBig);
        }

        pSmall.setTextSize(15 * d);
        for (int i = 0; i < grid.size(); i++) {
            RectF t = rect(GRID, i);
            if (t.top - scroll > H || t.bottom - scroll < 0) continue;
            boolean f = area == GRID && idx == i;
            drawTile(c, grid.get(i), t, f);
            if (f) {
                pSmall.setColor(Color.WHITE);
                String s = TextUtils.ellipsize(grid.get(i).label, new android.text.TextPaint(pSmall),
                        tile + gap - 6 * d, TextUtils.TruncateAt.END).toString();
                c.drawText(s, t.centerX(), t.bottom + 32 * d, pSmall);
            }
        }

        if (dropOpen) drawDropdown(c, W);
        c.restore();
    }

    private void drawTile(Canvas c, App a, RectF t, boolean focus) {
        float s = focus ? 1.1f : 1f;
        c.save();
        c.scale(s, s, t.centerX(), t.centerY());
        clip.reset();
        clip.addRoundRect(t, 16 * d, 16 * d, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(a.bmp, null, t, bmpPaint);
        c.restore();
    }

    private void drawDropdown(Canvas c, int W) {
        RectF pr = pill[0];
        float w = 250 * d, rowh = 42 * d;
        float x = Math.min(pr.left, W - 48 * d - w);
        float top = pr.bottom + 8 * d;
        tmp.set(x, top, x + w, top + inputs.size() * rowh + 12 * d);
        fill.setColor(0xF01C1C1E);
        c.drawRoundRect(tmp, 16 * d, 16 * d, fill);
        pSmall.setTextAlign(Paint.Align.LEFT);
        pSmall.setTextSize(16 * d);
        pSmall.setColor(Color.WHITE);
        for (int i = 0; i < inputs.size(); i++) {
            float y = top + 6 * d + i * rowh;
            if (i == dropIdx) {
                tmp.set(x + 6 * d, y, x + w - 6 * d, y + rowh);
                fill.setColor(0x4DFFFFFF);
                c.drawRoundRect(tmp, 12 * d, 12 * d, fill);
            }
            c.drawText(inputs.get(i).label, x + 20 * d, y + rowh / 2 + 5.5f * d, pSmall);
        }
    }
}
