package tr.com.navortal;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebResourceResponse;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.util.Set;
import org.json.JSONObject;

/** Yalnız birinci taraf HTTPS çerçevesinin sayaç mesajlarına erişimi var. */
public final class MainActivity extends ComponentActivity {
    static final String ORIGIN = "https://www.navortal.com.tr";
    /** getPreferences() dosyasının adı (etkinliğin yerel sınıf adı); arka plandaki push kodu aynı tercihleri okur. */
    static final String PREFS = "MainActivity";
    private WebView web;
    private TimerNotification timer;
    private ValueCallback<Uri[]> fileResult;
    private View errorView;
    private boolean loadFailed;
    private String failedUrl;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final int FILE = 9103;
    private static final String SIGNED_IN = "signed-in";
    private FrameLayout root;
    private FrameLayout webHolder; // WebView + hata ekranı; sistem çubuğu boşluklarıyla doldurulur.
    private WelcomeScreen welcome; // null değilse yerel karşılama ekranı görünür
    private LoadingScreen loading; // null değilse site yükleniyor geçişi görünür
    private int barLeft, barTop, barRight, barBottom;
    private boolean openedFromWelcome; // bu etkinlik örneğinde site karşılamadan açıldı
    private boolean clearHistoryOnFinish; // karşılamadan açılan ilk sayfanın öncesi geri listesinde kalmasın
    private boolean contentShown; // site içeriği en az bir kez görüldü (ondan önce engellenen gezinti/HTTP hatası hata sayılır)
    private boolean renderGone;
    private String pendingUrl; // en son denenen adres; "Yeniden dene" bunu yükler
    private TextView errorMessage, errorReason;
    private Button errorBack;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = () -> {
        web.stopLoading();
        showError(pendingUrl, R.string.load_timeout, WebLoad.timeoutReason());
    };

    static boolean trusted(Uri uri) {
        return "https".equals(uri.getScheme()) && "www.navortal.com.tr".equals(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        timer = new TimerNotification(this);
        PushNotifications.ensureChannel(this); // arka plandaki FCM bildirimleri bu kanala düşer
        web = new WebView(this);
        root = new FrameLayout(this);
        webHolder = new FrameLayout(this);
        webHolder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        errorView = buildErrorView();
        webHolder.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(webHolder, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (loading != null) cancelLoading();
                else if (welcome != null) { if (!welcome.hideSheet()) finish(); }
                else if (SignInState.backShowsWelcome(openedFromWelcome, getPreferences(MODE_PRIVATE).getBoolean(SIGNED_IN, false), web.canGoBack(), false)) returnToWelcome();
                else if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Karşılama/yükleme ekranları arka planını çubukların arkasına uzatır, içeriği kendisi boşlukla doldurur.
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                webHolder.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                barLeft = bars.left; barTop = bars.top; barRight = bars.right; barBottom = bars.bottom;
                if (welcome != null) welcome.applyInsets(barLeft, barTop, barRight, barBottom);
                if (loading != null) loading.applyInsets(barLeft, barTop, barRight, barBottom);
                return insets;
            });
        }
        var settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        WebView.setWebContentsDebuggingEnabled(false);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            new AlertDialog.Builder(this).setMessage("Sayaç bildirimi için Android System WebView bileşenini güncelle.")
                    .setPositiveButton("Tamam", null).show();
        } else {
            WebViewCompat.addWebMessageListener(web, "navortalTimer", Set.of(ORIGIN),
                    (view, message, sourceOrigin, mainFrame, reply) -> {
                        if (!mainFrame || !trusted(sourceOrigin) || message.getType() != WebMessageCompat.TYPE_STRING || message.getData() == null || message.getData().length() > 4096) return;
                        try { timer.accept(TimerSnapshot.parse(new JSONObject(message.getData()), System.currentTimeMillis())); }
                        catch (Exception ignored) { /* Geçersiz köprü mesajı çalışmaya veya oturuma dokunmaz. */ }
                    });
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                loadFailed = false;
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (!request.isForMainFrame()) return;
                if (welcome != null && loading == null) return; // "Vazgeç" sonrası gelen iptal hatası
                if (loadFailed) return; // ilk hata (örn. zaman aşımı) gösterildi; sonradan gelen iptal hatası onu ezmesin
                showError(request.getUrl().toString(), R.string.load_error, WebLoad.errorReason(error.getErrorCode(), error.getDescription()));
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (loadFailed || !WebLoad.httpIsError(request.isForMainFrame(), response.getStatusCode(), waitingForContent())) return;
                showError(request.getUrl().toString(), R.string.load_error, WebLoad.httpReason(response.getStatusCode()));
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (!loadFailed && waitingForContent()) showError(error.getUrl(), R.string.load_error, WebLoad.sslReason(error.getPrimaryError()));
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                renderGone = true; // WebView artık kullanılamaz; "Yeniden dene" etkinliği yeniden oluşturur.
                showError(pendingUrl, R.string.load_error, WebLoad.rendererReason());
                return true;
            }

            // İlk gerçek içerik görünür görünmez beklemeyi bırak; load olayı takılı bir alt kaynağa (örn. Google GSI) bağlı kalmasın.
            @Override public void onPageCommitVisible(WebView view, String url) {
                if (url != null && trusted(Uri.parse(url))) contentReady();
            }

            @Override public void onPageFinished(WebView view, String url) {
                trackSignIn(url);
                if (loadFailed) return;
                if (clearHistoryOnFinish) { clearHistoryOnFinish = false; view.clearHistory(); }
                contentReady();
            }

            // Next.js pushState ile gezindiği için onPageFinished tek başına yetmez.
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                trackSignIn(url);
                leaveWebLanding(url);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                var uri = request.getUrl();
                if (request.isForMainFrame() && SignInState.isLanding(uri.toString())) { leaveWebLanding(uri.toString()); return true; }
                if (trusted(uri)) return false;
                if (request.isForMainFrame()) {
                    // navortal.com.tr (www'suz) ve http biçimleri güvenilen kaynağa çevrilir; trusted() gevşemez.
                    var target = WebLoad.trustedEquivalent(uri.toString());
                    if (target != null) { view.loadUrl(target); return true; }
                    if (request.hasGesture()) external(uri);
                    else if (WebLoad.blockedIsError(true, false, waitingForContent()))
                        showError(pendingUrl, R.string.load_error, WebLoad.blockedReason(uri.getHost()));
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (loading == null) return;
                loading.setProgress(progress);
                if (progress >= 100) contentReady();
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileResult != null) fileResult.onReceiveValue(null);
                fileResult = callback;
                try { startActivityForResult(params.createIntent(), FILE); }
                catch (android.content.ActivityNotFoundException ignored) { fileResult.onReceiveValue(null); fileResult = null; }
                return true;
            }
        });
        // Süreç kapanıp yeniden oluşturulunca gezinme geçmişi geri yüklenir; çerezler WebView'de kalır.
        boolean welcomeAgain = saved != null && saved.getBoolean("welcome");
        boolean restored = !welcomeAgain && saved != null && web.restoreState(saved) != null;
        if (welcomeAgain || (!restored && !getPreferences(MODE_PRIVATE).getBoolean(SIGNED_IN, false))) showWelcome();
        else if (!restored && !(saved == null && handlePush(getIntent()))) web.loadUrl(ORIGIN + "/planning");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePush(intent);
    }

    /**
     * Push bildirimine dokunuş: oturum hâlâ açık mı ve alıcı/bağlama kayıtlıyla aynı mı diye yeniden kontrol edilir; yalnız o zaman
     * doğrulanmış "/notifications/&lt;uuid&gt;" yolu yüklenir ve "Opened" makbuzu gider. Aksi halde uygulama normal açılır.
     * @return yol WebView'e yüklendiyse true
     */
    private boolean handlePush(Intent intent) {
        var delivery = intent == null ? null : intent.getStringExtra(PushNotifications.EXTRA_DELIVERY);
        if (delivery == null) return false;
        var recipient = intent.getStringExtra(PushNotifications.EXTRA_RECIPIENT);
        long binding = intent.getLongExtra(PushNotifications.EXTRA_BINDING, -1);
        int snooze = intent.getIntExtra(PushNotifications.EXTRA_SNOOZE, 0);
        var path = PushPayload.validPath(intent.getStringExtra(PushNotifications.EXTRA_PATH));
        for (var key : new String[] {PushNotifications.EXTRA_DELIVERY, PushNotifications.EXTRA_RECIPIENT, PushNotifications.EXTRA_BINDING,
                PushNotifications.EXTRA_SNOOZE, PushNotifications.EXTRA_PATH}) intent.removeExtra(key); // yeniden oluşturmada tekrar işlenmesin
        boolean signedIn = getPreferences(MODE_PRIVATE).getBoolean(SIGNED_IN, false);
        if (!PushPayload.tapValid(recipient, binding, PushStore.boundUserId(this), PushStore.bindingVersion(this), signedIn)) return false;
        PushDelivery.sendReceipt(this, delivery, recipient, binding, snooze, "Opened");
        if (path == null || welcome != null || loading != null) return false;
        web.loadUrl(ORIGIN + path);
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
        out.putBoolean("welcome", welcome != null);
    }

    private void trackSignIn(String url) {
        var state = SignInState.fromUrl(url);
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        boolean was = prefs.getBoolean(SIGNED_IN, false);
        if (state != null && was != state) {
            prefs.edit().putBoolean(SIGNED_IN, state).apply();
            if (state) PushRegistrar.request(this, true); // giriş yapıldı: cihazı kaydet (kullanıcı /api/auth/me ile doğrulanır)
            else { // çıkış: çerez gittiği için DELETE denenmez; yalnız yerel bağlama ve görünen hatırlatmalar silinir
                PushStore.clearBinding(this);
                PushNotifications.cancelReminders(this);
            }
        }
        if (Boolean.TRUE.equals(state)) askNotificationPermission();
    }

    /** Giriş yapıldıktan sonra bildirim izni bir kez istenir (sayaç bildirimiyle ortak "sorulduysa tekrar sorma" işareti). */
    private void askNotificationPermission() {
        if (welcome != null || loading != null) return;
        var prefs = getPreferences(MODE_PRIVATE);
        if (!PushPermission.shouldAsk(android.os.Build.VERSION.SDK_INT, PushPermission.granted(this),
                prefs.getBoolean(PushPermission.ASKED_KEY, false), true)) return;
        prefs.edit().putBoolean(PushPermission.ASKED_KEY, true).apply();
        requestPermissions(new String[] {PushPermission.PERMISSION}, PushPermission.REQUEST_CODE);
    }

    /** Sitenin web karşılama sayfası uygulamada gösterilmez (ör. giriş ekranında logoya basınca). */
    private void leaveWebLanding(String url) {
        if (!SignInState.isLanding(url) || welcome != null || loading != null) return;
        if (getPreferences(MODE_PRIVATE).getBoolean(SIGNED_IN, false)) web.post(() -> web.loadUrl(ORIGIN + "/planning"));
        else web.post(this::returnToWelcome);
    }

    private void showWelcome() {
        welcome = new WelcomeScreen(root, new WelcomeScreen.Listener() {
            @Override public void onRegister() { openSite("/register"); }
            @Override public void onLogin() { openSite("/login"); }
            @Override public void onLegal(String path) { external(Uri.parse(ORIGIN + path)); }
            @Override public void onGoogleSignIn() { startGoogleSignIn(); }
        });
        welcome.applyInsets(barLeft, barTop, barRight, barBottom);
        webHolder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private GoogleSignIn google;

    /** Google Play hizmetli cihazlarda native giriş: oturum çerezi WebView'e yazılır, sonra planlama açılır. */
    private void startGoogleSignIn() {
        if (welcome == null || loading != null) return;
        if (!online()) { welcome.showOffline(this::startGoogleSignIn); return; }
        if (google == null) google = new GoogleSignIn(this);
        google.start(new GoogleSignIn.Callback() {
            @Override public void onSession(String cookie) {
                var cookies = CookieManager.getInstance();
                cookies.setCookie(ORIGIN, cookie, ok -> {
                    cookies.flush();
                    if (welcome != null) openSite("/planning");
                });
            }

            @Override public void onProblem(GoogleSignIn.Problem problem) {
                if (welcome == null || problem == GoogleSignIn.Problem.CANCELLED) return; // kullanıcı vazgeçti: sessiz
                int body;
                boolean again = false;
                switch (problem) {
                    case NO_ACCOUNT: body = R.string.google_error_no_account; break;
                    case NETWORK: body = R.string.google_error_network; again = true; break;
                    case RETRY: body = R.string.google_error_retry; again = true; break;
                    case LINK_REQUIRED: body = R.string.google_error_link_required; break;
                    case ALREADY_LINKED: body = R.string.google_error_already_linked; break;
                    case DISABLED: body = R.string.google_error_disabled; break;
                    case RATE_LIMITED: body = R.string.google_error_rate_limited; break;
                    default: body = R.string.google_error_generic; again = true;
                }
                if (again) welcome.showInfo(R.drawable.ic_google_g, R.string.google_error_title, body, R.string.google_error_retry_action, MainActivity.this::startGoogleSignIn);
                else welcome.showInfo(R.drawable.ic_google_g, R.string.google_error_title, body, R.string.google_soon_primary, () -> openSite("/login"));
            }
        });
    }

    private boolean online() {
        var cm = getSystemService(ConnectivityManager.class);
        var network = cm == null ? null : cm.getActiveNetwork();
        var caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void openSite(String path) {
        if (welcome == null || loading != null) return;
        if (!online()) { welcome.showOffline(() -> openSite(path)); return; }
        welcome.stop();
        openedFromWelcome = true;
        clearHistoryOnFinish = true;
        loading = new LoadingScreen(root, this::cancelLoading);
        loading.applyInsets(barLeft, barTop, barRight, barBottom);
        loadFailed = false;
        errorView.setVisibility(View.GONE);
        pendingUrl = ORIGIN + path;
        armWatchdog();
        web.loadUrl(pendingUrl);
    }

    /** Yükleme ekranı varken veya site içeriği henüz hiç görülmemişken bir sonuç bekleniyor demektir. */
    private boolean waitingForContent() { return loading != null || !contentShown; }

    private void armWatchdog() {
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WebLoad.WATCHDOG_MS);
    }

    private void disarmWatchdog() { watchdogHandler.removeCallbacks(watchdog); }

    /** İçerik göründü: bekleme bitti, yükleme/karşılama ekranları kalkar. Bekleyen ana çerçeve hatası varsa dokunmaz. */
    private void contentReady() {
        if (loadFailed || (welcome != null && loading == null)) return;
        disarmWatchdog();
        contentShown = true;
        pendingUrl = null;
        errorView.setVisibility(View.GONE);
        if (loading != null) closeOverlays();
    }

    /** Hata ekranını gösterir. reason yalnız ana makine adı içeren kısa teknik nedendir (günlüğe de yazılır). */
    private void showError(String url, int messageRes, String reason) {
        disarmWatchdog();
        loadFailed = true;
        if (url != null) failedUrl = url;
        Log.w("NavortalWeb", reason);
        errorMessage.setText(messageRes);
        errorReason.setText(reason);
        boolean fromWelcome = SignInState.backShowsWelcome(openedFromWelcome, getPreferences(MODE_PRIVATE).getBoolean(SIGNED_IN, false), false, false);
        errorBack.setVisibility(fromWelcome ? View.VISIBLE : View.GONE);
        closeOverlays(); // Hata ekranını örtmesinler.
        errorView.setVisibility(View.VISIBLE);
    }

    private void cancelLoading() {
        if (loading == null) return;
        disarmWatchdog();
        pendingUrl = null;
        web.stopLoading();
        loadFailed = false;
        errorView.setVisibility(View.GONE);
        loading.dismiss();
        loading = null;
        if (welcome != null) welcome.start();
    }

    /** Siteden (veya hata ekranından) karşılamaya dönüş; onStart tekrar çalışmayacağı için animasyonu kendimiz başlatırız. */
    private void returnToWelcome() {
        disarmWatchdog();
        pendingUrl = null;
        web.stopLoading();
        loadFailed = false;
        failedUrl = null;
        clearHistoryOnFinish = false;
        errorView.setVisibility(View.GONE);
        showWelcome();
        welcome.start();
    }

    private void closeOverlays() {
        if (loading != null) { loading.dismiss(); loading = null; }
        if (welcome != null) { welcome.dismiss(); welcome = null; }
        webHolder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private View buildErrorView() {
        var box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFFFFFFFF);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        errorMessage = new TextView(this);
        errorMessage.setText(R.string.load_error);
        errorMessage.setTextSize(16);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setTextColor(0xFF222222);
        errorReason = new TextView(this);
        errorReason.setTextSize(12);
        errorReason.setGravity(Gravity.CENTER);
        errorReason.setTextColor(0xFF5F5F5F);
        errorReason.setTextIsSelectable(true);
        errorReason.setPadding(0, pad / 3, 0, pad / 2);
        var retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setOnClickListener(v -> retryLoad());
        errorBack = new Button(this);
        errorBack.setText(R.string.error_back_to_welcome);
        errorBack.setOnClickListener(v -> returnToWelcome());
        errorBack.setVisibility(View.GONE);
        box.addView(errorMessage);
        box.addView(errorReason);
        box.addView(retry);
        box.addView(errorBack);
        box.setVisibility(View.GONE);
        box.setClickable(true); // Alttaki WebView'e dokunuşu geçirmez.
        return box;
    }

    private void retryLoad() {
        if (renderGone) { recreate(); return; }
        var target = pendingUrl != null ? pendingUrl
                : failedUrl != null && trusted(Uri.parse(failedUrl)) ? failedUrl : ORIGIN + "/planning";
        loadFailed = false;
        pendingUrl = target;
        errorView.setVisibility(View.GONE);
        armWatchdog();
        web.loadUrl(target);
    }

    private void external(Uri uri) {
        if (!Set.of("https", "mailto", "tel").contains(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (android.content.ActivityNotFoundException ignored) { }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == FILE && fileResult != null) {
            fileResult.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileResult = null;
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        timer.permissionResult(code);
        if (code == PushPermission.REQUEST_CODE) {
            if (PushPermission.granted(this)) PushNotifications.ensureChannel(this);
            PushRegistrar.request(this, false); // izin durumu değişti
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (welcome != null && loading == null) welcome.start();
        // Giriş açıkken: izin/kanal durumu son kayıttan farklıysa (veya 24 saat geçtiyse) kayıt yenilenir; aksi halde ağa çıkılmaz.
        if (getPreferences(MODE_PRIVATE).getBoolean(SIGNED_IN, false)) PushRegistrar.request(this, false);
        var cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                runOnUiThread(() -> { if (loadFailed && welcome == null) retryLoad(); });
            }
        };
        try { cm.registerDefaultNetworkCallback(networkCallback); }
        catch (RuntimeException ignored) { networkCallback = null; }
    }

    @Override protected void onStop() {
        if (welcome != null) welcome.stop();
        var cm = getSystemService(ConnectivityManager.class);
        if (cm != null && networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
        }
        networkCallback = null;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        timer.refresh();
        if (web != null) {
            web.onResume();
            if (web.getUrl() != null && trusted(Uri.parse(web.getUrl())))
                web.evaluateJavascript("window.dispatchEvent(new Event('navortal:resume'))", null);
        }
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (fileResult != null) fileResult.onReceiveValue(null);
        disarmWatchdog();
        if (google != null) google.destroy();
        if (welcome != null) welcome.stop();
        // Ekranın veya uygulama sürecinin kapanması sunucudaki çalışmayı bitirmez.
        web.destroy();
        super.onDestroy();
    }
}
