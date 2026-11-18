"""Isolated API contracts: no user account, cookies, or external requests."""
import os
import re
import sys
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch, AsyncMock, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'apps/web/backend'))
from fastapi.testclient import TestClient
import api


class ApiTest(unittest.TestCase):
    def test_animated_artwork_keeps_static_fallback_on_failure(self):
        with patch.object(api.artwork, 'resolve', return_value='https://mvod.itunes.apple.com/test.mp4'):
            response = TestClient(api.app).get('/api/artwork?title=Song&artist=Artist')
            self.assertEqual(response.json()['url'], 'https://mvod.itunes.apple.com/test.mp4')
        with patch.object(api.artwork, 'resolve', side_effect=OSError):
            self.assertEqual(TestClient(api.app).get('/api/artwork?title=Song&artist=Artist').json(), {'url': ''})

    def setUp(self):
        self.client = TestClient(api.app)
        api.SEARCH_CACHE.clear()
        api.STREAM_CACHE.clear()

    def test_health_and_static_shell(self):
        self.assertEqual(self.client.get('/api/health').json()['status'], 'ok')
        for path in ['/', '/app.js', '/core.js', '/parity.js', '/stats.js', '/i18n.js', '/style.css', '/sw.js']:
            self.assertEqual(self.client.get(path).status_code, 200, path)

    def test_invalid_parameters_never_reach_youtube(self):
        for path in ['/api/stream/not-an-id', '/api/lyrics/bad', '/api/related/abcdefghijk?count=999', '/api/search?q=x&type=invalid']:
            self.assertEqual(self.client.get(path).status_code, 422, path)

    def test_cookie_upload_requires_admin(self):
        with patch.dict(os.environ, {'MUSICBOX_ADMIN_TOKEN':''}):
            response = self.client.post('/api/cookies/upload', files={'file':('cookies.txt', b'# Netscape HTTP Cookie File')})
            self.assertEqual(response.status_code, 403)
        self.assertNotIn('path', self.client.get('/api/cookies/status').json())

    def test_song_search_and_cache(self):
        with patch.object(api.ytmusic, 'search_songs', return_value=[{'id':'abcdefghijk'}]) as search:
            for _ in range(2):
                self.assertEqual(self.client.get('/api/search?q=Song&type=songs').json(), [{'id':'abcdefghijk'}])
            self.assertEqual(search.call_count, 1)

    def test_direct_youtube_link(self):
        with patch.object(api.ytmusic, 'post', return_value={'videoDetails':{'title':'Song','author':'Artist','lengthSeconds':'90'}}):
            result = self.client.get('/api/search', params={'q':'https://youtu.be/abcdefghijk'}).json()
            self.assertEqual(result[0]['id'], 'abcdefghijk')
            self.assertEqual(result[0]['duration'], 90)

    def test_import_rejects_arbitrary_urls(self):
        self.assertEqual(self.client.get('/api/import/preview', params={'url':'http://127.0.0.1/private'}).status_code,400)

    def test_direct_link_keeps_exact_id_if_metadata_is_unavailable(self):
        with patch.object(api.ytmusic,'post',return_value={}), patch.object(api.httpx,'AsyncClient',side_effect=RuntimeError('Offline')):
            response=self.client.get('/api/search',params={'q':'https://youtu.be/abcdefghijk'})
            self.assertEqual(response.json()[0]['id'],'abcdefghijk')

    def test_import_preview_and_match_use_native_contract(self):
        playlist = api.playlist_import.Playlist('youtube','Demo',[api.playlist_import.Track('Song','Artist',90,{'id':'abcdefghijk','title':'Song'})])
        with patch.object(api.playlist_import,'fetch',return_value=playlist):
            response = self.client.get('/api/import/preview',params={'url':'https://www.youtube.com/playlist?list=PLabcdefghijklm'})
            self.assertEqual(response.status_code,200)
            self.assertEqual(response.json()['tracks'][0]['direct']['id'],'abcdefghijk')
        with patch.object(api.playlist_import,'match',return_value={'id':'abcdefghijk'}):
            self.assertEqual(self.client.post('/api/import/match',json={'title':'Song','artist':'Artist','seconds':90}).json()['id'],'abcdefghijk')

    def test_recommendations_filter_long_mixes_and_videos(self):
        tracks = [{'id':'a', 'kind':'song','duration':180}, {'id':'b','kind':'song','duration':3600}, {'id':'c','kind':'video','duration':200}]
        with patch.object(api.ytmusic, 'find_artist', return_value=None), patch.object(api.ytmusic, 'search_songs', return_value=tracks):
            self.assertEqual([t['id'] for t in self.client.get('/api/recommendations').json()['songs']], ['a'])

    def test_search_page_returns_items_and_the_next_token(self):
        page = {'items': [{'id': 'abcdefghijk'}], 'continuation': 'TOKEN-2'}
        with patch.object(api.ytmusic, 'search_page', return_value=page) as search_page:
            first = self.client.get('/api/search/page', params={'q': ' queen ', 'type': 'videos'})
            more = self.client.get('/api/search/page', params={'q': 'queen', 'type': 'videos', 'continuation': 'TOKEN-1'})
        self.assertEqual(first.json(), page)
        self.assertEqual(more.json(), page)
        self.assertEqual(search_page.call_args_list[0].args, ('queen', 'videos', ''), 'La primera página va sin token y con el texto limpio')
        self.assertEqual(search_page.call_args_list[1].args, ('queen', 'videos', 'TOKEN-1'))
        self.assertEqual(self.client.get('/api/search/page', params={'q': '  '}).json(), {'items': [], 'continuation': ''})

    def test_search_page_failure_does_not_leak_upstream_details(self):
        with patch.object(api.ytmusic, 'search_page', side_effect=RuntimeError('private upstream detail')):
            response = self.client.get('/api/search/page', params={'q': 'queen'})
        self.assertEqual(response.status_code, 502)
        self.assertNotIn('private upstream detail', response.text)

    def test_stream_resolution_failure_is_actionable(self):
        with patch.object(api.youtube_streams, 'resolve_stream', side_effect=RuntimeError('private upstream detail')):
            response = self.client.get('/api/stream/abcdefghijk')
            self.assertEqual(response.status_code, 503)
            self.assertNotIn('private upstream detail', response.text)

    def test_head_range_closes_upstream(self):
        api.store_cached_stream('abcdefghijk','https://example.test/audio',{'Origin':'https://www.youtube.com','Referer':'https://www.youtube.com/'})
        upstream = MagicMock(status_code=206, headers={'content-type':'audio/webm','content-range':'bytes 0-9/100','content-length':'10'})
        upstream.aclose = AsyncMock()
        client = MagicMock()
        client.send = AsyncMock(return_value=upstream)
        client.aclose = AsyncMock()
        with patch.object(api.httpx, 'AsyncClient', return_value=client):
            response = self.client.head('/api/stream/abcdefghijk', headers={'Range':'bytes=0-9'})
        self.assertEqual(response.status_code,206)
        self.assertEqual(response.headers['content-range'],'bytes 0-9/100')
        upstream.aclose.assert_awaited_once()
        client.aclose.assert_awaited_once()
        self.assertEqual(client.build_request.call_args.kwargs['headers']['Range'],'bytes=0-9')
        self.assertEqual(client.build_request.call_args.kwargs['headers']['Origin'],'https://www.youtube.com')
        self.assertEqual(client.build_request.call_args.kwargs['headers']['Referer'],'https://www.youtube.com/')

    @staticmethod
    def _range_only_client(body):
        """Proveedor simulado que, como googlevideo con algunos enlaces, rechaza rangos abiertos o ausentes."""
        requested = []

        def build_request(method, url, headers):
            requested.append(headers.get('Range'))
            return headers['Range']

        async def send(range_header, stream=True):
            match = re.fullmatch(r'bytes=(\d+)-(\d+)', range_header or '')
            if not match:
                return MagicMock(status_code=403, aclose=AsyncMock())
            start, end = int(match[1]), min(int(match[2]), len(body) - 1)
            piece = body[start:end + 1]

            async def chunks(chunk_size=65536):
                yield piece

            return MagicMock(status_code=206, aiter_bytes=chunks, aclose=AsyncMock(), headers={
                'content-type': 'video/mp4', 'content-length': str(len(piece)),
                'content-range': f'bytes {start}-{end}/{len(body)}'})

        client = MagicMock(build_request=build_request, send=send, aclose=AsyncMock())
        return client, requested

    def test_open_ended_range_is_bounded_for_the_provider(self):
        api.store_cached_stream('abcdefghijk', 'https://example.test/audio', {})
        body = bytes(range(100))
        client, requested = self._range_only_client(body)
        with patch.object(api.httpx, 'AsyncClient', return_value=client), patch.object(api, 'STREAM_CHUNK', 30):
            response = self.client.get('/api/stream/abcdefghijk', headers={'Range': 'bytes=40-'})
        self.assertEqual(requested, ['bytes=40-69'])
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.headers['content-range'], 'bytes 40-69/100')
        self.assertEqual(response.content, body[40:70])

    def test_request_without_range_receives_the_whole_file_in_chunks(self):
        api.store_cached_stream('abcdefghijk', 'https://example.test/audio', {})
        body = bytes(range(100))
        client, requested = self._range_only_client(body)
        with patch.object(api.httpx, 'AsyncClient', return_value=client), patch.object(api, 'STREAM_CHUNK', 30):
            response = self.client.get('/api/stream/abcdefghijk')
        self.assertEqual(requested, ['bytes=0-29', 'bytes=30-59', 'bytes=60-89', 'bytes=90-99'])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['content-length'], '100')
        self.assertNotIn('content-range', response.headers)
        self.assertEqual(response.content, body)

    def test_video_stream_has_its_own_cache_and_resolves_the_clip(self):
        api.store_cached_stream('abcdefghijk', 'https://example.test/audio', {})
        upstream = MagicMock(status_code=206, headers={'content-type': 'video/mp4'})
        upstream.aclose = AsyncMock()
        client = MagicMock(send=AsyncMock(return_value=upstream), aclose=AsyncMock())
        with patch.object(api.httpx, 'AsyncClient', return_value=client), \
                patch.object(api.ytmusic, 'playable_video_source', return_value='12345678901'), \
                patch.object(api.youtube_streams, 'resolve_stream', return_value={'url': 'https://example.test/video'}) as resolve:
            response = self.client.head('/api/stream/abcdefghijk?video=1', headers={'Range': 'bytes=0-9'})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.headers['content-type'], 'video/mp4')
        resolve.assert_called_once_with('https://www.youtube.com/watch?v=12345678901', api._extract_info, video=True)
        self.assertEqual(api.get_cached_stream('abcdefghijk')[0], 'https://example.test/audio')

    def test_upstream_failure_invalidates_cached_url(self):
        api.store_cached_stream('abcdefghijk','https://example.test/audio',{})
        upstream = MagicMock(status_code=403)
        upstream.aclose = AsyncMock()
        client = MagicMock(send=AsyncMock(return_value=upstream), aclose=AsyncMock())
        with patch.object(api.httpx, 'AsyncClient', return_value=client):
            response = self.client.get('/api/stream/abcdefghijk')
        self.assertEqual(response.status_code,502)
        self.assertIsNone(api.get_cached_stream('abcdefghijk'))

    def test_download_format_validation_and_provider_error(self):
        self.assertEqual(self.client.get('/api/download/abcdefghijk?format=mp3&quality=1080').status_code,422)
        with patch.object(api,'prepare_download',side_effect=RuntimeError('Blocked')):
            self.assertEqual(self.client.get('/api/download/abcdefghijk').status_code,503)

    def test_completed_download_streams_file_and_cleans_only_its_directory(self):
        directory=Path(tempfile.mkdtemp(prefix='musicbox-download-'))
        output=directory/'abcdefghijk.mp3';output.write_bytes(b'MP3 fixture')
        with patch.object(api,'prepare_download',return_value=(directory,output)):
            response=self.client.get('/api/download/abcdefghijk?format=mp3&quality=192')
        self.assertEqual(response.content,b'MP3 fixture')
        self.assertEqual(response.headers['content-type'],'audio/mpeg')
        self.assertFalse(directory.exists())
        with self.assertRaises(ValueError):api.remove_download_directory(Path(tempfile.gettempdir()))


if __name__ == '__main__':
    unittest.main()
