package cl.arichard.descargador;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.adapter.SearchResultAdapter;
import cl.arichard.descargador.model.SearchResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class SearchRenderingTest {
    @Test public void repeatedHomeRefreshFinishesPendingRecentAndLibraryCards() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity = controller.get();
            java.util.List<cl.arichard.descargador.model.Song> songs = java.util.Arrays.asList(
                    new cl.arichard.descargador.model.Song(1, "Primera", "Prueba", "", 180000, "/fixture/first.wav", "Prueba", 0),
                    new cl.arichard.descargador.model.Song(2, "Segunda", "Prueba", "", 180000, "/fixture/second.wav", "Prueba", 0));
            for (String kind : new String[]{"RecentPlays", "TopLibrary"}) {
                Field generation = MainActivity.class.getDeclaredField(kind.equals("RecentPlays")
                        ? "recentPlaysGeneration" : "topLibraryGeneration");
                generation.setAccessible(true);
                Method render = MainActivity.class.getDeclaredMethod("render" + kind, java.util.List.class);
                render.setAccessible(true);
                generation.setInt(activity, 1);
                render.invoke(activity, songs);
                // Otro resultado idéntico llega antes de que se creen las tarjetas diferidas.
                generation.setInt(activity, 2);
                render.invoke(activity, songs);
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
                android.widget.LinearLayout cards = activity.findViewById(kind.equals("RecentPlays")
                        ? R.id.layoutRecentPlaysCards : R.id.layoutTopLibraryList);
                assertEquals(kind, 2, cards.getChildCount());
                generation.setInt(activity, 3);
                render.invoke(activity, songs);
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
                assertEquals("Sin duplicados al refrescar " + kind, 2, cards.getChildCount());
            }
        }
    }

    private MainUiState state(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("uiState");
        field.setAccessible(true);
        return (MainUiState) field.get(activity);
    }

    private void render(MainActivity activity) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("renderSearchFilter");
        method.setAccessible(true);
        method.invoke(activity);
    }

    @Test public void resultsNeverOverlapDiscoveryOrTheFullPageSkeleton() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            MainUiState state = state(activity);
            state.results = new ArrayList<>(Collections.singletonList(
                    new SearchResult("abcdefghijk", "Way Too Self Aware", "Ian Asher", 157, "", "")));
            state.resultsVisible = true;
            state.searchLoading = true;
            render(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.searchLoading).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());

            state.searchLoading = false;
            render(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.searchLoading).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());

            state.loadingMore = true;
            render(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.searchLoading).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());

            state.searchFilter = "artists";
            render(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.scrollSearchArtists).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
        }
    }

    @Test public void emptySearchOrErrorKeepsDiscoveryHiddenUntilSearchIsCleared() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            MainUiState state = state(activity);
            state.searchStatus = "Sin conexión a internet";
            state.resultsVisible = false;
            state.searchLoading = false;
            render(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.searchLoading).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());
            state.loadingMore = true;
            state.searchContinuation = "pagina-pendiente";
            Method clear = MainActivity.class.getDeclaredMethod("clearSearch");
            clear.setAccessible(true);
            clear.invoke(activity);
            render(activity);
            assertFalse(state.loadingMore);
            assertEquals("", state.searchContinuation);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.searchLoading).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());
        }
    }

    @Test public void scrollDoesNotMutateTheAdapterDuringTheCallback() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            MainUiState state = state(activity);
            state.resultsVisible = true;
            state.hasMoreResults = true;
            state.searchContinuation = "next";
            state.activePagedSearchQuery = "query";
            RecyclerView rows = activity.findViewById(R.id.recyclerSearchResults);
            Method dispatch = RecyclerView.class.getDeclaredMethod("dispatchOnScrolled", int.class, int.class);
            dispatch.setAccessible(true);
            int generation = state.searchGeneration;
            dispatch.invoke(rows, 0, 16);
            assertFalse(state.loadingMore);
            assertEquals(generation, state.searchGeneration);

            // A filter change before the posted pagination runs invalidates it.
            state.searchGeneration++;
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(state.loadingMore);
            assertEquals(generation + 1, state.searchGeneration);
        }
    }

    @Test public void paginationFooterBindsWithItsOwnLayout() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        RecyclerView parent = new RecyclerView(context);
        SearchResultAdapter adapter = new SearchResultAdapter(context, null);
        adapter.setLoadingMore(true);
        RecyclerView.ViewHolder holder = adapter.createViewHolder(parent, adapter.getItemViewType(0));
        adapter.bindViewHolder(holder, 0);
        assertNotNull(holder.itemView.findViewById(R.id.searchLoadingMoreRing));
        assertNull(holder.itemView.findViewById(R.id.thumbFrame));
        adapter.onViewRecycled(holder);
    }
}
