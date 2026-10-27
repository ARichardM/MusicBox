"""El cliente de YouTube Music entiende las respuestas reales (recortadas) y separa canciones de videos."""

import json
import unittest
from pathlib import Path

import ytmusic

FIXTURES = Path(__file__).parent / "fixtures"


def load(name):
    return json.loads((FIXTURES / f"ytmusic_{name}.json").read_text(encoding="utf-8"))


def fake_api(**responses):
    calls = []

    def api(endpoint, body):
        calls.append((endpoint, body))
        key = body.get("browseId") or ("continuation" if "continuation" in body else endpoint)
        return responses[key] if key in responses else responses[endpoint]

    api.calls = calls
    return api


class RelatedTest(unittest.TestCase):
    def test_radio_returns_only_songs_with_artist_and_album(self):
        api = fake_api(next=load("next"))
        tracks = ytmusic.related("NgEJ9gMxP90", api=api)
        self.assertEqual(api.calls[0][1]["playlistId"], "RDAMVMNgEJ9gMxP90")
        self.assertTrue(tracks)
        self.assertTrue(all(t["kind"] == "song" for t in tracks))
        self.assertNotIn("NgEJ9gMxP90", [t["id"] for t in tracks])
        first = tracks[0]
        self.assertTrue(first["artist"] and first["duration"] > 0)
        self.assertTrue(first["url"].endswith(first["id"]))

    def test_the_radio_response_is_all_audio_tracks(self):
        types = {ytmusic.kind_of(item) for item in ytmusic.walk(load("next"), "playlistPanelVideoRenderer")}
        self.assertEqual(types, {"song"})


class SearchTest(unittest.TestCase):
    def test_song_search_returns_audio_tracks_with_album_and_duration(self):
        tracks = ytmusic.search_songs("tren al sur", api=fake_api(search=load("search_songs")))
        self.assertTrue(tracks)
        top = tracks[0]
        self.assertEqual((top["title"], top["artist"], top["album"]), ("Tren Al Sur", "Los Prisioneros", "Corazones"))
        self.assertEqual(top["duration"], 337)
        self.assertEqual(top["kind"], "song")

    def test_artist_search_gives_a_photo_and_an_id(self):
        artists = ytmusic.search_artists("Los Prisioneros", api=fake_api(search=load("search_artists")))
        self.assertTrue(all(a["browse_id"].startswith("UC") and a["name"] for a in artists))
        self.assertTrue(artists[0]["photo"].startswith("https://"))
        best = ytmusic.best_artist("los prisioneros", artists)
        self.assertEqual(best["name"], "Los Prisioneros")

    def test_best_artist_falls_back_to_the_first_result(self):
        candidates = [{"name": "Otro", "browse_id": "UC1", "photo": ""}, {"name": "Otro dos", "browse_id": "UC2", "photo": ""}]
        self.assertEqual(ytmusic.best_artist("nadie", candidates)["browse_id"], "UC1")
        self.assertIsNone(ytmusic.best_artist("x", []))


class ArtistPageTest(unittest.TestCase):
    def setUp(self):
        self.page = ytmusic.artist_page("UCTDZPojhkqe4pfN9X-9ZnBw", api=fake_api(**{"UCTDZPojhkqe4pfN9X-9ZnBw": load("artist")}))

    def test_header(self):
        self.assertEqual(self.page["name"], "Los Prisioneros")
        self.assertTrue(self.page["photo"].startswith("https://"))
        self.assertTrue(self.page["subscribers"])

    def test_top_songs_and_the_playlist_with_all_of_them(self):
        self.assertEqual(self.page["top_songs"][0]["title"], "Tren Al Sur")
        self.assertTrue(all(t["kind"] == "song" and t["artist"] for t in self.page["top_songs"]))
        self.assertTrue(self.page["songs_playlist"].startswith("VL"))

    def test_albums_and_singles_are_separated(self):
        self.assertTrue(self.page["albums"] and self.page["singles"])
        self.assertTrue(all(a["browse_id"].startswith("MPRE") and a["kind"] == "album" for a in self.page["albums"]))
        self.assertTrue(all(a["kind"] == "single" for a in self.page["singles"]))
        self.assertRegex(self.page["albums"][0]["year"], r"^(19|20)\d{2}$")
        self.assertTrue(self.page["albums"][0]["thumbnail"].startswith("https://"))


class PlaylistAndAlbumTest(unittest.TestCase):
    def test_playlist_rows_become_tracks(self):
        tracks = ytmusic.playlist_tracks("VLX", api=fake_api(VLX=load("playlist")))
        self.assertEqual(tracks[0]["title"], "Tren Al Sur")
        self.assertEqual(tracks[0]["album"], "Corazones")
        self.assertEqual(tracks[0]["duration"], 337)

    def test_pagination_follows_continuations_without_repeating(self):
        row = lambda vid, title: {"musicResponsiveListItemRenderer": {
            "playlistItemData": {"videoId": vid},
            "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": title}]}}}],
            "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "3:00"}]}}}]}}
        more = lambda token: {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": token}}}}
        pages = {"VLP": {"contents": {"musicPlaylistShelfRenderer": {"contents": [row("a", "A"), row("b", "B"), more("t1")]}}},
                 "continuation": {"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": [row("b", "B"), row("c", "C")]}}]}}
        api = fake_api(**pages)
        tracks = ytmusic.playlist_tracks("VLP", api=api)
        self.assertEqual([t["id"] for t in tracks], ["a", "b", "c"])
        self.assertEqual([call[0] for call in api.calls], ["browse", "browse"])
        self.assertEqual(api.calls[1][1], {"continuation": "t1"})

    def test_limit_stops_pagination(self):
        row = lambda vid: {"musicResponsiveListItemRenderer": {"playlistItemData": {"videoId": vid},
            "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": vid}]}}}]}}
        more = {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "t"}}}}
        api = fake_api(VLP={"contents": {"musicPlaylistShelfRenderer": {"contents": [row("a"), row("b"), more]}}})
        self.assertEqual(len(ytmusic.playlist_tracks("VLP", limit=2, api=api)), 2)
        self.assertEqual(len(api.calls), 1)

    def test_album_tracks_inherit_the_album_artist_and_cover(self):
        album = ytmusic.album_page("MPREb_x", api=fake_api(MPREb_x=load("album")))
        self.assertTrue(album["title"].startswith("La Voz de los"))
        self.assertEqual(album["year"], "2014")
        self.assertTrue(album["tracks"])
        track = album["tracks"][0]
        self.assertEqual(track["album"], album["title"])
        self.assertEqual(track["thumbnail"], album["thumbnail"])
        self.assertTrue(track["duration"] > 0 and track["artist"])


class ParsingHelpersTest(unittest.TestCase):
    def test_durations(self):
        self.assertEqual((ytmusic.seconds("3:45"), ytmusic.seconds("1:02:03"), ytmusic.seconds("abc"), ytmusic.seconds(None)), (225, 3723, 0, 0))

    def test_kinds_distinguish_songs_from_videos(self):
        node = lambda t: {"watchEndpoint": {"watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": t}}}}
        self.assertEqual(ytmusic.kind_of(node("MUSIC_VIDEO_TYPE_ATV")), "song")
        self.assertEqual(ytmusic.kind_of(node("MUSIC_VIDEO_TYPE_OMV")), "video")
        self.assertEqual(ytmusic.kind_of(node("MUSIC_VIDEO_TYPE_UGC")), "video")
        self.assertEqual(ytmusic.kind_of({}), "")

    def test_artist_photos_are_requested_square(self):
        banner = "https://yt3.ggpht.com/abc=w1200-h500-l90-rj-dcX"
        self.assertEqual(ytmusic.resize_photo(banner, 544), "https://yt3.ggpht.com/abc=w544-h544-l90-rj-dcX")
        self.assertEqual(ytmusic.resize_photo("", 544), "")
        self.assertEqual(ytmusic.resize_photo("https://x/y", 544), "https://x/y")

    def test_a_row_without_id_or_title_is_ignored(self):
        self.assertIsNone(ytmusic.track_from_row({"flexColumns": []}))

    def test_related_ignores_videos_mixed_into_the_radio(self):
        def item(vid, vtype):
            return {"playlistPanelVideoRenderer": {"videoId": vid, "title": {"runs": [{"text": vid}]},
                    "longBylineText": {"runs": [{"text": "Artista"}]}, "navigationEndpoint": {"watchEndpoint": {
                        "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": vtype}}}}}}
        data = {"contents": [item("song1", "MUSIC_VIDEO_TYPE_ATV"), item("clip", "MUSIC_VIDEO_TYPE_UGC"), item("song2", "MUSIC_VIDEO_TYPE_ATV")]}
        tracks = ytmusic.related("seed", api=fake_api(next=data))
        self.assertEqual([t["id"] for t in tracks], ["song1", "song2"])


if __name__ == "__main__":
    unittest.main()
