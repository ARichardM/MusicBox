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


class StateConflict(RuntimeError):
    """El documento cambió entre la lectura y la escritura; se vuelve a mezclar."""


def _post_json(url, payload, headers=None, method="POST"):
    data = json.dumps(payload).encode("utf-8")
    request = Request(url, data=data, headers={"Content-Type": "application/json", **(headers or {})}, method=method)
    try:
        with urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        if exc.code in (409, 412):
            raise StateConflict("El estado cambió durante la sincronización") from exc
        raise RuntimeError(json.loads(detail).get("error", {}).get("message", detail)) from exc


def _api_config():
    config = json.loads(FIREBASE_CONFIG_FILE.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise RuntimeError("La configuración de Firebase para escritorio está incompleta.")
    project_id = str(config.get("project_id", "")).strip()
    api_key = str(config.get("api_key", "")).strip()
    if not project_id or not api_key:
        raise RuntimeError(
            "La configuración de Firebase para escritorio está incompleta.")
    return project_id, api_key


SIGN_IN_DONE_PAGE = (
    "<!doctype html><html><head><meta charset='utf-8'><title>MusicBox</title></head>"
    "<body style=\"margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
    "justify-content:center;gap:10px;background:#0E1116;color:#fff;font-family:'Segoe UI',sans-serif\">"
    "<div style='font-size:42px;color:#50E9FF'>&#10003;</div>"
    "<div style='font-size:20px;font-weight:600'>Sesi&oacute;n iniciada / Signed in</div>"
    "<div style='font-size:14px;color:#8E93A3'>Ya puedes cerrar esta pesta&ntilde;a y volver a MusicBox."
    "<br>You can close this tab and return to MusicBox.</div>"
    "<script>setTimeout(function(){window.close()},600)</script></body></html>"
)


class _HtmlRedirectApp:
    """Respuesta de retorno de Google servida como HTML y no como texto plano."""

    def __init__(self, success_message):
        self.last_request_uri = None
        self._success_message = success_message

    def __call__(self, environ, start_response):
        import wsgiref.util
        start_response("200 OK", [("Content-Type", "text/html; charset=utf-8")])
        self.last_request_uri = wsgiref.util.request_uri(environ)
        return [self._success_message.encode("utf-8")]


class FirebaseDesktopClient:
    def __init__(self, refresh_token=""):
        self.project_id, self.api_key = "", ""
        try:
            self.project_id, self.api_key = _api_config()
        except (OSError, ValueError, RuntimeError):
            pass
        self.refresh_token = refresh_token
        self.id_token = ""
        self.user = {}

    @property
    def configured(self):
        return bool(self.project_id and self.api_key and OAUTH_FILE.is_file() and FIREBASE_CONFIG_FILE.is_file())

    def _ensure_api_config(self):
        if self.project_id and self.api_key:
            return
        try:
            self.project_id, self.api_key = _api_config()
        except (OSError, ValueError) as exc:
            raise RuntimeError("El inicio de sesión no está configurado en esta instalación.") from exc

    def sign_in(self):
        self._ensure_api_config()
        if not OAUTH_FILE.is_file():
            raise RuntimeError("El inicio de sesión no está configurado en esta instalación.")
        from google_auth_oauthlib.flow import InstalledAppFlow

        flow = InstalledAppFlow.from_client_secrets_file(
            str(OAUTH_FILE), scopes=GOOGLE_SCOPES)
        import google_auth_oauthlib.flow as flow_module
        original_app = flow_module._RedirectWSGIApp
        flow_module._RedirectWSGIApp = _HtmlRedirectApp
        try:
            credentials = flow.run_local_server(
                host="127.0.0.1", port=0, open_browser=True,
                authorization_prompt_message="", success_message=SIGN_IN_DONE_PAGE)
        finally:
            flow_module._RedirectWSGIApp = original_app
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
        self._ensure_api_config()
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

    def _document_url(self, name="musicbox"):
        uid = self.user["uid"]
        return (f"https://firestore.googleapis.com/v1/projects/{self.project_id}/databases/(default)"
                f"/documents/users/{uid}/state/{name}")

    @staticmethod
    def revocation_field(device_id):
        return "d_" + device_id

    def load_revocations(self):
        """Marcas de cierre de sesión remoto: {campo: milisegundos}."""
        if not self.id_token or not self.user:
            return {}
        request = Request(self._document_url("revocations"), headers={"Authorization": "Bearer " + self.id_token})
        try:
            with urlopen(request, timeout=20) as response:
                document = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            if exc.code == 404:
                return {}
            raise
        marks = {}
        for key, value in (document.get("fields") or {}).items():
            raw = value.get("integerValue", value.get("doubleValue"))
            if raw is None:
                continue
            try:
                marks[key] = int(float(raw))
            except (TypeError, ValueError):
                pass
        return marks

    def revoke_device(self, device_id, now_ms):
        """Pide a otro dispositivo que cierre su sesión guardada."""
        if not self.id_token or not self.user:
            raise RuntimeError("No hay una sesión activa.")
        field = self.revocation_field(device_id)
        _post_json(self._document_url("revocations") + "?updateMask.fieldPaths=" + field,
                   {"fields": {field: {"integerValue": str(int(now_ms))}}},
                   {"Authorization": "Bearer " + self.id_token}, method="PATCH")
        state = self.load_state()
        devices = state.get("devices") if isinstance(state.get("devices"), dict) else {}
        if device_id in devices:
            devices.pop(device_id)
            state["devices"] = devices
            self.save_state(state)

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

    def update_state(self, transform, retries=4):
        """Lectura y escritura condicional para no pisar un cambio simultáneo de otro dispositivo."""
        if not self.id_token or not self.user:
            raise RuntimeError("No hay una sesión activa.")
        for attempt in range(retries):
            try:
                request = Request(self._document_url(), headers={"Authorization": "Bearer " + self.id_token})
                try:
                    with urlopen(request, timeout=20) as response:
                        document = json.loads(response.read().decode("utf-8"))
                    remote = json.loads(document.get("fields", {}).get("payload", {}).get("stringValue", "{}"))
                    version = {"currentDocument.updateTime": document["updateTime"]}
                except HTTPError as exc:
                    if exc.code != 404:
                        raise
                    remote, version = {}, {"currentDocument.exists": "false"}
                state = transform(remote)
                query = urlencode({"updateMask.fieldPaths": "payload", **version})
                _post_json(self._document_url() + "?" + query,
                           {"fields": {"payload": {"stringValue": json.dumps(state, ensure_ascii=False, separators=(",", ":"))}}},
                           {"Authorization": "Bearer " + self.id_token}, method="PATCH")
                return state
            except StateConflict:
                if attempt + 1 == retries:
                    raise

