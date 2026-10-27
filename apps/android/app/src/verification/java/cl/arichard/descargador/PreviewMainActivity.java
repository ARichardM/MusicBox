package cl.arichard.descargador;

/** Solo se empaqueta en verificación: sin cuenta, sin sincronización y con almacenamiento independiente. */
public final class PreviewMainActivity extends MainActivity {
    @Override protected boolean requiresSignIn() { return false; }
    @Override protected boolean loadsRecommendationsOnStart() { return false; }
}
