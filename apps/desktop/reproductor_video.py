"""Reproductor YouTube integrado en WebView2, separado del hilo de Tkinter.

No extrae audio ni utiliza las cookies del descargador. El reproductor oficial
controla la reproducción y puede rechazar videos restringidos.
"""

from __future__ import annotations

import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import queue
import re
import secrets
import subprocess
import sys
import threading
from urllib.parse import parse_qs, urlsplit

import descargador_gui as backend


def video_id(url):
    parts = urlsplit(url)
    if parts.scheme not in ("https", "http") or parts.username or parts.password:
        raise ValueError("El reproductor requiere un enlace de YouTube.")
    if parts.hostname == "youtu.be":
        identifier = parts.path.strip("/")
    elif parts.hostname in ("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com"):
        identifier = parse_qs(parts.query).get("v", [""])[0]
    else:
        identifier = ""
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", identifier):
        raise ValueError("El enlace no contiene un identificador de video válido.")
    return identifier


def player_error(code):
    return {
        2: "El identificador del video no es válido.",
        5: "YouTube no pudo reproducir el contenido en este reproductor.",
        100: "Video no disponible: puede haber sido eliminado o marcado como privado.",
        101: "El propietario no permite reproducir este video dentro de otras aplicaciones.",
        150: "El propietario no permite reproducir este video dentro de otras aplicaciones.",
        153: "YouTube rechazó la identificación del reproductor integrado (error 153).",
        -1: "El reproductor no respondió. Comprueba tu conexión o si YouTube está bloqueando el acceso.",
    }.get(code, f"YouTube no pudo reproducir el video (código {code}).")


def player_page(identifier, title, origin, path, autoplay=True):
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", identifier):
        raise ValueError("Identificador de video no válido.")
    config = json.dumps(dict(video=identifier, origin=origin, endpoint=path + "/event", autoplay=bool(autoplay),
                             errors={str(code): player_error(code) for code in (2, 5, 100, 101, 150, 153, -1)}), ensure_ascii=True)
    safe_title = html.escape(title)
    return """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="referrer" content="strict-origin-when-cross-origin">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reproductor integrado</title><style>
html,body{height:100%}
body{margin:0;display:flex;flex-direction:column;background:#f3f5fa;color:#1b2540;font:15px 'Segoe UI',sans-serif}
header,footer{padding:16px 22px;flex-shrink:0}h1{font-size:19px;margin:0 0 5px}p{margin:4px 0}
#screen{background:#080811;flex:1;min-height:200px;width:100%}
#player{width:100%;height:100%;min-height:200px}small{color:#69758c}a{color:#6251e8}
#status{padding-bottom:8px}.error{color:#bf354f}
.transport{background:white;border-radius:12px;padding:14px;margin-bottom:12px}
.timeline{display:flex;align-items:center;gap:12px;margin-bottom:12px}
.timeline span{font-variant-numeric:tabular-nums;white-space:nowrap;color:#69758c}
input[type=range]{flex:1;min-width:80px;accent-color:#6251e8;cursor:pointer}
.buttons{display:flex;justify-content:center;gap:10px}
button{border:0;border-radius:8px;background:#eeebff;color:#5140ce;padding:10px 18px;font:inherit;cursor:pointer}
button:disabled,input:disabled{opacity:.45;cursor:default}#toggle{background:#6251e8;color:white;min-width:130px}
</style></head><body><header><h1>""" + safe_title + """</h1>
<small>Escucha en el programa · No se crea un archivo MP3. Usa Internet y búfer temporal.</small></header>
<div id="screen"><div id="player"></div></div><footer>
<div class="transport" aria-label="Controles de reproducción">
<div class="timeline"><span id="elapsed">0:00</span>
<input id="seek" type="range" min="0" max="0" step="0.1" value="0" disabled aria-label="Posición de reproducción">
<span id="duration">--:--</span></div>
<div class="buttons"><button id="back" disabled aria-label="Retroceder 10 segundos">−10 s</button>
<button id="toggle" disabled>Reproducir</button>
<button id="forward" disabled aria-label="Adelantar 10 segundos">+10 s</button></div></div>
<p id="status" role="status">Cargando el reproductor oficial de YouTube…</p>
<a href="https://www.youtube.com/watch?v=""" + identifier + """" target="_blank" rel="noopener">Abrir en YouTube si el video no permite reproducción integrada</a>
</footer><script>
const config=""" + config + """;
let responded=false;
let playerReady=false,dragging=false,progressTimer=null;
const seek=document.getElementById('seek');
const toggle=document.getElementById('toggle');
const back=document.getElementById('back');
const forward=document.getElementById('forward');
function timeLabel(value){
  const seconds=Math.max(0,Math.floor(Number(value)||0));
  const hours=Math.floor(seconds/3600),minutes=Math.floor(seconds/60)%60,remainder=String(seconds%60).padStart(2,'0');
  return hours?hours+':'+String(minutes).padStart(2,'0')+':'+remainder:minutes+':'+remainder;
}
function updateProgress(){
  if(!playerReady)return;
  const player=window.previewPlayer;
  const duration=Number(player.getDuration());
  const available=Number.isFinite(duration)&&duration>0;
  seek.disabled=back.disabled=forward.disabled=!available;
  toggle.disabled=false;
  seek.max=available?duration:0;
  document.getElementById('duration').textContent=available?timeLabel(duration):'--:--';
  if(!dragging){
    const current=Number(player.getCurrentTime())||0;
    seek.value=available?Math.min(duration,Math.max(0,current)):0;
    document.getElementById('elapsed').textContent=timeLabel(current);
  }
  toggle.textContent=player.getPlayerState()===1?'Pausar':'Reproducir';
}
function seekToPosition(value){
  if(!playerReady||seek.disabled)return;
  const target=Number(value);
  if(!Number.isFinite(target))return;
  const position=Math.min(Number(seek.max),Math.max(0,target));
  window.previewPlayer.seekTo(position,true);
  seek.value=position;
  document.getElementById('elapsed').textContent=timeLabel(position);
}
seek.addEventListener('input',()=>{
  dragging=true;
  document.getElementById('elapsed').textContent=timeLabel(seek.value);
});
seek.addEventListener('change',()=>{dragging=false;seekToPosition(seek.value);});
seek.addEventListener('blur',()=>{dragging=false;updateProgress();});
back.addEventListener('click',()=>seekToPosition(window.previewPlayer.getCurrentTime()-10));
forward.addEventListener('click',()=>seekToPosition(window.previewPlayer.getCurrentTime()+10));
toggle.addEventListener('click',()=>{
  if(!playerReady)return;
  const player=window.previewPlayer;
  if(player.getPlayerState()===1)player.pauseVideo();else player.playVideo();
  updateProgress();
});
window.addEventListener('pagehide',()=>clearInterval(progressTimer));
function notify(status,code){
  fetch(config.endpoint,{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({status:status,code:code})}).catch(()=>{});
}
function message(text,error){const el=document.getElementById('status');el.textContent=text;el.className=error?'error':'';}
function failure(code){
  responded=true;playerReady=false;clearInterval(progressTimer);
  seek.disabled=toggle.disabled=back.disabled=forward.disabled=true;
  message(config.errors[String(code)]||'YouTube rechazó la reproducción. Puedes probar el enlace inferior.',true);notify('error',code);
}
function onYouTubeIframeAPIReady(){
  window.previewPlayer=new YT.Player('player',{videoId:config.video,width:'100%',height:'100%',
    playerVars:{autoplay:config.autoplay?1:0,controls:1,playsinline:1,origin:config.origin,rel:0},
    events:{
      onReady:function(){
        responded=true;playerReady=true;updateProgress();clearInterval(progressTimer);
        progressTimer=setInterval(updateProgress,250);
        message('Reproductor listo. Pulsa Reproducir o usa los controles del video.',false);notify('ready');
      },
      onError:function(event){failure(event.data);},
      onAutoplayBlocked:function(){responded=true;message('Pulsa ▶ dentro del video para empezar a escuchar.',false);notify('autoplay_blocked');},
      onStateChange:function(event){
        updateProgress();
        if(event.data===1){message('Reproduciendo · Puedes pausar, ajustar el volumen o adelantar con los controles del video.',false);notify('playing');}
        else if(event.data===2){message('En pausa.',false);notify('paused');}
        else if(event.data===0){message('Reproducción terminada.',false);notify('ended');}
      }
    }});
}
setTimeout(()=>{if(!responded)failure(-1);},25000);
const script=document.createElement('script');script.src='https://www.youtube.com/iframe_api';script.onerror=()=>failure(-1);document.head.appendChild(script);
</script></body></html>"""


def make_player_server(identifier, title, emit, *, autoplay=True):
    path = "/" + secrets.token_urlsafe(24)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass

        def do_GET(self):
            if self.path != path:
                self.send_error(404)
                return
            body = player_page(identifier, title, origin, path, autoplay).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path != path + "/event" or self.headers.get("Origin") != origin:
                self.send_error(403)
                return
            try:
                length = int(self.headers.get("Content-Length", 0))
                if not 0 < length <= 1024:
                    raise ValueError("Tamaño inválido")
                data = json.loads(self.rfile.read(length))
                status = data["status"]
                if status not in ("ready", "playing", "paused", "ended", "error", "autoplay_blocked"):
                    raise ValueError("Evento inválido")
                code = data.get("code")
                if code is not None and (type(code) is not int or not -1 <= code <= 999):
                    raise ValueError("Código inválido")
                with lock:
                    emit(dict(event="player", status=status, text=player_error(code) if status == "error" else ""))
            except (KeyError, ValueError, TypeError):
                self.send_error(400)
                return
            self.send_response(204)
            self.end_headers()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    origin = f"http://127.0.0.1:{server.server_port}"
    return server, origin + path


def run_player(config):
    def emit(event):
        print(json.dumps(event, ensure_ascii=True), flush=True)

    server = None
    try:
        import webview
        identifier = video_id(config["url"])
        server, url = make_player_server(identifier, config.get("title") or "Reproductor", emit,
                                        autoplay=bool(config.get("autoplay", True)))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        webview.settings["ALLOW_DOWNLOADS"] = False
        webview.settings["ALLOW_FILE_URLS"] = False
        webview.create_window("Reproductor · " + (config.get("title") or "YouTube"), url=url,
                              width=900, height=760, min_size=(640, 580), background_color="#f3f5fa")
        emit(dict(event="player", status="opening", text="Abriendo reproductor integrado…"))
        webview.start(gui="edgechromium" if os.name == "nt" else None, private_mode=True, debug=False)
        return 0
    except Exception as exc:
        emit(dict(event="player", status="error", text=f"No se pudo abrir el reproductor integrado: {exc}. En Windows requiere pywebview y Microsoft Edge WebView2 Runtime."))
        return 1
    finally:
        if server:
            server.shutdown()
            server.server_close()


class PlayerJob:
    def __init__(self):
        self.events = queue.Queue()
        self.cancelled = threading.Event()
        self.process = None

    def cancel(self):
        self.cancelled.set()
        process = self.process
        if process:
            backend.stop_process(process)

    def run(self, config):
        process = None
        try:
            if self.cancelled.is_set():
                return
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(backend.worker_command("--player-worker"),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                encoding="utf-8", errors="replace", env={**os.environ, "PYTHONIOENCODING": "utf-8"}, **flags)
            self.process = process
            process.stdin.write(json.dumps(config) + "\n")
            process.stdin.close()
            if self.cancelled.is_set():
                backend.stop_process(process)
            received = False
            for line in process.stdout:
                try:
                    event = json.loads(line)
                    if isinstance(event, dict) and event.get("event") == "player":
                        received = True
                        self.events.put(event)
                except ValueError:
                    pass
            code = process.wait()
            if (code or not received) and not self.cancelled.is_set():
                self.events.put(dict(event="player", status="error", text="El reproductor se cerró o no pudo iniciarse. Comprueba WebView2 y las dependencias."))
        except Exception as exc:
            if not self.cancelled.is_set():
                self.events.put(dict(event="player", status="error", text=f"No se pudo iniciar el reproductor: {exc}"))
        finally:
            if process:
                if process.poll() is None:
                    backend.stop_process(process)
                    process.wait()
                process.stdout.close()
            self.process = None
            self.events.put(dict(event="player_done", cancelled=self.cancelled.is_set()))
