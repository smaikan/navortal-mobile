package tr.com.navortal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Oturum açılmamışken ağsız açılan yerel karşılama ekranı. Gösterilen her şey cihaz saatinden hesaplanır.
 * Animasyonlar yalnız {@link #start()} ile {@link #stop()} arasında çalışır.
 */
final class WelcomeScreen {
    /**
     * Google girişi Android Credential Manager ile yapılacak; Android OAuth istemcisi (Firebase/Google Cloud)
     * kaydedilene kadar düğme "yakında" panelini açar. Google gömülü WebView'de OAuth'u engellediği için giriş
     * WebView'e yönlendirilmez. Hazır olunca true yapılıp {@link Listener#onGoogleSignIn()} bağlanacak.
     */
    static final boolean GOOGLE_SIGN_IN_NATIVE_READY = true;

    interface Listener {
        void onRegister();
        void onLogin();
        /** Yalnız {@link #GOOGLE_SIGN_IN_NATIVE_READY} true iken çağrılır (Credential Manager akışı). */
        void onGoogleSignIn();
        /** @param path sitedeki yol, örn. "/terms" (dış tarayıcıda açılır) */
        void onLegal(String path);
    }

    private static final int TASKS = 3;
    private static final long SWEEP_DELAY = 350, SWEEP_MS = 1500, ROW_GAP = 520, ROW_MS = 350, SEG_MS = 450, HOLD_MS = 6500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.forLanguageTag("tr-TR"));
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    private final ViewGroup parent;
    private final Context context;
    private final View root, content, sheetLayer, sheet;
    private final View hero, panel;
    private final TextView date, heroValue, heroCaption, sumLeft, sumRight;
    private final DayRingView ring;
    private final PlanBarView bar;
    private final View[] rows = new View[TASKS], dots = new View[TASKS];
    private final TextView[] titles = new TextView[TASKS], durations = new TextView[TASKS];
    private final int[] heroBase, panelBase, sheetBase;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ValueAnimator[] segAnimators = new ValueAnimator[TASKS];
    private final float[] segProgress = new float[TASKS];
    private final int muted, freeGreen, overflowColor;
    private final int[] taskColors;
    private ValueAnimator sweepAnimator;
    private boolean running, receiverRegistered;
    private Runnable pendingAction;

    private int nowMinute;
    private float sweep = 1f; // yay/nokta/geri sayım ilerlemesi (0..1, yavaşlayan eğriyle)
    private int shown = TASKS;
    private int setIndex;
    private int lastHeroMinutes = -1;
    private int oldHeroWidth = -1;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { refreshClock(); }
    };

    WelcomeScreen(ViewGroup parent, Listener listener) {
        this.parent = parent;
        context = parent.getContext();
        root = LayoutInflater.from(context).inflate(R.layout.welcome, parent, false);
        root.setClickable(true); // Alttaki WebView'e dokunuşu geçirmez.
        content = root.findViewById(R.id.welcome_content);
        sheetLayer = root.findViewById(R.id.welcome_sheet_layer);
        sheetLayer.setClickable(true); // Panelin dışındaki dokunuşlar arkadaki düğmelere gitmesin.
        sheet = root.findViewById(R.id.welcome_sheet);
        hero = root.findViewById(R.id.welcome_hero);
        panel = root.findViewById(R.id.welcome_panel);
        date = root.findViewById(R.id.welcome_date);
        heroValue = root.findViewById(R.id.welcome_hero_value);
        heroCaption = root.findViewById(R.id.welcome_hero_caption);
        sumLeft = root.findViewById(R.id.welcome_sum_left);
        sumRight = root.findViewById(R.id.welcome_sum_right);
        ring = root.findViewById(R.id.welcome_ring);
        bar = root.findViewById(R.id.welcome_bar);
        muted = context.getColor(R.color.muted);
        freeGreen = context.getColor(R.color.free_green);
        overflowColor = context.getColor(R.color.overflow);
        taskColors = new int[] {context.getColor(R.color.task_blue), context.getColor(R.color.task_green), context.getColor(R.color.task_purple)};
        heroBase = paddings(hero);
        panelBase = paddings(panel);
        sheetBase = paddings(sheet);

        var inflater = LayoutInflater.from(context);
        var list = (LinearLayout) root.findViewById(R.id.welcome_tasks);
        for (int i = 0; i < TASKS; i++) {
            rows[i] = inflater.inflate(R.layout.item_task, list, false);
            list.addView(rows[i]);
            dots[i] = rows[i].findViewById(R.id.task_dot);
            titles[i] = rows[i].findViewById(R.id.task_title);
            durations[i] = rows[i].findViewById(R.id.task_duration);
            dots[i].setBackgroundTintList(ColorStateList.valueOf(taskColors[i]));
        }

        var register = (Button) root.findViewById(R.id.welcome_register);
        withIcon(register, R.string.cta_primary, R.drawable.ic_arrow_forward, true, 18, 8);
        register.setOnClickListener(v -> listener.onRegister());
        pressFeel(register);
        root.findViewById(R.id.welcome_login).setOnClickListener(v -> listener.onLogin());
        legal(R.id.welcome_terms, "/terms", listener);
        legal(R.id.welcome_privacy, "/privacy", listener);
        legal(R.id.welcome_kvkk, "/privacy-notice", listener);
        var google = (Button) root.findViewById(R.id.welcome_google);
        withIcon(google, R.string.cta_google, R.drawable.ic_google_g, false, 18, 10);
        // Credential Manager Google Play hizmetleri ister; hizmetsiz cihazlarda (örn. Huawei) düğme hiç görünmez.
        // İleride tarayıcı tabanlı bir akış eklenirse bu koşul gözden geçirilir.
        google.setVisibility(PlayServices.available(context) ? View.VISIBLE : View.GONE);
        pressFeel(google);
        google.setOnClickListener(v -> {
            if (GOOGLE_SIGN_IN_NATIVE_READY) listener.onGoogleSignIn();
            else showSheet(R.drawable.ic_google_g, R.string.google_soon_title, R.string.google_soon_body, R.string.google_soon_primary, listener::onLogin);
        });

        root.findViewById(R.id.welcome_sheet_retry).setOnClickListener(v -> {
            var action = pendingAction;
            hideSheet();
            if (action != null) action.run();
        });
        root.findViewById(R.id.welcome_sheet_close).setOnClickListener(v -> hideSheet());
        if (Build.VERSION.SDK_INT >= 28) root.findViewById(R.id.welcome_sheet_title).setAccessibilityHeading(true);

        heroValue.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != oldHeroWidth) { oldHeroWidth = r - l; fitHeroValue(); }
        });
        showFinalState(); // start() animasyonlu açılışı baştan kurar; ilk karede boş görünmesin diye bu durumla başlar.
        parent.addView(root);
    }

    private static int[] paddings(View v) { return new int[] {v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()}; }

    /** Basınca düğme hafifçe küçülür, bırakınca yaylanarak döner (tıklamayı engellemez). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private static void pressFeel(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
                default: break;
            }
            return false;
        });
    }

    private void legal(int id, String path, Listener listener) {
        var link = (Button) root.findViewById(id);
        link.setPaintFlags(link.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        link.setOnClickListener(v -> listener.onLegal(path));
    }

    /** Metne satır içi simge ekler (düğme gravity'si ortada kalsın diye drawableStart/End yerine ImageSpan). */
    private void withIcon(Button button, int labelRes, int iconRes, boolean atEnd, float sizeSp, float gapDp) {
        var label = context.getString(labelRes);
        var metrics = context.getResources().getDisplayMetrics();
        int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics));
        int gap = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gapDp, metrics));
        var icon = new InsetDrawable(context.getDrawable(iconRes), atEnd ? gap : 0, 0, atEnd ? 0 : gap, 0);
        icon.setBounds(0, 0, size + gap, size);
        var text = new SpannableStringBuilder();
        if (!atEnd) text.append('￼');
        text.append(label);
        if (atEnd) text.append('￼');
        int at = atEnd ? text.length() - 1 : 0;
        text.setSpan(new CenteredImageSpan(icon), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
        button.setContentDescription(label); // Ekran okuyucu nesne karakterini okumasın.
    }

    // --- yaşam döngüsü ---

    boolean isSheetShowing() { return sheetLayer.getVisibility() == View.VISIBLE; }

    void applyInsets(int left, int top, int right, int bottom) {
        hero.setPadding(heroBase[0] + left, heroBase[1] + top, heroBase[2] + right, heroBase[3]);
        panel.setPadding(panelBase[0] + left, panelBase[1], panelBase[2] + right, panelBase[3] + bottom);
        sheet.setPadding(sheetBase[0] + left, sheetBase[1], sheetBase[2] + right, sheetBase[3] + bottom);
    }

    /** Saati dinlemeyi ve animasyonları (yeniden) başlatır. */
    void start() {
        if (running) return;
        running = true;
        var filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        context.registerReceiver(clockReceiver, filter);
        receiverRegistered = true;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            showFinalState();
            return;
        }
        sweep = 0f;
        shown = 0;
        setIndex = 0;
        ring.setPulsing(false);
        resetRows();
        refreshClock();
        handler.postDelayed(this::startSweep, SWEEP_DELAY);
    }

    /** Tüm animatörleri, zamanlanmış işleri ve saat alıcısını durdurur. */
    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        cancel(sweepAnimator);
        sweepAnimator = null;
        for (int i = 0; i < TASKS; i++) {
            cancel(segAnimators[i]);
            segAnimators[i] = null;
            rows[i].animate().cancel();
        }
        ring.setPulsing(false);
        if (receiverRegistered) {
            try { context.unregisterReceiver(clockReceiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
    }

    /** Ekranı kaldırır; sonrasında bu nesne kullanılmaz. */
    void dismiss() {
        stop();
        parent.removeView(root);
    }

    private static void cancel(ValueAnimator a) {
        if (a == null) return;
        a.removeAllListeners();
        a.removeAllUpdateListeners();
        a.cancel();
    }

    // --- alt panel (çevrimdışı, "Google yakında") ---

    void showOffline(Runnable retry) {
        showSheet(R.drawable.ic_wifi_off, R.string.offline_title, R.string.offline_body, R.string.offline_retry, retry);
    }

    /** Bilgi/hata paneli (örn. Google girişi sorunları). */
    void showInfo(int iconRes, int titleRes, int bodyRes, int primaryRes, Runnable primaryAction) {
        showSheet(iconRes, titleRes, bodyRes, primaryRes, primaryAction);
    }

    /** Ortak alt panel: simge, başlık, gövde, birincil düğme eylemi ve "Kapat". */
    private void showSheet(int iconRes, int titleRes, int bodyRes, int primaryRes, Runnable primaryAction) {
        pendingAction = primaryAction;
        ((ImageView) root.findViewById(R.id.welcome_sheet_icon)).setImageResource(iconRes);
        var title = (TextView) root.findViewById(R.id.welcome_sheet_title);
        title.setText(titleRes);
        ((TextView) root.findViewById(R.id.welcome_sheet_body)).setText(bodyRes);
        ((Button) root.findViewById(R.id.welcome_sheet_retry)).setText(primaryRes);
        if (Build.VERSION.SDK_INT >= 28) sheet.setAccessibilityPaneTitle(title.getText());
        sheetLayer.setVisibility(View.VISIBLE);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /** @return panel açıktıysa kapatıp true */
    boolean hideSheet() {
        if (!isSheetShowing()) return false;
        sheetLayer.setVisibility(View.GONE);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        pendingAction = null;
        return true;
    }

    // --- saat ---

    private void refreshClock() {
        var now = LocalTime.now();
        nowMinute = now.getHour() * 60 + now.getMinute();
        date.setText(DATE.format(LocalDate.now()));
        int remaining = DayPlan.remaining(nowMinute);
        ring.setContentDescription(context.getString(R.string.ring_description, DayPlan.duration(remaining), DayPlan.clock(nowMinute)));
        heroCaption.setText(context.getString(R.string.hero_caption, DayPlan.clock(nowMinute)));
        lastHeroMinutes = -1;
        renderHero();
        renderSummary();
        renderBar();
    }

    private void renderHero() {
        ring.setFraction(DayPlan.dayFraction(nowMinute) * sweep);
        int minutes = DayPlan.countdown(nowMinute, sweep);
        if (minutes == lastHeroMinutes) return;
        lastHeroMinutes = minutes;
        heroValue.setText(heroText(minutes));
    }

    private SpannableStringBuilder heroText(int minutes) {
        var text = new SpannableStringBuilder();
        if (DayPlan.showHours(minutes)) {
            text.append(String.valueOf(minutes / 60));
            unit(text, " sa  ");
        }
        text.append(String.valueOf(minutes % 60));
        unit(text, " dk");
        return text;
    }

    /**
     * Sayı boyutu, görünebilecek en geniş metne ("23 sa 59 dk") göre bir kez belirlenir; geri sayım ve dakika
     * güncellemeleri boyutu değiştirmez. Genişlik değişince yeniden hesaplanır (yazı tipi ölçeği değişince etkinlik
     * zaten yeniden oluşur).
     */
    private void fitHeroValue() {
        int available = heroValue.getWidth() - heroValue.getPaddingLeft() - heroValue.getPaddingRight();
        if (available <= 0) return;
        float max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 44, context.getResources().getDisplayMetrics());
        var paint = new TextPaint(heroValue.getPaint());
        paint.setTextSize(max);
        var widest = heroText(23 * 60 + 59);
        float width = StaticLayout.getDesiredWidth(widest, paint);
        float size = width > available ? max * available / width : max;
        heroValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(size, max * 0.4f));
    }

    private void unit(SpannableStringBuilder text, String unit) {
        int start = text.length();
        text.append(unit);
        text.setSpan(new RelativeSizeSpan(17f / 44f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(muted), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // --- plan ---

    private int[] minutes() { return setIndex == 0 ? DayPlan.SET_A : DayPlan.SET_B; }

    private void renderTasks() {
        var names = context.getResources().getStringArray(setIndex == 0 ? R.array.task_titles_a : R.array.task_titles_b);
        for (int i = 0; i < TASKS; i++) {
            titles[i].setText(names[i]);
            durations[i].setText(DayPlan.duration(minutes()[i]));
        }
    }

    private DayPlan.Summary summary() { return DayPlan.summarize(DayPlan.remaining(nowMinute), minutes(), shown); }

    private void renderSummary() {
        var s = summary();
        sumLeft.setText(s.fits() ? context.getString(R.string.plan_sum_left, DayPlan.duration(s.planned)) : context.getString(R.string.plan_sum_left_over));
        sumRight.setText(s.fits() ? context.getString(R.string.plan_sum_right, DayPlan.duration(s.free)) : context.getString(R.string.plan_sum_right_over, DayPlan.duration(s.overflow())));
        sumRight.setTextColor(s.fits() ? freeGreen : overflowColor);
    }

    private void renderBar() {
        var s = summary();
        var fractions = new float[TASKS];
        var colors = new int[TASKS];
        for (int i = 0; i < TASKS; i++) {
            fractions[i] = segProgress[i] * DayPlan.segmentFraction(minutes()[i], s.scale);
            colors[i] = s.fits() ? taskColors[i] : overflowColor;
        }
        bar.setSegments(fractions, colors);
    }

    private void resetRows() {
        renderTasks();
        for (int i = 0; i < TASKS; i++) {
            rows[i].animate().cancel();
            rows[i].setAlpha(0f);
            rows[i].setVisibility(View.INVISIBLE);
            segProgress[i] = 0f;
        }
        renderSummary();
        renderBar();
    }

    /** Animasyon kapalıyken: gün oranı, set A ve üç görev hazır, döngü yok. */
    private void showFinalState() {
        sweep = 1f;
        shown = TASKS;
        setIndex = 0;
        renderTasks();
        for (int i = 0; i < TASKS; i++) {
            rows[i].setVisibility(View.VISIBLE);
            rows[i].setAlpha(1f);
            rows[i].setTranslationY(0f);
            segProgress[i] = 1f;
        }
        refreshClock();
    }

    // --- animasyon akışı (mockup'taki start/planIn/planOut) ---

    private void startSweep() {
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f);
        sweepAnimator.setDuration(SWEEP_MS);
        sweepAnimator.setInterpolator(new DecelerateInterpolator(1.5f)); // 1-(1-t)^3
        sweepAnimator.addUpdateListener(a -> { sweep = (float) a.getAnimatedValue(); renderHero(); });
        sweepAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!running) return;
                sweep = 1f;
                renderHero();
                ring.setPulsing(true);
                planIn();
            }
        });
        sweepAnimator.start();
    }

    private void planIn() {
        for (int k = 1; k <= TASKS; k++) {
            int index = k - 1;
            handler.postDelayed(() -> showRow(index), k * ROW_GAP);
        }
        handler.postDelayed(this::planOut, TASKS * ROW_GAP + HOLD_MS);
    }

    private void planOut() {
        shown = 0;
        renderSummary();
        for (int i = 0; i < TASKS; i++) {
            var row = rows[i];
            row.animate().alpha(0f).translationY(dp(8)).setDuration(ROW_MS).setInterpolator(EASE)
                    .withEndAction(() -> row.setVisibility(View.INVISIBLE)).start();
            animateSegment(i, 0f);
        }
        handler.postDelayed(() -> {
            setIndex = (setIndex + 1) % 2;
            renderTasks();
            renderBar();
            planIn();
        }, ROW_GAP);
    }

    private void showRow(int i) {
        shown = i + 1;
        renderSummary();
        var row = rows[i];
        row.animate().cancel();
        row.setVisibility(View.VISIBLE);
        row.setAlpha(0f);
        row.setTranslationY(dp(8));
        row.animate().alpha(1f).translationY(0f).setDuration(ROW_MS).setInterpolator(EASE).start();
        animateSegment(i, 1f);
    }

    private void animateSegment(int i, float to) {
        cancel(segAnimators[i]);
        var a = ValueAnimator.ofFloat(segProgress[i], to);
        a.setDuration(SEG_MS);
        a.setInterpolator(EASE);
        a.addUpdateListener(v -> { segProgress[i] = (float) v.getAnimatedValue(); renderBar(); });
        segAnimators[i] = a;
        a.start();
    }

    private float dp(float v) { return v * context.getResources().getDisplayMetrics().density; }
}
