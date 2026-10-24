"""Interfaz de escritorio. El motor de descarga vive en descargador_gui.py."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk
from urllib.parse import parse_qs, urlsplit

import descargador_gui as backend


BG = "#f3f5fa"
SURFACE = "#ffffff"
INK = "#1b2540"
MUTED = "#69758c"
ACCENT = "#6251e8"
LINE = "#e5e9f2"
GREEN = "#147d60"
RED = "#bf354f"


class SmoothProgress(tk.Canvas):
    """Una sola animación activa, con interpolación y modo sin tamaño conocido."""

    def __init__(self, master):
        super().__init__(master, height=16, bg=SURFACE, highlightthickness=0)
        self.value = self.target = 0.0
        self.indeterminate = False
        self.phase = 0.0
        self.timer = None
        self.color = ACCENT
        self.bind("<Configure>", lambda _: self.draw())

    def draw(self):
        width = max(20, self.winfo_width())
        self.delete("all")
        self.create_line(6, 8, width - 6, 8, width=8, fill=LINE, capstyle="round")
        span = width - 12
        if self.indeterminate:
            segment = span * 0.22
            left = 6 + (span - segment) * (math.sin(self.phase) + 1) / 2
            self.create_line(left, 8, left + segment, 8, width=8, fill=self.color, capstyle="round")
        elif self.value > 0.1:
            self.create_line(6, 8, 6 + span * self.value / 100, 8,
                             width=8, fill=self.color, capstyle="round")

    def animate(self):
        self.timer = None
        if self.indeterminate:
            self.phase += 0.09
        else:
            delta = self.target - self.value
            self.value = self.target if abs(delta) < 0.15 else self.value + delta * 0.22
        self.draw()
        if self.indeterminate or self.value != self.target:
            self.timer = self.after(28, self.animate)

    def set(self, value, *, immediate=False, color=ACCENT):
        self.indeterminate = False
        self.color = color
        self.target = min(100, max(0, float(value)))
        if immediate or self.target < self.value:
            self.value = self.target
        if self.timer is None:
            self.animate()

    def start(self):
        if self.indeterminate:
            return
        self.indeterminate = True
        self.color = ACCENT
        if self.timer is None:
            self.animate()

    def stop(self):
        self.indeterminate = False
        if self.timer is not None:
            self.after_cancel(self.timer)
            self.timer = None

    def destroy(self):
        self.stop()
        super().destroy()


class ScrollableSettings(ttk.Frame):
    """Las opciones pueden desplazarse; los botones de acción quedan siempre visibles."""

    def __init__(self, master):
        super().__init__(master, style="Card.TFrame")
        self.canvas = tk.Canvas(self, bg=SURFACE, width=280, height=300, highlightthickness=0)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.content = ttk.Frame(self.canvas, style="Card.TFrame")
        self.window = self.canvas.create_window((0, 0), window=self.content, anchor="nw")
        self.canvas.bind("<Configure>", self.resize)
        self.content.bind("<Configure>", self.resize)
        self.root = self.winfo_toplevel()
        self.wheel_binding = self.root.bind("<MouseWheel>", self.on_wheel, add="+")

    def resize(self, _=None):
        self.canvas.itemconfigure(self.window, width=self.canvas.winfo_width())
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        if self.content.winfo_reqheight() > self.canvas.winfo_height() + 2:
            self.scrollbar.pack(side="right", fill="y", padx=(6, 0), before=self.canvas)
        else:
            self.scrollbar.pack_forget()
            self.canvas.yview_moveto(0)

    def on_wheel(self, event):
        if event.widget.winfo_class() == "TCombobox":
            return
        widget = event.widget
        while widget is not None:
            if widget is self:
                if self.content.winfo_reqheight() > self.canvas.winfo_height():
                    self.canvas.yview_scroll(-1 if event.delta > 0 else 1, "units")
                return "break"
            widget = getattr(widget, "master", None)

    def destroy(self):
        self.root.unbind("<MouseWheel>", self.wheel_binding)
        super().destroy()


class DownloaderApp(tk.Tk):
    def __init__(self, *, history_path=None):
        super().__init__()
        from historial import DEFAULT_PATH

        self.history_path = Path(history_path) if history_path is not None else DEFAULT_PATH
        assets = backend.CODE_DIR / "assets"
        self.app_icon = tk.PhotoImage(master=self, file=str(assets / "logo.png"))
        self.logo_image = tk.PhotoImage(master=self, file=str(assets / "logo-48.png"))
        self.iconphoto(True, self.app_icon)
        if os.name == "nt":
            self.iconbitmap(default=str(assets / "descargador.ico"))
        self.title(f"Descargador v{backend.APP_VERSION} · MP3 y MP4")
        width = min(1080, self.winfo_screenwidth() - 80)
        height = min(800, self.winfo_screenheight() - 90)
        self.geometry(f"{width}x{height}")
        self.minsize(860, 640)
        self.configure(bg=BG)
        self.events = queue.Queue()
        self.process = None
        self.running = False
        self.cancel_requested = threading.Event()
        self.closing = False
        self.completed = self.error_count = 0
        self.had_summary = False
        self.inputs = []
        self.pending_logs = []
        self.poll_timer = None
        self.edit_timer = None
        self.advanced_open = False
        self.manual_mode = False
        self.song_window = None
        self.video_search_window = None
        self.history_window = None
        self.history_window_panel = None
        self.video_query_var = tk.StringVar(master=self)
        self.format_var = tk.StringVar(value="MP3")
        self.mode_var = tk.StringVar(value="single")
        self.quality_var = tk.StringVar(value=backend.AUDIO_QUALITY[0])
        self.folder_var = tk.StringVar(value=str(backend.APP_DIR / "Descargas"))
        self.items_var = tk.StringVar()
        self.cookies_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Todo listo para empezar")
        self.detail_var = tk.StringVar(value="El progreso de cada archivo aparecerá aquí.")
        self.counter_var = tk.StringVar(value="Archivos listos: 0 · errores: 0")
        self.link_count_var = tk.StringVar(value="Sin enlaces todavía")
        self.error_var = tk.StringVar()
        self.percent_var = tk.StringVar(value="—")
        self.badge_var = tk.StringVar(value="EN ESPERA")
        self.quality_hint_var = tk.StringVar()
        self.summary_var = tk.StringVar()
        self.build_ui()
        self.update_idletasks()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.bind("<Control-Return>", lambda _: self.start_download())
        self.bind("<Control-l>", self.focus_links)
        self.poll_timer = self.after(50, self.poll_events)

    def build_ui(self):
        self.option_add("*Font", ("Segoe UI", 10))
        self.option_add("*TCombobox*Listbox.background", SURFACE)
        self.option_add("*TCombobox*Listbox.selectBackground", ACCENT)
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=SURFACE, bordercolor=LINE, lightcolor=LINE, darkcolor=LINE)
        style.configure("TLabel", background=SURFACE, foreground=INK)
        style.configure("Muted.TLabel", foreground=MUTED)
        style.configure("Section.TLabel", foreground=MUTED, font=("Segoe UI", 9, "bold"))
        style.configure("TRadiobutton", background=SURFACE, foreground=INK, padding=(0, 5))
        style.map("TRadiobutton", background=[("active", SURFACE)], foreground=[("disabled", "#a0a8b8")])
        style.configure("Card.TCheckbutton", background=SURFACE, foreground=INK)
        style.map("Card.TCheckbutton", background=[("active", SURFACE)])
        style.configure("TEntry", padding=(8, 7), fieldbackground="#f8f9fc", bordercolor=LINE, lightcolor=LINE, darkcolor=LINE)
        style.map("TEntry", bordercolor=[("focus", ACCENT)])
        style.configure("TCombobox", padding=(8, 7), fieldbackground="#f8f9fc", background="#f8f9fc", bordercolor=LINE, arrowcolor=MUTED)
        style.map("TCombobox", fieldbackground=[("readonly", "#f8f9fc")], selectbackground=[("readonly", "#f8f9fc")], selectforeground=[("readonly", INK)])
        style.configure("TButton", padding=(12, 8), background="#eef0f7", foreground=INK,
                        borderwidth=0, focusthickness=2, focuscolor=ACCENT)
        style.map("TButton", background=[("active", "#e3e6f1")], foreground=[("disabled", "#a0a8b8")])
        style.configure("Accent.TButton", padding=(16, 13), background=ACCENT, foreground="white", font=("Segoe UI", 11, "bold"))
        style.map("Accent.TButton", background=[("disabled", "#d9d5f4"), ("active", "#5140ce")], foreground=[("disabled", "#9690ba")])
        style.configure("Link.TButton", background=SURFACE, foreground=ACCENT, padding=(4, 6))
        style.map("Link.TButton", background=[("active", "#f1efff")])
        style.configure("TNotebook", background=SURFACE, borderwidth=0, tabmargins=0,
                        bordercolor=LINE, lightcolor=LINE, darkcolor=LINE)
        style.configure("TNotebook.Tab", padding=(12, 8), background=SURFACE, foreground=MUTED, borderwidth=0)
        style.map("TNotebook.Tab", background=[("selected", "#f0edff")], foreground=[("selected", ACCENT)])
        style.configure("Treeview", background=SURFACE, fieldbackground=SURFACE, foreground=INK,
                        rowheight=32, borderwidth=0, font=("Segoe UI", 9))
        style.configure("Treeview.Heading", background="#f8f9fc", foreground=MUTED, relief="flat", font=("Segoe UI", 9))
        style.map("Treeview", background=[("selected", "#eeebff")], foreground=[("selected", INK)])

        header = tk.Frame(self, bg=BG)
        header.pack(fill="x", padx=26, pady=(18, 12))
        tk.Label(header, image=self.logo_image, bg=BG, borderwidth=0).pack(side="left", padx=(0, 10))
        title = tk.Frame(header, bg=BG)
        title.pack(side="left")
        tk.Label(title, text="Descargador", font=("Segoe UI", 20, "bold"), bg=BG, fg=INK).pack(anchor="w")
        tk.Label(title, text="Audio y video, a tu manera.", bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(anchor="w")
        tk.Label(header, text="MP3  /  MP4", bg="#eae6ff", fg=ACCENT,
                 padx=12, pady=7, font=("Segoe UI", 9, "bold")).pack(side="right")
        self.import_button = ttk.Button(header, text="Imágenes y nombres…", command=self.open_song_importer)
        self.import_button.pack(side="right", padx=16)
        self.inputs.append(self.import_button)

        search = ttk.Frame(self, padding=(26, 0, 26, 10))
        search.pack(fill="x")
        ttk.Label(search, text="Buscar videos:", background=BG, foreground=MUTED).pack(side="left", padx=(0, 8))
        self.video_search_entry = ttk.Entry(search, textvariable=self.video_query_var)
        self.video_search_entry.pack(side="left", fill="x", expand=True)
        self.video_search_entry.bind("<Return>", lambda _: self.open_video_search())
        self.video_search_button = ttk.Button(search, text="Buscar con portadas", command=self.open_video_search)
        self.video_search_button.pack(side="left", padx=(8, 0))
        self.inputs.extend([self.video_search_entry, self.video_search_button])

        body = ttk.Frame(self)
        body.pack(fill="both", expand=True, padx=26, pady=(0, 10))
        body.columnconfigure(0, weight=3, minsize=430)
        body.columnconfigure(1, weight=2, minsize=300)
        body.rowconfigure(0, weight=1)
        left = ttk.Frame(body)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 18))
        left.columnconfigure(0, weight=1)
        left.rowconfigure(2, weight=1)

        links = self.card(left)
        links.grid(row=0, column=0, sticky="ew")
        toolbar = ttk.Frame(links, style="Card.TFrame")
        toolbar.pack(fill="x", pady=(0, 10))
        ttk.Label(toolbar, text="01   TUS ENLACES", style="Section.TLabel").pack(side="left")
        self.paste_button = ttk.Button(toolbar, text="Pegar enlaces", style="Link.TButton", command=self.paste_links)
        self.paste_button.pack(side="right")
        self.clear_button = ttk.Button(toolbar, text="Limpiar", style="Link.TButton", command=self.clear_links)
        self.clear_button.pack(side="right", padx=(0, 8))
        self.urls = scrolledtext.ScrolledText(links, height=3, wrap="word", font=("Segoe UI", 10),
                                           bg="#f8f9fc", fg=INK, insertbackground=ACCENT,
                                           selectbackground="#e3ddff", selectforeground=INK,
                                           relief="flat", borderwidth=0, highlightthickness=1,
                                           highlightbackground=LINE, highlightcolor=ACCENT, padx=12, pady=10)
        self.urls.pack(fill="x")
        self.urls.bind("<<Modified>>", self.on_links_modified)
        footer = ttk.Frame(links, style="Card.TFrame")
        footer.pack(fill="x", pady=(8, 0))
        ttk.Label(footer, textvariable=self.link_count_var, style="Muted.TLabel", font=("Segoe UI", 9)).pack(side="left")
        ttk.Label(footer, text="Un enlace por línea", style="Muted.TLabel", font=("Segoe UI", 9)).pack(side="right")
        self.error_label = ttk.Label(links, textvariable=self.error_var, foreground=RED, wraplength=440)
        self.inputs.extend([self.paste_button, self.clear_button])

        progress = self.card(left)
        progress.grid(row=1, column=0, sticky="ew", pady=14)
        state_row = ttk.Frame(progress, style="Card.TFrame")
        state_row.pack(fill="x", pady=(0, 12))
        ttk.Label(state_row, text="02   TU DESCARGA", style="Section.TLabel").pack(side="left")
        self.badge = tk.Label(state_row, textvariable=self.badge_var, bg="#f0edff", fg=ACCENT,
                              padx=8, pady=4, font=("Segoe UI", 8, "bold"))
        self.badge.pack(side="right")
        self.status_label = ttk.Label(progress, textvariable=self.status_var,
                                      font=("Segoe UI", 12, "bold"), wraplength=440)
        self.status_label.pack(anchor="w", fill="x")
        progress_row = ttk.Frame(progress, style="Card.TFrame")
        progress_row.pack(fill="x", pady=(12, 6))
        self.progress_bar = SmoothProgress(progress_row)
        self.progress_bar.pack(side="left", fill="x", expand=True)
        ttk.Label(progress_row, textvariable=self.percent_var, width=6, anchor="e",
                  foreground=ACCENT, font=("Segoe UI", 10, "bold")).pack(side="right", padx=(8, 0))
        self.detail_label = ttk.Label(progress, textvariable=self.detail_var, style="Muted.TLabel",
                                      wraplength=440, font=("Segoe UI", 9))
        self.detail_label.pack(anchor="w", fill="x")
        ttk.Label(progress, textvariable=self.counter_var, style="Muted.TLabel", font=("Segoe UI", 9)).pack(anchor="w", pady=(12, 0))
        progress.bind("<Configure>", self.resize_status)

        results = self.card(left, padding=12)
        results.grid(row=2, column=0, sticky="nsew")
        self.notebook = ttk.Notebook(results)
        self.notebook.pack(fill="both", expand=True)
        self.files_tab = ttk.Frame(self.notebook, style="Card.TFrame", padding=(0, 8, 0, 0))
        self.log_tab = ttk.Frame(self.notebook, style="Card.TFrame", padding=(0, 8, 0, 0))
        self.notebook.add(self.files_tab, text="Archivos")
        from historial_gui import HistoryPanel

        self.history_panel = HistoryPanel(self.notebook, self.history_path,
                                          self.open_path, self.add_song_urls, lambda: not self.running,
                                          expand=self.open_download_history)
        self.notebook.add(self.history_panel, text="Historial")
        self.notebook.add(self.log_tab, text="Registro")
        self.import_tab = ttk.Frame(self.notebook, style="Card.TFrame", padding=(0, 8, 0, 0))
        self.notebook.add(self.import_tab, text="Informe de canciones")
        self.import_report = scrolledtext.ScrolledText(self.import_tab, height=3, state="disabled", wrap="word",
                                                       font=("Segoe UI", 9), relief="flat", bg="#f8f9fc", fg=INK)
        self.import_report.pack(fill="both", expand=True)
        self.empty_label = ttk.Label(self.files_tab, text="Tus archivos aparecerán aquí al terminar.",
                                     style="Muted.TLabel", font=("Segoe UI", 10))
        self.empty_label.pack(anchor="w", padx=8, pady=14)
        self.files_frame = ttk.Frame(self.files_tab, style="Card.TFrame")
        self.files = ttk.Treeview(self.files_frame, columns=("file", "format"), show="headings", height=3, selectmode="browse")
        self.files.heading("file", text="ARCHIVO")
        self.files.heading("format", text="FORMATO")
        self.files.column("file", width=320, minwidth=120)
        self.files.column("format", width=72, minwidth=60, stretch=False, anchor="center")
        scroll = ttk.Scrollbar(self.files_frame, orient="vertical", command=self.files.yview)
        self.files.configure(yscrollcommand=scroll.set)
        self.files.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.file_paths = {}
        self.files.bind("<Double-1>", self.open_selected_file)
        self.files.bind("<Return>", self.open_selected_file)
        self.log = scrolledtext.ScrolledText(self.log_tab, height=3, state="disabled", wrap="word",
                                            bg="#f8f9fc", fg=MUTED, font=("Consolas", 9),
                                            relief="flat", padx=8, pady=8)
        self.log.pack(fill="both", expand=True)
        for tag, color in (("error", RED), ("warning", "#a76b1c"), ("ready", GREEN)):
            self.log.tag_configure(tag, foreground=color)

        right_card = self.card(body)
        right_card.grid(row=0, column=1, sticky="nsew")
        self.actions = ttk.Frame(right_card, style="Card.TFrame")
        self.actions.pack(side="bottom", fill="x", pady=(16, 0))
        self.settings_scroll = ScrollableSettings(right_card)
        self.settings_scroll.pack(fill="both", expand=True)
        right = self.settings_scroll.content
        ttk.Label(right, text="CONFIGURA TU DESCARGA", style="Section.TLabel").pack(anchor="w", pady=(0, 16))
        ttk.Label(right, text="¿Qué quieres guardar?", font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(0, 10))
        formats = ttk.Frame(right, style="Card.TFrame")
        formats.pack(fill="x")
        formats.columnconfigure((0, 1), weight=1, uniform="format")
        self.format_buttons = {}
        for column, (value, label) in enumerate((("MP3", "MP3\nSolo audio"), ("MP4", "MP4\nVideo + audio"))):
            button = tk.Button(formats, text=label, command=lambda fmt=value: self.select_format(fmt),
                               relief="flat", borderwidth=0, padx=10, pady=12, cursor="hand2",
                               font=("Segoe UI", 11, "bold"), highlightthickness=1,
                               highlightbackground=LINE, activebackground="#eeeaff", activeforeground=ACCENT,
                               takefocus=True)
            button.grid(row=0, column=column, sticky="ew", padx=(0, 5) if column == 0 else (5, 0))
            self.format_buttons[value] = button
            self.inputs.append(button)
        ttk.Label(right, text="Calidad", style="Muted.TLabel").pack(anchor="w", pady=(18, 6))
        self.quality_box = ttk.Combobox(right, textvariable=self.quality_var, state="readonly")
        self.quality_box.pack(fill="x")
        self.quality_box.bind("<<ComboboxSelected>>", lambda _: self.refresh_summary())
        ttk.Label(right, textvariable=self.quality_hint_var, style="Muted.TLabel", wraplength=290,
                  font=("Segoe UI", 9)).pack(anchor="w", pady=(6, 0))
        self.separator(right)
        ttk.Label(right, text="Cantidad", style="Muted.TLabel").pack(anchor="w", pady=(0, 3))
        for value, label in (("single", "Un video por enlace"), ("playlist", "Playlist completa o un rango")):
            button = ttk.Radiobutton(right, text=label, value=value, variable=self.mode_var, command=self.select_mode)
            button.pack(anchor="w")
            self.inputs.append(button)
        self.range_frame = ttk.Frame(right, style="Card.TFrame")
        self.items_entry = ttk.Entry(self.range_frame, textvariable=self.items_var)
        self.items_entry.pack(fill="x", pady=(6, 4))
        ttk.Label(self.range_frame, text="Vacío: toda la lista · Ej. 1-10 o 1,3,5-8", style="Muted.TLabel",
                  font=("Segoe UI", 9), wraplength=290).pack(anchor="w")
        self.range_anchor = ttk.Frame(right, style="Card.TFrame")
        self.range_anchor.pack(fill="x")
        self.separator(right)
        folder_row = ttk.Frame(right, style="Card.TFrame")
        folder_row.pack(fill="x")
        ttk.Label(folder_row, text="Carpeta de destino", style="Muted.TLabel").pack(side="left")
        folder_button = ttk.Button(folder_row, text="Cambiar", style="Link.TButton", command=self.choose_folder)
        folder_button.pack(side="right")
        self.folder_entry = ttk.Entry(right, textvariable=self.folder_var)
        self.folder_entry.pack(fill="x", pady=(3, 0))
        advanced_button = ttk.Button(right, text="+ Opciones avanzadas", style="Link.TButton", command=self.toggle_advanced)
        advanced_button.pack(anchor="w", pady=(12, 0))
        self.advanced_button = advanced_button
        self.advanced_frame = ttk.Frame(right, style="Card.TFrame")
        self.advanced_anchor = ttk.Frame(right, style="Card.TFrame")
        self.advanced_anchor.pack(fill="x")
        ttk.Label(self.advanced_frame, text="Cookies opcionales (.txt)", style="Muted.TLabel", font=("Segoe UI", 9)).pack(anchor="w", pady=(4, 6))
        cookie_row = ttk.Frame(self.advanced_frame, style="Card.TFrame")
        cookie_row.pack(fill="x")
        cookie_entry = ttk.Entry(cookie_row, textvariable=self.cookies_var, width=12)
        cookie_entry.pack(side="left", fill="x", expand=True)
        cookie_button = ttk.Button(cookie_row, text="Elegir", command=self.choose_cookies)
        cookie_button.pack(side="right", padx=(6, 0))
        self.start_button = ttk.Button(self.actions, text="Descargar", style="Accent.TButton", command=self.start_download)
        self.start_button.pack(fill="x")
        self.cancel_button = ttk.Button(self.actions, text="Cancelar descarga", state="disabled", command=self.cancel_download)
        ttk.Button(self.actions, text="Abrir carpeta de destino", style="Link.TButton", command=self.open_folder).pack(pady=(5, 0))
        self.inputs.extend([folder_button, self.folder_entry, cookie_entry, cookie_button])
        self.update_options()

        footer = tk.Frame(self, bg=BG)
        footer.pack(fill="x", padx=28, pady=(0, 12))
        tk.Label(footer, textvariable=self.summary_var, bg=BG, fg=MUTED, font=("Segoe UI", 9)).pack(side="left")
        tk.Label(footer, text="Ctrl + Enter  Descargar     Ctrl + L  Enlaces", bg=BG, fg=MUTED,
                 font=("Segoe UI", 9)).pack(side="right")

    @staticmethod
    def card(parent, padding=18):
        return ttk.Frame(parent, style="Card.TFrame", padding=padding,
                         relief="solid", borderwidth=1)

    @staticmethod
    def separator(parent):
        tk.Frame(parent, height=1, bg=LINE).pack(fill="x", pady=16)

    def resize_status(self, event):
        width = max(220, event.width - 40)
        self.status_label.configure(wraplength=width)
        self.detail_label.configure(wraplength=width)

    def focus_links(self, _=None):
        if not self.running:
            self.urls.focus_set()
        return "break"

    def open_download_history(self):
        if self.history_window is not None and self.history_window.winfo_exists():
            self.history_window.lift()
            return
        from historial_gui import HistoryPanel

        self.history_window = tk.Toplevel(self)
        self.history_window.title("Historial · Descargador")
        self.history_window.geometry("860x560")
        self.history_window.minsize(640, 360)
        self.history_window.configure(bg=SURFACE)
        self.history_window_panel = HistoryPanel(self.history_window, self.history_path,
            self.open_path, self.add_song_urls, lambda: not self.running)
        self.history_window_panel.pack(fill="both", expand=True, padx=16, pady=12)

    def open_song_importer(self):
        if self.running:
            return
        if self.song_window is not None and self.song_window.winfo_exists():
            self.song_window.lift()
            return
        try:
            from importar_canciones import ImportSongsDialog

            self.song_window = ImportSongsDialog(self, self.add_song_urls, self.cookies_var.get().strip(),
                                                on_transfer=self.record_song_transfer)
        except ImportError:
            self.set_error("Faltan las herramientas de imágenes. Cierra y abre iniciar_descargador.bat para instalarlas.")

    def open_video_search(self):
        if self.running:
            return
        if self.video_search_window is not None and self.video_search_window.winfo_exists():
            self.video_search_window.lift()
            return
        from buscar_videos import VideoSearchDialog
        self.video_search_window = VideoSearchDialog(self, self.add_song_urls,
            self.video_query_var.get().strip(), self.cookies_var.get().strip())

    def add_song_urls(self, urls):
        if self.running:
            return False
        incoming = list(urls)
        unique = list(dict.fromkeys(url.strip() for url in incoming if url.strip()))
        existing = {line.strip() for line in self.urls.get("1.0", "end").splitlines() if line.strip()}
        additions = [url for url in unique if url not in existing]
        self.last_queue_addition = dict(incoming=len(incoming), unique=len(unique), added=len(additions),
                                        existing=len(unique) - len(additions), repeated=len(incoming) - len(unique))
        if not unique:
            self.set_error("No hay enlaces válidos para añadir. Revisa las elecciones.")
            return False
        if additions and self.urls.get("1.0", "end").strip():
            self.urls.insert("end", "\n")
        if additions:
            self.urls.insert("end", "\n".join(additions))
        self.manual_mode = True
        self.mode_var.set("single")
        self.update_options()
        self.refresh_links()
        self.status_var.set(f"{len(additions)} enlaces nuevos · {len(unique) - len(additions)} ya estaban en cola")
        self.detail_var.set(f"{len(incoming)} enlaces recibidos · {len(unique)} únicos. Se descargan en el orden de la cola. Elige MP3 o MP4 y pulsa Descargar.")
        if len(incoming) != len(additions):
            self.append_log(f"Cola: {len(incoming)} enlaces recibidos → {len(unique)} únicos; {len(additions)} nuevos y {len(unique) - len(additions)} ya presentes.", "warning")
            self.flush_logs()
            self.notebook.select(self.log_tab)
        return True

    def record_song_transfer(self, report):
        """Conserva nombres y motivos incluso después de cerrar el importador."""
        stats = self.last_queue_addition
        summary = f"{report['selected']} elecciones → {report['unique']} videos únicos · {stats['added']} nuevos · {stats['existing']} ya en cola"
        detail = report["source_report"] + "\n\nCOLA: " + summary
        self.import_report.configure(state="normal")
        if self.import_report.get("1.0", "end").strip():
            self.import_report.insert("end", "\n\n" + "═" * 40 + "\n\n")
        self.import_report.insert("end", detail)
        self.import_report.configure(state="disabled")
        self.import_report.see("end")
        self.status_var.set(summary)
        self.detail_var.set("La descarga aún no ha empezado. El detalle de pendientes y duplicados está en «Informe de canciones».")
        self.append_log(summary, "warning" if report["repeated"] else "info")
        self.flush_logs()
        self.notebook.select(self.import_tab)

    def set_error(self, text):
        self.error_var.set(text)
        if text:
            self.error_label.pack(anchor="w", pady=(8, 0))
        else:
            self.error_label.pack_forget()

    def select_format(self, fmt):
        self.format_var.set(fmt)
        self.update_options()

    def select_mode(self):
        self.manual_mode = True
        self.update_options()

    def update_options(self):
        audio = self.format_var.get() == "MP3"
        values = backend.AUDIO_QUALITY if audio else backend.VIDEO_QUALITY
        self.quality_box.configure(values=values, state="disabled" if self.running else "readonly")
        if self.quality_var.get() not in values:
            self.quality_var.set(values[0])
        for fmt, button in self.format_buttons.items():
            selected = fmt == self.format_var.get()
            button.configure(bg="#eeebff" if selected else "#f5f6fa", fg=ACCENT if selected else MUTED,
                             disabledforeground="#aaa3cf" if selected else "#9da5b5")
        playlist = self.mode_var.get() == "playlist"
        if playlist:
            self.range_frame.pack(fill="x", before=self.range_anchor)
        else:
            self.range_frame.pack_forget()
        self.items_entry.configure(state="normal" if playlist and not self.running else "disabled")
        self.quality_hint_var.set("Incluye portada y metadatos disponibles." if audio else "Resolución máxima, según disponibilidad.")
        self.refresh_summary()

    def refresh_summary(self):
        self.summary_var.set(f"{self.format_var.get()} · {self.quality_var.get()} · "
                             + ("Playlist" if self.mode_var.get() == "playlist" else "Videos individuales"))

    def toggle_advanced(self):
        self.advanced_open = not self.advanced_open
        if self.advanced_open:
            self.advanced_frame.pack(fill="x", before=self.advanced_anchor)
        else:
            self.advanced_frame.pack_forget()
        self.advanced_button.configure(text="− Opciones avanzadas" if self.advanced_open else "+ Opciones avanzadas")

    def on_links_modified(self, _=None):
        if not self.urls.edit_modified():
            return
        self.urls.edit_modified(False)
        if self.edit_timer is not None:
            self.after_cancel(self.edit_timer)
        self.edit_timer = self.after(160, self.refresh_links)

    def refresh_links(self):
        self.edit_timer = None
        lines = list(dict.fromkeys(line.strip() for line in self.urls.get("1.0", "end").splitlines() if line.strip()))
        self.link_count_var.set("Sin enlaces todavía" if not lines else f"{len(lines)} enlace{'s' if len(lines) != 1 else ''} en cola")
        if not self.running:
            self.set_error("")
            if lines and not self.manual_mode:
                try:
                    playlists = [urlsplit(url) for url in lines]
                    explicit_playlist = all(
                        (part.hostname or "").lower() in ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
                        and part.path.rstrip("/") == "/playlist" and "list" in parse_qs(part.query)
                        for part in playlists
                    )
                    self.mode_var.set("playlist" if explicit_playlist else "single")
                    self.update_options()
                except ValueError:
                    pass

    def clear_links(self):
        self.urls.delete("1.0", "end")
        self.manual_mode = False
        self.set_error("")

    def paste_links(self):
        try:
            text = self.clipboard_get().strip()
        except tk.TclError:
            self.set_error("Copia un enlace y vuelve a pulsar Pegar.")
            return
        if self.urls.get("1.0", "end").strip():
            self.urls.insert("end", "\n")
        self.urls.insert("end", text)
        self.urls.see("end")
        self.urls.focus_set()

    def choose_folder(self):
        folder = filedialog.askdirectory(parent=self, title="Carpeta de descarga")
        if folder:
            self.folder_var.set(folder)

    def choose_cookies(self):
        filename = filedialog.askopenfilename(parent=self, title="Cookies en formato Netscape",
                                              filetypes=[("Archivo de cookies", "*.txt"), ("Todos", "*.*")])
        if filename:
            self.cookies_var.set(filename)

    def open_path(self, path):
        try:
            if os.name == "nt":
                os.startfile(str(path))
            else:
                subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", str(path)])
        except OSError as exc:
            self.set_error(f"No se pudo abrir: {exc}")

    def open_folder(self):
        folder = Path(self.folder_var.get().strip() or str(backend.APP_DIR / "Descargas")).expanduser()
        try:
            folder.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            self.set_error(str(exc))
            return
        self.open_path(folder.resolve())

    def open_selected_file(self, _=None):
        selection = self.files.selection()
        if selection:
            self.open_path(self.file_paths[selection[0]])

    def set_running(self, running):
        self.running = running
        self.history_panel.update_actions()
        if self.history_window is not None and self.history_window.winfo_exists():
            self.history_window_panel.update_actions()
        for widget in self.inputs:
            widget.configure(state="disabled" if running else "normal")
        self.urls.configure(state="disabled" if running else "normal")
        self.start_button.configure(state="disabled" if running else "normal",
                                     text="Descargando…" if running else "Descargar")
        self.cancel_button.configure(state="normal" if running else "disabled")
        if running:
            self.cancel_button.pack(fill="x", pady=(7, 0), after=self.start_button)
        else:
            self.cancel_button.pack_forget()
        self.update_options()

    def set_badge(self, text, color=ACCENT, background="#f0edff"):
        self.badge_var.set(text)
        self.badge.configure(fg=color, bg=background)

    def start_download(self):
        if self.running:
            return
        if self.edit_timer is not None:
            self.after_cancel(self.edit_timer)
            self.edit_timer = None
        # Resuelve la detección de playlists aunque se pulse Descargar inmediatamente tras pegar.
        self.refresh_links()
        try:
            playlist = self.mode_var.get() == "playlist"
            urls = backend.parse_urls(self.urls.get("1.0", "end"), playlist)
            items = backend.validate_items(self.items_var.get()) if playlist else ""
            if not self.folder_var.get().strip():
                raise ValueError("Elige una carpeta de destino.")
            destination = Path(self.folder_var.get().strip()).expanduser().resolve()
            destination.mkdir(parents=True, exist_ok=True)
            ffmpeg = backend.find_ffmpeg()
            if not ffmpeg:
                raise ValueError("Coloca ffmpeg.exe junto al script para descargar MP3 y MP4.")
            cookies = self.cookies_var.get().strip()
            if cookies and not Path(cookies).is_file():
                raise ValueError("No existe el archivo de cookies seleccionado.")
            config = dict(urls=urls, playlist=playlist, items=items, destination=str(destination),
                          ffmpeg=ffmpeg, format=self.format_var.get(), quality=self.quality_var.get(),
                          cookies=cookies, history_path=str(self.history_path))
        except (ValueError, OSError) as exc:
            self.set_error(str(exc))
            self.urls.focus_set()
            return
        self.set_error("")
        self.cancel_requested.clear()
        self.completed = self.error_count = 0
        self.had_summary = False
        self.counter_var.set("Archivos listos: 0 · errores: 0")
        self.progress_bar.set(0, immediate=True)
        self.progress_bar.start()
        self.percent_var.set("…")
        self.status_var.set("Analizando enlaces…")
        self.detail_var.set("Buscando los formatos disponibles.")
        self.set_badge("PREPARANDO")
        self.append_log(f"\nDestino: {destination}\nFormato: {config['format']} · {config['quality']}")
        self.notebook.select(self.files_tab)
        self.set_running(True)
        threading.Thread(target=self.download_thread, args=(config,), daemon=True).start()

    def download_thread(self, config):
        process = None
        try:
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(backend.worker_command("--worker"),
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       encoding="utf-8", errors="replace",
                                       env={**os.environ, "PYTHONIOENCODING": "utf-8"}, **flags)
            self.process = process
            process.stdin.write(json.dumps(config) + "\n")
            process.stdin.close()
            if self.cancel_requested.is_set():
                backend.stop_process(process)
            for line in process.stdout:
                try:
                    event = json.loads(line)
                    if not isinstance(event, dict) or "event" not in event:
                        raise ValueError("Mensaje sin evento")
                except (ValueError, TypeError):
                    event = dict(event="log", level="info", text=line.strip())
                self.events.put(event)
            process.wait()
        except Exception as exc:
            if not self.cancel_requested.is_set():
                self.events.put(dict(event="log", level="error", text=str(exc)))
        finally:
            if process is not None:
                if process.poll() is None:
                    backend.stop_process(process)
                    process.wait()
                if process.stdout:
                    process.stdout.close()
            self.process = None
            self.events.put(dict(event="done", cancelled=self.cancel_requested.is_set()))

    def cancel_download(self):
        if not self.running or self.cancel_requested.is_set():
            return
        self.cancel_requested.set()
        self.cancel_button.configure(state="disabled", text="Cancelando…")
        self.status_var.set("Cancelando la descarga…")
        self.set_badge("CANCELANDO")
        process = self.process
        if process is not None:
            threading.Thread(target=backend.stop_process, args=(process,), daemon=True).start()

    def append_log(self, text, level="info"):
        self.pending_logs.append((str(text), level))

    def flush_logs(self):
        if not self.pending_logs:
            return
        follow = self.log.yview()[1] >= 0.98
        self.log.configure(state="normal")
        for text, level in self.pending_logs:
            self.log.insert("end", text + "\n", level)
        self.pending_logs.clear()
        lines = int(self.log.index("end-1c").split(".")[0])
        if lines > 1200:
            self.log.delete("1.0", f"{lines - 1000}.0")
        if follow:
            self.log.see("end")
        self.log.configure(state="disabled")

    def add_file(self, filename):
        self.empty_label.pack_forget()
        self.files_frame.pack(fill="both", expand=True)
        path = Path(filename)
        item = self.files.insert("", "end", values=(path.name, path.suffix.lstrip(".").upper()))
        self.file_paths[item] = path
        self.files.see(item)
        self.notebook.tab(self.files_tab, text=f"Archivos ({len(self.file_paths)})")

    def handle_event(self, event):
        kind = event["event"]
        cancelling = self.cancel_requested.is_set()
        if kind == "log":
            level = event.get("level", "info")
            if level == "error":
                self.error_count += 1
            self.append_log(event.get("text", ""), level)
        elif kind == "link" and not cancelling:
            self.status_var.set(f"Analizando enlace {event['index']} de {event['count']}…")
            self.detail_var.set("Consultando la información del enlace.")
            self.set_badge("PREPARANDO")
            self.percent_var.set("…")
            self.progress_bar.start()
        elif kind == "progress" and not cancelling:
            total, downloaded = event.get("total"), event.get("downloaded", 0)
            if event["status"] == "finished":
                self.status_var.set("Preparando el archivo final…")
                self.detail_var.set("Terminando la conversión o unión de pistas.")
                self.set_badge("PROCESANDO")
                self.percent_var.set("…")
                self.progress_bar.start()
            else:
                self.status_var.set(str(event.get("title", "Descargando…")))
                self.set_badge("DESCARGANDO")
                if total:
                    percent = min(100, downloaded / total * 100)
                    self.progress_bar.set(percent)
                    self.percent_var.set(f"{percent:.0f}%")
                else:
                    self.progress_bar.start()
                    self.percent_var.set("…")
                parts = [f"{backend.human_bytes(downloaded)} / {backend.human_bytes(total) if total else '?'}"]
                if event.get("speed"):
                    parts.append(f"{backend.human_bytes(event['speed'])}/s")
                if event.get("eta") is not None:
                    eta = max(0, int(event["eta"]))
                    parts.append(f"{eta // 60}:{eta % 60:02d} restantes")
                if event.get("index"):
                    parts.append(f"Archivo {event['index']} de {event.get('count') or '?'}")
                self.detail_var.set(" · ".join(parts))
        elif kind == "processing" and not cancelling:
            self.status_var.set(event["text"])
            self.set_badge("PROCESANDO")
            self.percent_var.set("…")
            self.progress_bar.start()
        elif kind == "ready":
            self.completed = event["completed"]
            self.append_log(f"Listo: {event['filename']}", "ready")
            self.add_file(event["filename"])
            self.history_panel.schedule_refresh()
            if self.history_window is not None and self.history_window.winfo_exists():
                self.history_window_panel.schedule_refresh()
        elif kind == "summary":
            self.completed, self.error_count = event["completed"], event["errors"]
            self.had_summary = True
        elif kind == "done":
            self.progress_bar.stop()
            self.cancel_button.configure(text="Cancelar descarga")
            if event["cancelled"]:
                self.status_var.set("Descarga cancelada")
                self.detail_var.set("Los archivos completos están guardados. Puedes volver a intentarlo.")
                self.percent_var.set("—")
                self.progress_bar.set(0, immediate=True)
                self.set_badge("CANCELADO", MUTED, "#eef0f5")
            elif self.error_count or not self.had_summary:
                self.status_var.set("El proceso terminó con errores · revisa el registro")
                self.detail_var.set("Los archivos completados están guardados en la carpeta de destino.")
                self.percent_var.set("—")
                self.progress_bar.set(0, immediate=True, color=RED)
                self.set_badge("CON ERRORES", RED, "#fff0f3")
                self.notebook.select(self.log_tab)
            else:
                self.status_var.set("¡Descargas terminadas!")
                self.detail_var.set("Abre un archivo con doble clic o visita la carpeta de destino.")
                self.percent_var.set("100%")
                self.progress_bar.set(100, color=GREEN)
                self.set_badge("COMPLETADO", GREEN, "#eaf7f1")
            self.append_log(self.status_var.get())
            self.set_running(False)
        self.counter_var.set(f"Archivos listos: {self.completed} · errores: {self.error_count}")

    def poll_events(self):
        self.poll_timer = None
        # Un presupuesto corto permite repintar y atender clics incluso con muchas entradas.
        deadline = time.perf_counter() + 0.008
        for _ in range(80):
            try:
                event = self.events.get_nowait()
            except queue.Empty:
                break
            self.handle_event(event)
            if time.perf_counter() >= deadline:
                break
        self.flush_logs()
        if self.closing and not self.running and not self.import_busy():
            self.destroy()
            return
        self.poll_timer = self.after(20 if not self.events.empty() else 60, self.poll_events)

    def on_close(self):
        if self.running:
            if not messagebox.askyesno("Cerrar", "Hay una descarga en curso. ¿Cancelar y cerrar?", parent=self):
                return
            self.closing = True
            self.cancel_download()
        else:
            if self.import_busy():
                self.closing = True
                for window in (self.song_window, self.video_search_window):
                    if window is not None and window.winfo_exists():
                        window.close()
            else:
                self.destroy()

    def import_busy(self):
        return any(window is not None and window.winfo_exists() and window.busy
                   for window in (self.song_window, self.video_search_window))

    def destroy(self):
        for timer in (self.poll_timer, self.edit_timer):
            if timer is not None:
                self.after_cancel(timer)
        self.poll_timer = self.edit_timer = None
        super().destroy()
        # Libera los objetos Tcl en el hilo de la interfaz, antes de que el
        # recolector de otro hilo pueda encontrar ciclos de widgets ya cerrados.
        self.inputs.clear()
        self.format_buttons.clear()
        for name, value in list(vars(self).items()):
            if isinstance(value, (tk.Variable, tk.Misc)):
                delattr(self, name)
