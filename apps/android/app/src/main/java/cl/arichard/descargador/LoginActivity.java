package cl.arichard.descargador;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Property;
import android.view.View;
import android.widget.Button;
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
    private Button login;
    private View progress;
    private TextView error;
    private View logo;
    private View auraInner;
    private View auraOuter;
    private AnimatorSet logoAnimation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (FirebaseAuth.getInstance().getCurrentUser() != null) { openApp(); return; }
        setContentView(R.layout.activity_login);
        login = findViewById(R.id.btnGoogleLogin);
        progress = findViewById(R.id.loginProgress);
        error = findViewById(R.id.txtLoginError);
        logo = findViewById(R.id.loginLogo);
        auraInner = findViewById(R.id.loginLogoAuraInner);
        auraOuter = findViewById(R.id.loginLogoAuraOuter);
        login.setOnClickListener(v -> beginGoogleLogin(false));
    }

    @Override protected void onStart() {
        super.onStart();
        startLogoAnimation();
    }

    @Override protected void onStop() {
        stopLogoAnimation();
        super.onStop();
    }

    private ObjectAnimator pulse(View view, Property<View, Float> property,
                                 float from, float to, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, from, to);
        animator.setDuration(duration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        return animator;
    }

    private void startLogoAnimation() {
        if (logo == null || logoAnimation != null) return;
        ObjectAnimator floatUp = pulse(logo, View.TRANSLATION_Y, dp(4), dp(-4), 1900);
        ObjectAnimator logoScaleX = pulse(logo, View.SCALE_X, 0.98f, 1.035f, 1900);
        ObjectAnimator logoScaleY = pulse(logo, View.SCALE_Y, 0.98f, 1.035f, 1900);
        ObjectAnimator innerAlpha = pulse(auraInner, View.ALPHA, 0.22f, 0.58f, 1500);
        ObjectAnimator innerScaleX = pulse(auraInner, View.SCALE_X, 0.90f, 1.10f, 1500);
        ObjectAnimator innerScaleY = pulse(auraInner, View.SCALE_Y, 0.90f, 1.10f, 1500);
        ObjectAnimator outerAlpha = pulse(auraOuter, View.ALPHA, 0.10f, 0.34f, 2200);
        ObjectAnimator outerScaleX = pulse(auraOuter, View.SCALE_X, 0.86f, 1.08f, 2200);
        ObjectAnimator outerScaleY = pulse(auraOuter, View.SCALE_Y, 0.86f, 1.08f, 2200);
        outerAlpha.setStartDelay(350);
        outerScaleX.setStartDelay(350);
        outerScaleY.setStartDelay(350);
        logoAnimation = new AnimatorSet();
        logoAnimation.playTogether(floatUp, logoScaleX, logoScaleY, innerAlpha,
                innerScaleX, innerScaleY, outerAlpha, outerScaleX, outerScaleY);
        logoAnimation.start();
    }

    private void stopLogoAnimation() {
        if (logoAnimation == null) return;
        logoAnimation.cancel();
        logoAnimation = null;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
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
                            showError("No hay una cuenta de Google disponible en este dispositivo.");
                            return;
                        }
                        android.util.Log.e("LoginActivity", "GetCredential error: ", e);
                        showError("No se pudo iniciar sesión: " + e.getMessage());
                    }
                });
    }

    private void handleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential) ||
                !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            showError("Google devolvió una credencial no compatible."); return;
        }
        try {
            GoogleIdTokenCredential google = GoogleIdTokenCredential.createFrom(credential.getData());
            FirebaseAuth.getInstance().signInWithCredential(
                    GoogleAuthProvider.getCredential(google.getIdToken(), null))
                    .addOnSuccessListener(result -> openApp())
                    .addOnFailureListener(e -> {
                        android.util.Log.e("LoginActivity", "Firebase rejected login: ", e);
                        showError("Firebase rechazó el inicio de sesión: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    });
        } catch (Exception e) {
            android.util.Log.e("LoginActivity", "Error validating Google account: ", e);
            showError("No se pudo validar la cuenta de Google: " + e.getMessage());
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
    }
}
