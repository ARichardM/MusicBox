package cl.arichard.descargador;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

public class LoginActivity extends AppCompatActivity {
    public static final String EXTRA_REVOKED = "revoked";
    private Button login;
    private View progress;
    private TextView error;
    private cl.arichard.descargador.util.AnimatedSplashView animatedLogo;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Se retira el fundido propio del splash del sistema: la app ya dibuja el mismo cubo en el mismo sitio.
        if (android.os.Build.VERSION.SDK_INT >= 31) getSplashScreen().setOnExitAnimationListener(view -> view.remove());
        // This build has no Firebase configuration by design and must remain safe for automated/manual verification.
        if (MainActivity.isVerificationPackage(getPackageName())) { openApp(); return; }
        if (FirebaseAuth.getInstance().getCurrentUser() != null) { openApp(); return; }
        setContentView(R.layout.activity_login);
        login = findViewById(R.id.btnGoogleLogin);
        progress = findViewById(R.id.loginProgress);
        error = findViewById(R.id.txtLoginError);
        FrameLayout stage = findViewById(R.id.loginLogoStage);
        animatedLogo = new cl.arichard.descargador.util.AnimatedSplashView(this, false);
        stage.addView(animatedLogo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        login.setOnClickListener(v -> beginGoogleLogin(false));
        if (getIntent().getBooleanExtra(EXTRA_REVOKED, false)) {
            showError(cl.arichard.descargador.util.Tr.t("Tu sesión se cerró desde otro dispositivo."));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (animatedLogo != null) animatedLogo.start();
    }

    @Override protected void onStop() {
        if (animatedLogo != null) animatedLogo.stop();
        super.onStop();
    }

    private void beginGoogleLogin(boolean authorizedOnly) {
        setLoading(true);
        GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(authorizedOnly)
                .setServerClientId(getString(R.string.default_web_client_id))
                .setAutoSelectEnabled(authorizedOnly).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(this).getCredentialAsync(this, request, new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse result) { handleCredential(result.getCredential()); }
                    @Override public void onError(@NonNull GetCredentialException e) {
                        if (e instanceof NoCredentialException) {
                            showError(cl.arichard.descargador.util.Tr.t("No hay una cuenta de Google disponible en este dispositivo."));
                            return;
                        }
                        android.util.Log.e("LoginActivity", "GetCredential error: ", e);
                        showError(cl.arichard.descargador.util.Tr.t("No se pudo iniciar sesión: ") + e.getMessage());
                    }
                });
    }

    private void handleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential) ||
                !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            showError(cl.arichard.descargador.util.Tr.t("Google devolvió una credencial no compatible.")); return;
        }
        try {
            GoogleIdTokenCredential google = GoogleIdTokenCredential.createFrom(credential.getData());
            FirebaseAuth.getInstance().signInWithCredential(
                    GoogleAuthProvider.getCredential(google.getIdToken(), null))
                    .addOnSuccessListener(result -> {
                        cl.arichard.descargador.data.DeviceSessions.markSignedIn(this);
                        openApp();
                    })
                    .addOnFailureListener(e -> {
                        android.util.Log.e("LoginActivity", "Firebase rejected login: ", e);
                        showError(cl.arichard.descargador.util.Tr.t("Firebase rechazó el inicio de sesión: ") + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    });
        } catch (Exception e) {
            android.util.Log.e("LoginActivity", "Error validating Google account: ", e);
            showError(cl.arichard.descargador.util.Tr.t("No se pudo validar la cuenta de Google: ") + e.getMessage());
        }
    }

    private void setLoading(boolean loading) {
        login.setEnabled(!loading); progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        error.setVisibility(View.GONE);
    }
    private void showError(String message) {
        setLoading(false); error.setText(message); error.setVisibility(View.VISIBLE);
    }
    private void openApp() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        overridePendingTransition(0, 0);
    }
}
