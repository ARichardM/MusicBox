package cl.arichard.descargador.importer;

public enum ImportStatusFilter {
    ALL, READY, REVIEW, PENDING;

    public boolean matches(String state, boolean chosen) {
        if (this == ALL) return true;
        if (this == READY) return chosen;
        boolean review = "review".equals(state) || "auto".equals(state);
        return !chosen && (this == REVIEW ? review : !review);
    }
}
