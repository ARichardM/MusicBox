package cl.arichard.descargador;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import cl.arichard.descargador.data.RecommendationEngine;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration changes recreate views, while this data and active work stay in memory. */
public class MainUiState extends ViewModel {
    final List<Song> library = new ArrayList<>();
    boolean libraryLoaded, splashShown, resultsVisible, searchLoading, downloadVisible, isMp3 = true;
    volatile boolean downloading;
    int discoveryScroll, resultPosition, downloadProgress, searchGeneration, completedDownloads;
    String searchQuery = "", searchStatus = "", downloadStatus = "", downloadEta = "";
    List<SearchResult> results = new ArrayList<>();
    /** Recomendaciones "Para ti": sobreviven a un giro de pantalla. */
    RecommendationEngine.Result forYou;
    boolean forYouLoading;
    /** "Para ti" ya se calculó desde que se abrió la app; volver de otra pantalla no lo recalcula. */
    boolean forYouStarted;
    int forYouGeneration;
    final Map<String, List<SearchResult>> cache = new LinkedHashMap<String, List<SearchResult>>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<SearchResult>> eldest) { return size() > 8; }
    };
    final MutableLiveData<Integer> revision = new MutableLiveData<>();
    void changed() { Integer value = revision.getValue(); revision.setValue(value == null ? 1 : value + 1); }
}
