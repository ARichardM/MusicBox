"""La página de retorno de Google debe servirse como HTML y conservar la URL de autorización."""

import unittest

import musicbox_cloud


class LoginCallbackTests(unittest.TestCase):
    def test_callback_is_html_and_records_request(self):
        sent = {}
        app = musicbox_cloud._HtmlRedirectApp(musicbox_cloud.SIGN_IN_DONE_PAGE)
        environ = {"wsgi.url_scheme": "http", "HTTP_HOST": "127.0.0.1:5000", "PATH_INFO": "/",
                   "QUERY_STRING": "code=abc&state=xyz", "SCRIPT_NAME": ""}
        body = app(environ, lambda status, headers: sent.update(status=status, headers=dict(headers)))
        self.assertTrue(sent["headers"]["Content-Type"].startswith("text/html"))
        self.assertIn("code=abc", app.last_request_uri)
        self.assertIn(b"<html", body[0].lower())


if __name__ == "__main__":
    unittest.main()
