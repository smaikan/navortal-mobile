package tr.com.navortal;

import android.app.Activity;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Play hizmetli cihazlarda native Google girişi (Credential Manager). Sıra: config (nonce) → Google hesap seçici →
 * session/google. Google sayfası WebView'de açılmaz. Token ve çerez hiçbir yere yazılmaz.
 */
final class GoogleSignIn {
    enum Problem { NONE, CANCELLED, NO_ACCOUNT, NETWORK, RETRY, LINK_REQUIRED, ALREADY_LINKED, DISABLED, RATE_LIMITED, OTHER }

    interface Callback {
        /** @param cookie WebView çerez deposuna yazılacak oturum çerezi */
        void onSession(String cookie);
        void onProblem(Problem problem);
    }

    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private CancellationSignal cancel;
    private boolean busy, destroyed;

    GoogleSignIn(Activity activity) { this.activity = activity; }

    boolean busy() { return busy; }

    void start(Callback callback) {
        if (busy || destroyed) return;
        busy = true;
        io.execute(() -> {
            try {
                var config = GoogleAuthApi.fetchConfig(); // her denemede yeni nonce/challenge; önbelleğe alınmaz
                if (!config.enabled) { finish(callback, Problem.DISABLED); return; }
                main.post(() -> requestCredential(config, callback));
            } catch (GoogleAuthApi.Failure f) {
                finish(callback, problemFor(f.kind));
            }
        });
    }

    private void requestCredential(GoogleAuthApi.Config config, Callback callback) {
        if (destroyed) return;
        var option = new GetGoogleIdOption.Builder()
                .setServerClientId(config.clientId) // Web istemci kimliği (Android istemcisi değil)
                .setNonce(config.nonce)             // sunucunun nonce'u aynen; yeniden üretilmez, hash'lenmez
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build();
        var request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        cancel = new CancellationSignal();
        CredentialManager.create(activity).getCredentialAsync(activity, request, cancel, io,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse result) { onCredential(result, config, callback); }
                    @Override public void onError(GetCredentialException e) {
                        finish(callback, e instanceof GetCredentialCancellationException ? Problem.CANCELLED
                                : e instanceof NoCredentialException ? Problem.NO_ACCOUNT : Problem.OTHER);
                    }
                });
    }

    private void onCredential(GetCredentialResponse result, GoogleAuthApi.Config config, Callback callback) {
        var credential = result.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            finish(callback, Problem.OTHER);
            return;
        }
        try {
            var token = GoogleIdTokenCredential.Companion.createFrom(credential.getData()).getIdToken();
            var cookie = GoogleAuthApi.login(token, config);
            main.post(() -> { busy = false; if (!destroyed) callback.onSession(cookie); });
        } catch (GoogleAuthApi.Failure f) {
            finish(callback, problemFor(f.kind));
        } catch (Exception e) { // GoogleIdTokenParsingException (Kotlin, Java'ya bildirilmez) dahil
            finish(callback, Problem.OTHER);
        }
    }

    private void finish(Callback callback, Problem problem) {
        main.post(() -> { busy = false; if (!destroyed) callback.onProblem(problem); });
    }

    static Problem problemFor(GoogleAuthApi.Failure.Kind kind) {
        switch (kind) {
            case NETWORK: return Problem.NETWORK;
            case DISABLED: return Problem.DISABLED;
            case RETRY_LOGIN: return Problem.RETRY;
            case LINK_REQUIRED: return Problem.LINK_REQUIRED;
            case ALREADY_LINKED: return Problem.ALREADY_LINKED;
            case RATE_LIMITED: return Problem.RATE_LIMITED;
            default: return Problem.OTHER;
        }
    }

    void destroy() {
        destroyed = true;
        if (cancel != null) cancel.cancel();
        io.shutdown();
    }
}
