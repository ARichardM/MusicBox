"""Google/Firebase session and small Firestore state document for MusicBox Desktop."""

from __future__ import annotations

import json
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


from app_paths import RESOURCE_ROOT

OAUTH_FILE = RESOURCE_ROOT / "desktop_oauth_client.json"
FIREBASE_CONFIG_FILE = RESOURCE_ROOT / "desktop_firebase_config.json"
GOOGLE_SCOPES = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/userinfo.profile",
]


def _post_json(url, payload, headers=None, method="POST"):
    data = json.dumps(payload).encode("utf-8")
    request = Request(url, data=data, headers={"Content-Type": "application/json", **(headers or {})}, method=method)
    try:
        with urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise RuntimeError(json.loads(detail).get("error", {}).get("message", detail)) from exc


def _api_config():
    config = json.loads(FIREBASE_CONFIG_FILE.read_text(encoding="utf-8"))
    project_id = str(config.get("project_id", "")).strip()
    api_key = str(config.get("api_key", "")).strip()
    if not project_id or not api_key:
        raise RuntimeError(
            "La configuración de Firebase para escritorio está incompleta.")
    return project_id, api_key


class FirebaseDesktopClient:
    def __init__(self, refresh_token=""):
        self.project_id, self.api_key = _api_config()
        self.refresh_token = refresh_token
        self.id_token = ""
        self.user = {}

    @property
    def configured(self):
        return OAUTH_FILE.is_file() and FIREBASE_CONFIG_FILE.is_file()

    def sign_in(self):
        from google_auth_oauthlib.flow import InstalledAppFlow

        flow = InstalledAppFlow.from_client_secrets_file(
            str(OAUTH_FILE), scopes=GOOGLE_SCOPES)
        credentials = flow.run_local_server(
            host="127.0.0.1", port=0, open_browser=True,
            authorization_prompt_message="",
            success_message=(
                "<html><body style='background:#111;color:#fff;font-family:sans-serif;"
                "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                "<p>✔ Sesión iniciada. Cerrando…</p>"
                "<script>setTimeout(function(){window.close()},800)</script>"
                "</body></html>"
            ))
        if not credentials.id_token:
            raise RuntimeError("Google no devolvió un token de identidad.")
        response = _post_json(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=" + self.api_key,
            {"postBody": "id_token=" + credentials.id_token + "&providerId=google.com",
             "requestUri": "http://localhost", "returnIdpCredential": True,
             "returnSecureToken": True})
        self._accept(response)
        return self.user

    def restore(self):
        if not self.refresh_token:
            return None
        body = urlencode({"grant_type": "refresh_token", "refresh_token": self.refresh_token}).encode()
        request = Request("https://securetoken.googleapis.com/v1/token?key=" + self.api_key,
                          data=body, headers={"Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urlopen(request, timeout=20) as response:
                token = json.loads(response.read().decode("utf-8"))
            self.id_token = token["id_token"]
            self.refresh_token = token.get("refresh_token", self.refresh_token)
            info = _post_json("https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" + self.api_key,
                              {"idToken": self.id_token})["users"][0]
            self.user = {"uid": info["localId"], "name": info.get("displayName", ""),
                         "email": info.get("email", ""), "photo": info.get("photoUrl", "")}
            return self.user
        except Exception:
            self.id_token = ""
            self.user = {}
            return None

    def _accept(self, response):
        self.id_token = response["idToken"]
        self.refresh_token = response["refreshToken"]
        self.user = {"uid": response["localId"], "name": response.get("displayName", ""),
                     "email": response.get("email", ""), "photo": response.get("photoUrl", "")}

    def _document_url(self):
        uid = self.user["uid"]
        return (f"https://firestore.googleapis.com/v1/projects/{self.project_id}/databases/(default)"
                f"/documents/users/{uid}/state/musicbox")

    def load_state(self):
        if not self.id_token or not self.user:
            return {}
        request = Request(self._document_url(), headers={"Authorization": "Bearer " + self.id_token})
        try:
            with urlopen(request, timeout=20) as response:
                document = json.loads(response.read().decode("utf-8"))
            return json.loads(document.get("fields", {}).get("payload", {}).get("stringValue", "{}"))
        except HTTPError as exc:
            if exc.code == 404:
                return {}
            raise

    def save_state(self, state):
        if not self.id_token or not self.user:
            return
        _post_json(self._document_url() + "?updateMask.fieldPaths=payload", {
            "fields": {"payload": {"stringValue": json.dumps(state, ensure_ascii=False, separators=(",", ":"))}}
        }, {"Authorization": "Bearer " + self.id_token}, method="PATCH")

