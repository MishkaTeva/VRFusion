#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VRFusion Control Center
Единая панель управления VR-системой (Samsung S21 FE + SteamVR + Kinect 360)
"""

import sys
import os
import shutil
import time
import socket
import subprocess
import threading
import tkinter as tk
from tkinter import ttk, messagebox

VR_DIR = os.path.dirname(os.path.abspath(__file__))
ADB_PATH = os.path.join(VR_DIR, "platform-tools", "adb.exe")
PHONE_CMD_PORT = 9552
DRIVER_CMD_PORT = 9555
DEFAULT_SERIAL = "R5CRC476JPV"
CREATE_NO_WINDOW = 0x08000000

class VRFusionControlCenter:
    def __init__(self, root):
        self.root = root
        self.root.title("VRFusion Unified Control Center")
        self.root.geometry("900x870")
        self.root.minsize(860, 800)
        self.root.configure(bg="#121318")

        # Connection & State
        self.serial = DEFAULT_SERIAL
        self.is_connected = False
        self.driver_connected = False
        self.polling_active = True

        # Headset & Tracking State
        self.yaw = 0.0
        self.pitch = 0.0
        self.roll = 0.0
        self.batt = 100
        self.roll_inv = False
        self.flip_180 = False
        self.kinect_mirror = False
        self.kinect_tilt = 0
        self.kinect_seated = False
        self.kinect_height = 0.0
        self.kinect_tracking = False
        self.kinect_auto_tilt = True
        self.kinect_motor_locked = False
        self.kinect_ax = 0.0
        self.kinect_ay = -1.0
        self.kinect_az = 0.0
        self.kinect_pitch = 0.0
        self.kinect_roll = 0.0
        self.mic_on = False
        self.cam_mode = 0
        self.cam_preview_proc = None

        # Debounce timers
        self._scale_job = None
        self._offset_job = None
        self._ipd_job = None
        self._ui_scale_job = None
        self._divider_job = None
        self._tilt_job = None
        self._tilt_dragging = False

        self._init_styles()
        self._build_ui()
        self._detect_device()

        # Background telemetry poller
        self.telemetry_thread = threading.Thread(target=self._telemetry_worker, daemon=True)
        self.telemetry_thread.start()

    def _init_styles(self):
        style = ttk.Style()
        style.theme_use("clam")

        bg_card = "#1a1d26"
        fg_text = "#e2e8f0"
        fg_muted = "#94a3b8"
        accent_blue = "#00d2ff"
        accent_green = "#00e676"

        style.configure(".", background="#121318", foreground=fg_text)
        style.configure("Card.TFrame", background=bg_card, relief="flat")
        style.configure("Header.TLabel", font=("Segoe UI", 16, "bold"), foreground="#ffffff", background="#121318")
        style.configure("SubHeader.TLabel", font=("Segoe UI", 9), foreground=fg_muted, background="#121318")
        style.configure("Section.TLabel", font=("Segoe UI", 11, "bold"), foreground=accent_blue, background=bg_card)
        style.configure("Label.TLabel", font=("Segoe UI", 9), foreground=fg_text, background=bg_card)
        style.configure("Value.TLabel", font=("Segoe UI", 9, "bold"), foreground=accent_green, background=bg_card)

        style.configure("Horizontal.TScale",
                        background=bg_card,
                        troughcolor="#2d3345",
                        sliderrelief="flat")

    def _build_ui(self):
        # 1. Header
        header_frame = tk.Frame(self.root, bg="#121318", pady=10)
        header_frame.pack(fill=tk.X, padx=16)

        title_box = tk.Frame(header_frame, bg="#121318")
        title_box.pack(side=tk.LEFT)

        ttk.Label(title_box, text="⚡ VRFUSION CONTROL CENTER", style="Header.TLabel").pack(anchor="w")
        ttk.Label(title_box, text="Samsung Galaxy S21 FE • 120Hz AMOLED • SteamVR • Kinect 360 FBT", style="SubHeader.TLabel").pack(anchor="w")

        status_box = tk.Frame(header_frame, bg="#121318")
        status_box.pack(side=tk.RIGHT)

        self.lbl_phone_status = tk.Label(status_box, text="● ТЕЛЕФОН: ПРОВЕРКА...", font=("Segoe UI", 9, "bold"),
                                         bg="#1e293b", fg="#fbbf24", padx=10, pady=4)
        self.lbl_phone_status.pack(side=tk.LEFT, padx=4)

        self.lbl_driver_status = tk.Label(status_box, text="● STEAMVR: ОЖИДАНИЕ", font=("Segoe UI", 9, "bold"),
                                          bg="#1e293b", fg="#94a3b8", padx=10, pady=4)
        self.lbl_driver_status.pack(side=tk.LEFT, padx=4)

        # 2. Main Action Buttons Bar
        btn_bar = tk.Frame(self.root, bg="#1a1d26", padx=12, pady=10)
        btn_bar.pack(fill=tk.X, padx=16, pady=(0, 10))

        self.btn_start_vr = tk.Button(btn_bar, text="▶ ЗАПУСК VR СЕССИИ", font=("Segoe UI", 10, "bold"),
                                      bg="#059669", fg="#ffffff", activebackground="#10b981", activeforeground="#ffffff",
                                      relief="flat", padx=14, pady=6, cursor="hand2", command=self.action_start_vr)
        self.btn_start_vr.pack(side=tk.LEFT, padx=3)

        self.btn_stop_vr = tk.Button(btn_bar, text="⏹ ОСТАНОВИТЬ VR", font=("Segoe UI", 10, "bold"),
                                     bg="#dc2626", fg="#ffffff", activebackground="#ef4444", activeforeground="#ffffff",
                                     relief="flat", padx=14, pady=6, cursor="hand2", command=self.action_stop_vr)
        self.btn_stop_vr.pack(side=tk.LEFT, padx=3)

        self.btn_recenter = tk.Button(btn_bar, text="🎯 ЦЕНТР ВЗГЛЯДА", font=("Segoe UI", 10, "bold"),
                                      bg="#2563eb", fg="#ffffff", activebackground="#3b82f6", activeforeground="#ffffff",
                                      relief="flat", padx=12, pady=6, cursor="hand2", command=self.action_recenter)
        self.btn_recenter.pack(side=tk.LEFT, padx=3)

        self.btn_flip180 = tk.Button(btn_bar, text="🔄 РАЗВЕРНУТЬ НА 180°", font=("Segoe UI", 10, "bold"),
                                     bg="#7c3aed", fg="#ffffff", activebackground="#8b5cf6", activeforeground="#ffffff",
                                     relief="flat", padx=12, pady=6, cursor="hand2", command=self.action_flip_180)
        self.btn_flip180.pack(side=tk.LEFT, padx=3)

        self.btn_calib_kinect = tk.Button(btn_bar, text="🧍 ЦЕНТР KINECT", font=("Segoe UI", 10, "bold"),
                                          bg="#0891b2", fg="#ffffff", activebackground="#06b6d4", activeforeground="#ffffff",
                                          relief="flat", padx=12, pady=6, cursor="hand2", command=self.action_recenter_kinect)
        self.btn_calib_kinect.pack(side=tk.LEFT, padx=3)

        # 3. Two-Column Body
        body = tk.Frame(self.root, bg="#121318")
        body.pack(fill=tk.BOTH, expand=True, padx=16)

        # Left Column: Display & Optics
        col_left = ttk.Frame(body, style="Card.TFrame", padding=14)
        col_left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 6))

        ttk.Label(col_left, text="ОПТИКА И ЛИНЗЫ (ДИСПЛЕЙ)", style="Section.TLabel").pack(anchor="w", pady=(0, 10))

        # Scale Slider
        self._create_slider_row(
            col_left, "Масштаб глаз (Zoom / FOV):", 0.50, 1.50, 1.00,
            unit="%", factor=100, format_str="{:.0f}%",
            on_change=self._on_scale_slider, var_name="scale_var", lbl_name="scale_val_lbl"
        )
        scale_presets = tk.Frame(col_left, bg="#1a1d26")
        scale_presets.pack(fill=tk.X, pady=(2, 10))
        for s_val in [0.85, 0.95, 1.00, 1.05, 1.15, 1.25]:
            b = tk.Button(scale_presets, text=f"{int(s_val*100)}%", font=("Segoe UI", 8),
                          bg="#2d3345", fg="#cbd5e1", relief="flat", padx=6, pady=2, cursor="hand2",
                          command=lambda v=s_val: self.set_scale_value(v))
            b.pack(side=tk.LEFT, padx=2)

        # Vertical Offset Y Slider
        self._create_slider_row(
            col_left, "Смещение по вертикали (Offset Y):", -300, 300, 0,
            unit="px", factor=1, format_str="{:+d} px",
            on_change=self._on_offset_slider, var_name="offset_var", lbl_name="offset_val_lbl"
        )
        offset_presets = tk.Frame(col_left, bg="#1a1d26")
        offset_presets.pack(fill=tk.X, pady=(2, 10))
        for off_val in [-100, -50, 0, 50, 100]:
            b = tk.Button(offset_presets, text=f"{off_val:+d}", font=("Segoe UI", 8),
                          bg="#2d3345", fg="#cbd5e1", relief="flat", padx=6, pady=2, cursor="hand2",
                          command=lambda v=off_val: self.set_offset_value(v))
            b.pack(side=tk.LEFT, padx=2)

        # IPD Slider
        self._create_slider_row(
            col_left, "Межзрачковое расстояние (IPD):", 55.0, 75.0, 63.0,
            unit="мм", factor=1, format_str="{:.1f} мм",
            on_change=self._on_ipd_slider, var_name="ipd_var", lbl_name="ipd_val_lbl"
        )
        ipd_presets = tk.Frame(col_left, bg="#1a1d26")
        ipd_presets.pack(fill=tk.X, pady=(2, 10))
        for ipd_val in [58.0, 60.0, 63.0, 65.0, 68.0, 70.0]:
            b = tk.Button(ipd_presets, text=f"{ipd_val:.1f} мм", font=("Segoe UI", 8),
                          bg="#2d3345", fg="#cbd5e1", relief="flat", padx=6, pady=2, cursor="hand2",
                          command=lambda v=ipd_val: self.set_ipd_value(v))
            b.pack(side=tk.LEFT, padx=2)

        # HUD Scale Slider
        self._create_slider_row(
            col_left, "Масштаб интерфейса (HUD):", 0.50, 1.50, 1.00,
            unit="%", factor=100, format_str="{:.0f}%",
            on_change=self._on_ui_scale_slider, var_name="ui_scale_var", lbl_name="ui_scale_val_lbl"
        )

        # Divider Width Slider
        self._create_slider_row(
            col_left, "Разделитель экранов (Divider):", 0, 30, 0,
            unit="px", factor=1, format_str="{:d} px",
            on_change=self._on_divider_slider, var_name="divider_var", lbl_name="divider_val_lbl"
        )

        # Right Column: Tracking, Kinect & Telemetry
        col_right = ttk.Frame(body, style="Card.TFrame", padding=14)
        col_right.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True, padx=(6, 0))

        ttk.Label(col_right, text="ТРЕКИНГ И СТОРОНЫ ТЕЛА", style="Section.TLabel").pack(anchor="w", pady=(0, 10))

        toggles_frame = tk.Frame(col_right, bg="#1a1d26")
        toggles_frame.pack(fill=tk.X, pady=(0, 8))

        self.btn_roll = tk.Button(toggles_frame, text="НАКЛОН (ROLL): СТАНДАРТ", font=("Segoe UI", 9, "bold"),
                                  bg="#065f46", fg="#34d399", relief="flat", padx=8, pady=5, cursor="hand2",
                                  command=self.action_toggle_roll)
        self.btn_roll.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 4))

        self.btn_mirror = tk.Button(toggles_frame, text="СТОРОНЫ KINECT: ПРЯМЫЕ", font=("Segoe UI", 9, "bold"),
                                    bg="#0e7490", fg="#38bdf8", relief="flat", padx=8, pady=5, cursor="hand2",
                                    command=self.action_toggle_kinect_mirror)
        self.btn_mirror.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=(4, 0))

        toggles_frame2 = tk.Frame(col_right, bg="#1a1d26")
        toggles_frame2.pack(fill=tk.X, pady=(0, 12))

        self.btn_hud = tk.Button(toggles_frame2, text="ИНФО ЭКРАНА (HUD)", font=("Segoe UI", 9, "bold"),
                                 bg="#1e293b", fg="#cbd5e1", relief="flat", padx=8, pady=5, cursor="hand2",
                                 command=self.action_toggle_hud)
        self.btn_hud.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 3))

        self.btn_mic = tk.Button(toggles_frame2, text="🎙 МИКРОФОН: ВЫКЛ", font=("Segoe UI", 9, "bold"),
                                 bg="#1e293b", fg="#94a3b8", relief="flat", padx=8, pady=5, cursor="hand2",
                                 command=self.action_toggle_mic)
        self.btn_mic.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(3, 3))

        btn_reset = tk.Button(toggles_frame2, text="↺ СБРОС", font=("Segoe UI", 9, "bold"),
                              bg="#374151", fg="#f87171", relief="flat", padx=8, pady=5, cursor="hand2",
                              command=self.action_reset_defaults)
        btn_reset.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=(3, 0))

        # Telemetry Card
        telem_card = tk.LabelFrame(col_right, text=" ЖИВАЯ ТЕЛЕМЕТРИЯ ШЛЕМА ", font=("Segoe UI", 9, "bold"),
                                   bg="#121318", fg="#38bdf8", padx=12, pady=10, bd=1, relief="solid")
        telem_card.pack(fill=tk.X, pady=(0, 10))

        row1 = tk.Frame(telem_card, bg="#121318")
        row1.pack(fill=tk.X, pady=2)
        tk.Label(row1, text="Yaw (Курс):", font=("Segoe UI", 9), bg="#121318", fg="#94a3b8").pack(side=tk.LEFT)
        self.lbl_yaw = tk.Label(row1, text="0.0°", font=("Segoe UI", 10, "bold"), bg="#121318", fg="#00e676")
        self.lbl_yaw.pack(side=tk.RIGHT)

        row2 = tk.Frame(telem_card, bg="#121318")
        row2.pack(fill=tk.X, pady=2)
        tk.Label(row2, text="Pitch (Тангаж вверх/вниз):", font=("Segoe UI", 9), bg="#121318", fg="#94a3b8").pack(side=tk.LEFT)
        self.lbl_pitch = tk.Label(row2, text="0.0°", font=("Segoe UI", 10, "bold"), bg="#121318", fg="#00e676")
        self.lbl_pitch.pack(side=tk.RIGHT)

        row3 = tk.Frame(telem_card, bg="#121318")
        row3.pack(fill=tk.X, pady=2)
        tk.Label(row3, text="Roll (Наклон влево/вправо):", font=("Segoe UI", 9), bg="#121318", fg="#94a3b8").pack(side=tk.LEFT)
        self.lbl_roll = tk.Label(row3, text="0.0°", font=("Segoe UI", 10, "bold"), bg="#121318", fg="#00e676")
        self.lbl_roll.pack(side=tk.RIGHT)

        row4 = tk.Frame(telem_card, bg="#121318")
        row4.pack(fill=tk.X, pady=3)
        tk.Label(row4, text="Батарея S21 FE:", font=("Segoe UI", 9), bg="#121318", fg="#94a3b8").pack(side=tk.LEFT)
        self.lbl_batt = tk.Label(row4, text="100%", font=("Segoe UI", 10, "bold"), bg="#121318", fg="#38bdf8")
        self.lbl_batt.pack(side=tk.RIGHT)

        # Kinect Control & Status Box
        kinect_card = tk.LabelFrame(col_right, text=" УПРАВЛЕНИЕ KINECT 360 & FBT ", font=("Segoe UI", 9, "bold"),
                                    bg="#121318", fg="#38bdf8", padx=12, pady=8, bd=1, relief="solid")
        kinect_card.pack(fill=tk.X, pady=(0, 6))

        # Status row
        k_row1 = tk.Frame(kinect_card, bg="#121318")
        k_row1.pack(fill=tk.X, pady=1)
        tk.Label(k_row1, text="Статус человека:", font=("Segoe UI", 9), bg="#121318", fg="#94a3b8").pack(side=tk.LEFT)
        self.lbl_kinect_status = tk.Label(k_row1, text="Ожидание скелета...", font=("Segoe UI", 9, "bold"), bg="#121318", fg="#fbbf24")
        self.lbl_kinect_status.pack(side=tk.RIGHT)

        # Mode & Auto-tilt toggle row
        k_mode_row = tk.Frame(kinect_card, bg="#121318")
        k_mode_row.pack(fill=tk.X, pady=(4, 4))
        self.btn_kinect_mode = tk.Button(k_mode_row, text="РЕЖИМ: СТОЯ (FBT)", font=("Segoe UI", 9, "bold"),
                                         bg="#059669", fg="#ffffff", activebackground="#10b981", activeforeground="#ffffff",
                                         relief="flat", padx=6, pady=4, cursor="hand2", command=self.action_toggle_kinect_seated)
        self.btn_kinect_mode.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 2))

        self.btn_auto_tilt = tk.Button(k_mode_row, text="🎯 АВТО-ФОКУС: ВКЛ", font=("Segoe UI", 9, "bold"),
                                       bg="#1d4ed8", fg="#93c5fd", activebackground="#2563eb", activeforeground="#ffffff",
                                       relief="flat", padx=6, pady=4, cursor="hand2", command=self.action_toggle_kinect_auto_tilt)
        self.btn_auto_tilt.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=(2, 0))

        # Motor lock toggle row
        k_lock_row = tk.Frame(kinect_card, bg="#121318")
        k_lock_row.pack(fill=tk.X, pady=(0, 4))
        self.btn_motor_lock = tk.Button(k_lock_row, text="🔓 МОТОР KINECT: РАЗРЕШЁН", font=("Segoe UI", 8, "bold"),
                                        bg="#065f46", fg="#34d399", activebackground="#047857", activeforeground="#ffffff",
                                        relief="flat", padx=6, pady=3, cursor="hand2", command=self.action_toggle_motor_lock)
        self.btn_motor_lock.pack(fill=tk.X, expand=True)

        # Tilt motor controls
        k_tilt_header = tk.Frame(kinect_card, bg="#121318")
        k_tilt_header.pack(fill=tk.X, pady=(2, 1))
        tk.Label(k_tilt_header, text="Угол наклона (-8°..+14°):", font=("Segoe UI", 9), bg="#121318", fg="#cbd5e1").pack(side=tk.LEFT)
        self.lbl_tilt_val = tk.Label(k_tilt_header, text="0°", font=("Segoe UI", 9, "bold"), bg="#121318", fg="#00e676")
        self.lbl_tilt_val.pack(side=tk.RIGHT)

        # Real-time Accelerometer & Physical Tilt display
        k_acc_row = tk.Frame(kinect_card, bg="#121318")
        k_acc_row.pack(fill=tk.X, pady=(0, 2))
        tk.Label(k_acc_row, text="Акселерометр G:", font=("Segoe UI", 8), bg="#121318", fg="#94a3b8").pack(side=tk.LEFT)
        self.lbl_kinect_acc = tk.Label(k_acc_row, text="X:0.00 Y:-1.00 Z:0.00 (Roll:0.0°)", font=("Segoe UI", 8, "bold"), bg="#121318", fg="#38bdf8")
        self.lbl_kinect_acc.pack(side=tk.RIGHT)

        k_tilt_btns = tk.Frame(kinect_card, bg="#121318")
        k_tilt_btns.pack(fill=tk.X, pady=(2, 4))
        b_t_up = tk.Button(k_tilt_btns, text="⬆ Наклон +3°", font=("Segoe UI", 8, "bold"),
                           bg="#2563eb", fg="#ffffff", relief="flat", padx=6, pady=3, cursor="hand2",
                           command=self.action_kinect_tilt_up)
        b_t_up.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 2))

        b_t_zero = tk.Button(k_tilt_btns, text="⬌ 0° Горизонт", font=("Segoe UI", 8, "bold"),
                             bg="#334155", fg="#cbd5e1", relief="flat", padx=6, pady=3, cursor="hand2",
                             command=self.action_kinect_tilt_zero)
        b_t_zero.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=2)

        b_t_down = tk.Button(k_tilt_btns, text="⬇ Наклон -3°", font=("Segoe UI", 8, "bold"),
                             bg="#2563eb", fg="#ffffff", relief="flat", padx=6, pady=3, cursor="hand2",
                             command=self.action_kinect_tilt_down)
        b_t_down.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(2, 0))

        # Height offset controls
        k_h_header = tk.Frame(kinect_card, bg="#121318")
        k_h_header.pack(fill=tk.X, pady=(4, 1))
        tk.Label(k_h_header, text="Смещение высоты / роста:", font=("Segoe UI", 9), bg="#121318", fg="#cbd5e1").pack(side=tk.LEFT)
        self.lbl_h_val = tk.Label(k_h_header, text="+0.00 м", font=("Segoe UI", 9, "bold"), bg="#121318", fg="#00e676")
        self.lbl_h_val.pack(side=tk.RIGHT)

        k_h_btns = tk.Frame(kinect_card, bg="#121318")
        k_h_btns.pack(fill=tk.X, pady=(2, 4))
        b_h_up = tk.Button(k_h_btns, text="Выше (+5 см)", font=("Segoe UI", 8, "bold"),
                           bg="#0284c7", fg="#ffffff", relief="flat", padx=6, pady=3, cursor="hand2",
                           command=self.action_kinect_height_up)
        b_h_up.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 2))

        b_h_zero = tk.Button(k_h_btns, text="Сброс (0)", font=("Segoe UI", 8, "bold"),
                             bg="#334155", fg="#cbd5e1", relief="flat", padx=6, pady=3, cursor="hand2",
                             command=self.action_kinect_height_zero)
        b_h_zero.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=2)

        b_h_down = tk.Button(k_h_btns, text="Ниже (-5 см)", font=("Segoe UI", 8, "bold"),
                             bg="#0284c7", fg="#ffffff", relief="flat", padx=6, pady=3, cursor="hand2",
                             command=self.action_kinect_height_down)
        b_h_down.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(2, 0))

        # Camera passthrough & preview window controls
        k_cam_row = tk.Frame(kinect_card, bg="#121318")
        k_cam_row.pack(fill=tk.X, pady=(4, 2))
        self.btn_cam_mode = tk.Button(k_cam_row, text="📷 КАМЕРА: ВЫКЛ", font=("Segoe UI", 8, "bold"),
                                      bg="#00838f", fg="#ffffff", activebackground="#00acc1", activeforeground="#ffffff",
                                      relief="flat", padx=6, pady=3, cursor="hand2", command=self.action_toggle_cam_mode)
        self.btn_cam_mode.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 2))

        self.btn_cam_preview = tk.Button(k_cam_row, text="🪟 ОКНО КАМЕРЫ", font=("Segoe UI", 8, "bold"),
                                         bg="#0891b2", fg="#ffffff", activebackground="#06b6d4", activeforeground="#ffffff",
                                         relief="flat", padx=6, pady=3, cursor="hand2", command=self.action_toggle_cam_preview)
        self.btn_cam_preview.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=(2, 0))

        # 4. Bottom Activity Log
        log_frame = tk.Frame(self.root, bg="#1a1d26", padx=12, pady=6)
        log_frame.pack(fill=tk.X, padx=16, pady=(10, 16))

        tk.Label(log_frame, text="ЖУРНАЛ:", font=("Segoe UI", 8, "bold"), bg="#1a1d26", fg="#64748b").pack(side=tk.LEFT)
        self.lbl_log = tk.Label(log_frame, text="Панель запущена и готова к работе.", font=("Segoe UI", 9), bg="#1a1d26", fg="#94a3b8")
        self.lbl_log.pack(side=tk.LEFT, padx=8)

    def _create_slider_row(self, parent, label_text, min_val, max_val, default_val,
                           unit, factor, format_str, on_change, var_name, lbl_name):
        f = tk.Frame(parent, bg="#1a1d26")
        f.pack(fill=tk.X, pady=(4, 2))

        lbl = ttk.Label(f, text=label_text, style="Label.TLabel")
        lbl.pack(side=tk.LEFT)

        val_lbl = ttk.Label(f, text=format_str.format(default_val * factor if factor != 1 else default_val),
                            style="Value.TLabel")
        val_lbl.pack(side=tk.RIGHT)
        setattr(self, lbl_name, val_lbl)

        var = tk.DoubleVar(value=default_val)
        setattr(self, var_name, var)

        scale = ttk.Scale(parent, from_=min_val, to=max_val, variable=var,
                          orient=tk.HORIZONTAL, style="Horizontal.TScale",
                          command=lambda v: on_change(float(v), factor, format_str, val_lbl))
        scale.pack(fill=tk.X, pady=(0, 2))

    # ════════════════════════════════════════════════════════════════
    # Sliders
    # ════════════════════════════════════════════════════════════════
    def _on_scale_slider(self, val, factor, fmt, lbl):
        lbl.config(text=fmt.format(val * factor))
        if self._scale_job:
            self.root.after_cancel(self._scale_job)
        self._scale_job = self.root.after(40, lambda: self._send_scale(val))

    def set_scale_value(self, val):
        self.scale_var.set(val)
        self.scale_val_lbl.config(text=f"{int(val*100)}%")
        self._send_scale(val)

    def _send_scale(self, val):
        val_clamped = round(val, 2)
        self.send_phone_command(f"SCALE {val_clamped}")
        self.log(f"Масштаб линз: {int(val_clamped*100)}%")

    def _on_offset_slider(self, val, factor, fmt, lbl):
        int_val = int(round(val))
        lbl.config(text=fmt.format(int_val))
        if self._offset_job:
            self.root.after_cancel(self._offset_job)
        self._offset_job = self.root.after(40, lambda: self._send_offset(int_val))

    def set_offset_value(self, val):
        self.offset_var.set(val)
        self.offset_val_lbl.config(text=f"{val:+d} px")
        self._send_offset(val)

    def _send_offset(self, val):
        self.send_phone_command(f"OFFSET_Y {val}")
        self.log(f"Смещение Y: {val:+d} px")

    def _on_ipd_slider(self, val, factor, fmt, lbl):
        clamped = round(val, 1)
        lbl.config(text=fmt.format(clamped))
        if self._ipd_job:
            self.root.after_cancel(self._ipd_job)
        self._ipd_job = self.root.after(40, lambda: self._send_ipd(clamped))

    def set_ipd_value(self, val):
        self.ipd_var.set(val)
        self.ipd_val_lbl.config(text=f"{val:.1f} мм")
        self._send_ipd(val)

    def _send_ipd(self, val_mm):
        val_meters = val_mm / 1000.0
        self.send_driver_command(f"SET_IPD {val_meters:.4f}")
        self.send_driver_command("SET_IPD_SHIFT 0")
        self.send_phone_command(f"IPD {val_mm:.1f}")
        self.log(f"IPD (3D стереобаза SteamVR): {val_mm:.1f} мм")

    def _on_ui_scale_slider(self, val, factor, fmt, lbl):
        lbl.config(text=fmt.format(val * factor))
        if self._ui_scale_job:
            self.root.after_cancel(self._ui_scale_job)
        self._ui_scale_job = self.root.after(40, lambda: self._send_ui_scale(val))

    def _send_ui_scale(self, val):
        val_clamped = round(val, 2)
        self.send_phone_command(f"UI_SCALE {val_clamped}")
        self.log(f"Масштаб HUD: {int(val_clamped*100)}%")

    def _on_divider_slider(self, val, factor, fmt, lbl):
        int_val = int(round(val))
        lbl.config(text=fmt.format(int_val))
        if self._divider_job:
            self.root.after_cancel(self._divider_job)
        self._divider_job = self.root.after(40, lambda: self._send_divider(int_val))

    def _send_divider(self, val):
        self.send_phone_command(f"DIVIDER {val}")
        self.log(f"Ширина разделителя: {val} px")

    # ════════════════════════════════════════════════════════════════
    # Button Actions (Instant Visual Feedback)
    # ════════════════════════════════════════════════════════════════
    def action_start_vr(self):
        bat_path = os.path.join(VR_DIR, "Start_VR_Session.bat")
        if os.path.exists(bat_path):
            os.startfile(bat_path)
            self.log("Запущен 1-Click лаунчер Start_VR_Session.bat")
        else:
            ps1_path = os.path.join(VR_DIR, "Start_VR_Session.ps1")
            subprocess.Popen(["powershell", "-NoExit", "-ExecutionPolicy", "Bypass", "-File", ps1_path])
            self.log("Запущен Start_VR_Session.ps1")

    def action_stop_vr(self):
        bat_path = os.path.join(VR_DIR, "Stop_VR_Session.bat")
        if os.path.exists(bat_path):
            os.startfile(bat_path)
        self.log("Остановка всех процессов VR и трансляции...")

    def action_recenter(self):
        self.send_phone_command("RECENTER")
        orig_text = self.btn_recenter.cget("text")
        self.btn_recenter.config(text="✓ СЦЕНТРИРОВАНО!", bg="#059669")
        self.root.after(600, lambda: self.btn_recenter.config(text=orig_text, bg="#2563eb"))
        self.log("Центрирование взгляда: строго прямо 0°.")

    def action_flip_180(self):
        self.flip_180 = not self.flip_180
        self.send_phone_command("FLIP_180")
        if self.flip_180:
            self.btn_flip180.config(text="🔄 ВЗГЛЯД: РАЗВЕРНУТ 180°", bg="#b91c1c")
            self.log("Взгляд развернут на 180°.")
        else:
            self.btn_flip180.config(text="🔄 РАЗВЕРНУТЬ НА 180°", bg="#7c3aed")
            self.log("Взгляд возвращен в стандартное направление 0°.")

    def action_toggle_roll(self):
        self.roll_inv = not self.roll_inv
        self.send_phone_command("SET_ROLL 1" if self.roll_inv else "SET_ROLL 0")
        if self.roll_inv:
            self.btn_roll.config(text="НАКЛОН (ROLL): ИНВЕРСИЯ", bg="#c2410c", fg="#fdba74")
            self.log("Инверсия наклона головы (Roll) ВКЛЮЧЕНА.")
        else:
            self.btn_roll.config(text="НАКЛОН (ROLL): СТАНДАРТ", bg="#065f46", fg="#34d399")
            self.log("Наклон головы (Roll): СТАНДАРТНЫЙ режим.")

    def action_toggle_kinect_mirror(self):
        self.kinect_mirror = not self.kinect_mirror
        self.send_driver_command("SET_KINECT_MIRROR 1" if self.kinect_mirror else "SET_KINECT_MIRROR 0")
        if self.kinect_mirror:
            self.btn_mirror.config(text="СТОРОНЫ KINECT: ЗЕРКАЛЬНЫЕ", bg="#7e22ce", fg="#e9d5ff")
            self.log("Стороны Kinect переключены: ЗЕРКАЛЬНЫЙ РЕЖИМ.")
        else:
            self.btn_mirror.config(text="СТОРОНЫ KINECT: ПРЯМЫЕ", bg="#0e7490", fg="#38bdf8")
            self.log("Стороны Kinect переключены: ПРЯМОЙ РЕЖИМ (1:1).")

    def action_recenter_kinect(self):
        self.send_driver_command("RECENTER_KINECT")
        orig_text = self.btn_calib_kinect.cget("text")
        self.btn_calib_kinect.config(text="✓ KINECT СЦЕНТРИРОВАН!", bg="#059669")
        self.root.after(600, lambda: self.btn_calib_kinect.config(text=orig_text, bg="#0891b2"))
        self.log("Центр Kinect 360 зафиксирован в точке стояния!")

    def action_toggle_mic(self):
        self.mic_on = not self.mic_on
        self.send_phone_command("TOGGLE_MIC")
        if self.mic_on:
            self.btn_mic.config(text="🎙 МИКРОФОН: ВКЛ", bg="#059669", fg="#ffffff")
            self.log("Микрофон шлема ВКЛЮЧЕН.")
        else:
            self.btn_mic.config(text="🎙 МИКРОФОН: ВЫКЛ", bg="#1e293b", fg="#94a3b8")
            self.log("Микрофон шлема ВЫКЛЮЧЕН.")

    def action_toggle_hud(self):
        self.send_phone_command("TOGGLE_HUD")
        orig_text = self.btn_hud.cget("text")
        self.btn_hud.config(text="✓ HUD ПЕРЕКЛЮЧЕН", bg="#059669")
        self.root.after(600, lambda: self.btn_hud.config(text=orig_text, bg="#1e293b"))
        self.log("Интерфейс на экране телефона переключен.")

    def action_reset_defaults(self):
        self.send_phone_command("RESET")
        self.scale_var.set(1.0)
        self.scale_val_lbl.config(text="100%")
        self.offset_var.set(0)
        self.offset_val_lbl.config(text="+0 px")
        self.ipd_var.set(63.0)
        self.ipd_val_lbl.config(text="63.0 мм")
        self.ui_scale_var.set(1.0)
        self.ui_scale_val_lbl.config(text="100%")
        self.divider_var.set(0)
        self.divider_val_lbl.config(text="0 px")
        self.roll_inv = False
        self.flip_180 = False
        self.kinect_mirror = False
        self.btn_roll.config(text="НАКЛОН (ROLL): СТАНДАРТ", bg="#065f46", fg="#34d399")
        self.btn_flip180.config(text="🔄 РАЗВЕРНУТЬ НА 180°", bg="#7c3aed")
        self.btn_mirror.config(text="СТОРОНЫ KINECT: ПРЯМЫЕ", bg="#0e7490", fg="#38bdf8")
        self.send_driver_command("SET_IPD 0.0630")
        self.send_driver_command("SET_IPD_SHIFT 0")
        self.send_driver_command("SET_KINECT_MIRROR 0")
        self.send_driver_command("KINECT_TILT_ZERO")
        self.send_driver_command("SET_KINECT_SEATED 0")
        self.send_driver_command("KINECT_HEIGHT_ZERO")
        self.log("Все параметры сброшены к значениям по умолчанию.")

    def action_toggle_motor_lock(self):
        self.kinect_motor_locked = not self.kinect_motor_locked
        self.send_driver_command("TOGGLE_KINECT_MOTOR_LOCK")
        if self.kinect_motor_locked:
            self.btn_motor_lock.config(text="🔒 МОТОР KINECT: ЗАБЛОКИРОВАН", bg="#7f1d1d", fg="#fca5a5")
            self.log("Мотор Kinect заблокирован (защита от случайных движений и перезагрузок).")
        else:
            self.btn_motor_lock.config(text="🔓 МОТОР KINECT: РАЗРЕШЁН", bg="#065f46", fg="#34d399")
            self.log("Мотор Kinect разблокирован (плавное управление наклоном разрешено).")

    def action_kinect_tilt_up(self):
        if self.kinect_motor_locked:
            self.log("Мотор Kinect заблокирован! Разблокируйте его для изменения угла.")
            return
        self.send_driver_command("KINECT_TILT_UP")
        self.log("Kinect 360: Наклон мотора вверх (+3°).")

    def action_kinect_tilt_down(self):
        if self.kinect_motor_locked:
            self.log("Мотор Kinect заблокирован! Разблокируйте его для изменения угла.")
            return
        self.send_driver_command("KINECT_TILT_DOWN")
        self.log("Kinect 360: Наклон мотора вниз (-3°).")

    def action_kinect_tilt_zero(self):
        if self.kinect_motor_locked:
            self.log("Мотор Kinect заблокирован! Разблокируйте его для изменения угла.")
            return
        self.send_driver_command("KINECT_TILT_ZERO")
        self.log("Kinect 360: Плавный возврат в горизонт (0°)...")

    def action_toggle_kinect_seated(self):
        self.kinect_seated = not self.kinect_seated
        self.send_driver_command("SET_KINECT_SEATED 1" if self.kinect_seated else "SET_KINECT_SEATED 0")
        if self.kinect_seated:
            self.btn_kinect_mode.config(text="РЕЖИМ: СИДЯ (SEATED / ВЕРХ ТЕЛА)", bg="#c026d3")
            self.log("Kinect 360: Режим СИДЯ (отслеживается голова, руки, позвоночник).")
        else:
            self.btn_kinect_mode.config(text="РЕЖИМ: СТОЯ (ПОЛНЫЙ FBT)", bg="#059669")
            self.log("Kinect 360: Режим СТОЯ (полный трекинг 10 суставов тела).")

    def action_kinect_height_up(self):
        self.send_driver_command("KINECT_HEIGHT_UP")
        self.log("Kinect 360: Высота пола / трекеров увеличена (+5 см).")

    def action_kinect_height_down(self):
        self.send_driver_command("KINECT_HEIGHT_DOWN")
        self.log("Kinect 360: Высота пола / трекеров уменьшена (-5 см).")

    def action_kinect_height_zero(self):
        self.send_driver_command("KINECT_HEIGHT_ZERO")
        self.log("Kinect 360: Смещение высоты сброшено (0 см).")

    def action_toggle_kinect_auto_tilt(self):
        self.kinect_auto_tilt = not self.kinect_auto_tilt
        self.send_driver_command("SET_KINECT_AUTO_TILT 1" if self.kinect_auto_tilt else "SET_KINECT_AUTO_TILT 0")
        if self.kinect_auto_tilt:
            self.btn_auto_tilt.config(text="🎯 АВТО-ФОКУС: ВКЛ", bg="#1d4ed8", fg="#93c5fd")
            self.log("Kinect 360: Авто-фокус ВКЛ (сенсор удерживает тело и голову в кадре).")
        else:
            self.btn_auto_tilt.config(text="🎯 АВТО-ФОКУС: ВЫКЛ", bg="#334155", fg="#94a3b8")
            self.log("Kinect 360: Авто-фокус ВЫКЛ (ручной наклон).")

    def action_toggle_cam_mode(self):
        self.cam_mode = (self.cam_mode + 1) % 5
        self.send_driver_command(f"SET_CAM_MODE {self.cam_mode}")
        self.send_phone_command(f"SET_CAM_MODE {self.cam_mode}")
        names = ["ВЫКЛ", "RGB ЦВЕТ", "3D ГЛУБИНА", "IR ИК НОЧЬ", "PIP ОКНО"]
        self.btn_cam_mode.config(text=f"📷 КАМЕРА: {names[self.cam_mode]}")
        self.log(f"Режим камеры Kinect: {names[self.cam_mode]}")

    def action_toggle_cam_preview(self):
        if self.cam_preview_proc and self.cam_preview_proc.poll() is None:
            try:
                self.cam_preview_proc.terminate()
            except Exception:
                pass
            self.cam_preview_proc = None
            self.btn_cam_preview.config(text="🪟 ОКНО КАМЕРЫ", bg="#0891b2")
            self.log("Окно предпросмотра камеры Kinect закрыто.")
        else:
            ffplay_path = shutil.which("ffplay")
            if not ffplay_path:
                winget_ffplay = os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin\ffplay.exe")
                if os.path.exists(winget_ffplay):
                    ffplay_path = winget_ffplay
                else:
                    ffplay_path = "ffplay"

            cmd = [
                ffplay_path,
                "-f", "rawvideo",
                "-pixel_format", "bgra",
                "-video_size", "640x480",
                "-framerate", "30",
                "-window_title", "VRFusion - Превью камеры Kinect (640x480)",
                r"\\.\pipe\vrfusion_kinect_cam"
            ]
            try:
                self.cam_preview_proc = subprocess.Popen(cmd)
                self.btn_cam_preview.config(text="🪟 ЗАКРЫТЬ КАМЕРУ", bg="#dc2626")
                self.log("Окно камеры Kinect (640x480) запущено.")
            except Exception as e:
                self.log(f"Ошибка запуска предпросмотра камеры: {e}")

    # ════════════════════════════════════════════════════════════════
    # Communication Layer (ADB + Direct Sockets)
    # ════════════════════════════════════════════════════════════════
    def _detect_device(self):
        def worker():
            try:
                # Disconnect stray TV
                subprocess.run([ADB_PATH, "disconnect", "192.168.31.124:5555"],
                               capture_output=True, timeout=2, creationflags=CREATE_NO_WINDOW)
                # Find device serial
                res = subprocess.run([ADB_PATH, "devices"], capture_output=True, text=True,
                                     timeout=3, creationflags=CREATE_NO_WINDOW)
                for line in res.stdout.splitlines():
                    parts = line.strip().split()
                    if len(parts) >= 2 and parts[1] == "device" and "192.168.31.124" not in parts[0]:
                        self.serial = parts[0]
                        break

                # Forward ports
                subprocess.run([ADB_PATH, "-s", self.serial, "forward", f"tcp:{PHONE_CMD_PORT}", f"tcp:{PHONE_CMD_PORT}"],
                               capture_output=True, timeout=2, creationflags=CREATE_NO_WINDOW)
                subprocess.run([ADB_PATH, "-s", self.serial, "forward", "tcp:6004", "tcp:6004"],
                               capture_output=True, timeout=2, creationflags=CREATE_NO_WINDOW)
                subprocess.run([ADB_PATH, "-s", self.serial, "forward", "tcp:9550", "tcp:9550"],
                               capture_output=True, timeout=2, creationflags=CREATE_NO_WINDOW)

                # Wake up and ensure app is foreground
                subprocess.run([ADB_PATH, "-s", self.serial, "shell",
                                "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.vrfusion.tracker/.MainActivity; am start-foreground-service -a com.vrfusion.tracker.START -n com.vrfusion.tracker/.VRFusionService --es extra_ip 192.168.31.2"],
                               capture_output=True, timeout=3, creationflags=CREATE_NO_WINDOW)

                self.log(f"Телефон подключен ({self.serial}). Порты USB проброшены.")
            except Exception as e:
                self.log(f"ADB статус: {e}")

        threading.Thread(target=worker, daemon=True).start()

    def send_phone_command(self, cmd):
        def worker():
            # 1. Primary: Direct UDP to port 9552 (instant, non-blocking)
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.settimeout(0.1)
                data = (cmd + "\n").encode("utf-8")
                s.sendto(data, ("127.0.0.1", PHONE_CMD_PORT))
                s.close()
            except Exception:
                pass

            # 2. Fast ADB Broadcast with package specified and quoted cmd
            if os.path.exists(ADB_PATH):
                try:
                    subprocess.run(
                        [ADB_PATH, "-s", self.serial, "shell", "am", "broadcast",
                         "-a", "com.vrfusion.COMMAND", "-p", "com.vrfusion.tracker",
                         "--es", "cmd", f"'{cmd}'"],
                        capture_output=True, timeout=1.5, creationflags=CREATE_NO_WINDOW
                    )
                except Exception:
                    pass

            # 3. Direct TCP localhost
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(0.1)
                s.connect(("127.0.0.1", PHONE_CMD_PORT))
                s.sendall((cmd + "\n").encode("utf-8"))
                s.close()
            except Exception:
                pass

        threading.Thread(target=worker, daemon=True).start()

    def send_driver_command(self, cmd):
        def worker():
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.settimeout(0.3)
                s.sendto(cmd.encode("utf-8"), ("127.0.0.1", DRIVER_CMD_PORT))
                resp, _ = s.recvfrom(256)
                s.close()
                resp_str = resp.decode("utf-8", errors="ignore")
                if resp_str.startswith("STAT:"):
                    self._parse_stat(resp_str)
                    self.driver_connected = True
                elif resp_str.startswith("OK"):
                    self.driver_connected = True
            except Exception:
                pass

        threading.Thread(target=worker, daemon=True).start()

    # ════════════════════════════════════════════════════════════════
    # Telemetry Poller
    # ════════════════════════════════════════════════════════════════
    def _telemetry_worker(self):
        adb_check_counter = 0
        while self.polling_active:
            # 1. Query SteamVR driver via UDP port 9555
            driver_ok = False
            try:
                d = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                d.settimeout(0.3)
                d.sendto(b"GET_STAT", ("127.0.0.1", DRIVER_CMD_PORT))
                resp, _ = d.recvfrom(256)
                d.close()
                if resp:
                    resp_str = resp.decode("utf-8", errors="ignore")
                    if resp_str.startswith("STAT:"):
                        self._parse_stat(resp_str)
                    driver_ok = True
            except Exception:
                pass
            self.driver_connected = driver_ok

            # 2. Check phone presence every 1.5 seconds via ADB
            adb_check_counter += 1
            if adb_check_counter >= 3:
                adb_check_counter = 0
                try:
                    res = subprocess.run([ADB_PATH, "devices"], capture_output=True, text=True,
                                         timeout=1.5, creationflags=CREATE_NO_WINDOW)
                    for line in res.stdout.splitlines():
                        parts = line.strip().split()
                        if len(parts) >= 2 and parts[1] == "device" and "192.168.31.124" not in parts[0]:
                            self.serial = parts[0]
                            self.is_connected = True
                            break
                    else:
                        self.is_connected = False
                except Exception:
                    self.is_connected = False

            self.root.after(0, self._update_gui_telemetry)
            time.sleep(0.4)

    def _parse_stat(self, stat_str):
        try:
            body = stat_str.strip()
            if body.startswith("STAT:"):
                body = body[5:]
            parts = body.split(";")
            for p in parts:
                if "=" in p:
                    k, v = p.split("=", 1)
                    k = k.strip().upper()
                    v = v.strip()
                    if k == "YAW":
                        self.yaw = float(v)
                    elif k == "PITCH":
                        self.pitch = float(v)
                    elif k == "ROLL":
                        self.roll = float(v)
                    elif k == "BATT":
                        self.batt = int(v)
                    elif k == "KINECT":
                        self.kinect_tracking = (v == "1")
                    elif k == "KMIRROR":
                        self.kinect_mirror = (v == "1")
                    elif k == "TILT":
                        self.kinect_tilt = int(v)
                    elif k == "SEATED":
                        self.kinect_seated = (v == "1")
                    elif k == "HEIGHT":
                        self.kinect_height = float(v)
                    elif k == "AUTOTILT":
                        self.kinect_auto_tilt = (v == "1")
                    elif k == "CAM":
                        self.cam_mode = int(v)
                    elif k == "KX":
                        self.kinect_ax = float(v)
                    elif k == "KY":
                        self.kinect_ay = float(v)
                    elif k == "KZ":
                        self.kinect_az = float(v)
                    elif k == "KPITCH":
                        self.kinect_pitch = float(v)
                    elif k == "KROLL":
                        self.kinect_roll = float(v)
                    elif k == "MLOCK":
                        self.kinect_motor_locked = (v == "1")
        except Exception:
            pass

    def _update_gui_telemetry(self):
        if self.is_connected:
            self.lbl_phone_status.config(text=f"● ТЕЛЕФОН: СВЯЗЬ АКТИВНА ({self.serial})", bg="#065f46", fg="#34d399")
        else:
            self.lbl_phone_status.config(text="● ТЕЛЕФОН: ОЖИДАНИЕ USB/WI-FI", bg="#1e293b", fg="#94a3b8")

        if self.driver_connected:
            self.lbl_driver_status.config(text="● STEAMVR: ПОДКЛЮЧЕН", bg="#065f46", fg="#34d399")
        else:
            self.lbl_driver_status.config(text="● STEAMVR: НЕ ЗАПУЩЕН", bg="#1e293b", fg="#94a3b8")

        self.lbl_yaw.config(text=f"{self.yaw:+.1f}°")
        self.lbl_pitch.config(text=f"{self.pitch:+.1f}°")
        self.lbl_roll.config(text=f"{self.roll:+.1f}°")
        self.lbl_batt.config(text=f"{self.batt}%")

        if getattr(self, "kinect_tracking", False):
            self.lbl_kinect_status.config(text="ЧЕЛОВЕК ЗАХВАЧЕН (ТРЕКИНГ АКТИВЕН)", fg="#00e676")
        else:
            self.lbl_kinect_status.config(text="Ожидание скелета...", fg="#fbbf24")

        self.lbl_tilt_val.config(text=f"{self.kinect_tilt:+d}° (Физ: {self.kinect_pitch:+.1f}°)")
        if hasattr(self, 'lbl_kinect_acc'):
            self.lbl_kinect_acc.config(text=f"X:{self.kinect_ax:+.2f}g Y:{self.kinect_ay:+.2f}g Z:{self.kinect_az:+.2f}g (Roll:{self.kinect_roll:+.1f}°)")
        self.lbl_h_val.config(text=f"{self.kinect_height:+.2f} м")
        if self.kinect_seated:
            self.btn_kinect_mode.config(text="РЕЖИМ: СИДЯ (SEATED / ВЕРХ ТЕЛА)", bg="#c026d3")
        else:
            self.btn_kinect_mode.config(text="РЕЖИМ: СТОЯ (ПОЛНЫЙ FBT)", bg="#059669")

        if self.kinect_auto_tilt:
            self.btn_auto_tilt.config(text="🎯 АВТО-ФОКУС: ВКЛ", bg="#1d4ed8", fg="#93c5fd")
        else:
            self.btn_auto_tilt.config(text="🎯 АВТО-ФОКУС: ВЫКЛ", bg="#334155", fg="#94a3b8")

        if hasattr(self, 'btn_motor_lock'):
            if self.kinect_motor_locked:
                self.btn_motor_lock.config(text="🔒 МОТОР KINECT: ЗАБЛОКИРОВАН", bg="#7f1d1d", fg="#fca5a5")
            else:
                self.btn_motor_lock.config(text="🔓 МОТОР KINECT: РАЗРЕШЁН", bg="#065f46", fg="#34d399")

        cam_names = ["ВЫКЛ", "RGB ЦВЕТ", "3D ГЛУБИНА", "IR ИК НОЧЬ", "PIP ОКНО"]
        if hasattr(self, 'btn_cam_mode') and self.cam_mode < len(cam_names):
            self.btn_cam_mode.config(text=f"📷 КАМЕРА: {cam_names[self.cam_mode]}")

        if self.cam_preview_proc and self.cam_preview_proc.poll() is not None:
            self.cam_preview_proc = None
            self.btn_cam_preview.config(text="🪟 ОКНО КАМЕРЫ", bg="#0891b2")

    def log(self, msg):
        self.lbl_log.config(text=msg)

    def on_closing(self):
        self.polling_active = False
        if self.cam_preview_proc and self.cam_preview_proc.poll() is None:
            try:
                self.cam_preview_proc.terminate()
            except Exception:
                pass
        self.root.destroy()

if __name__ == "__main__":
    root = tk.Tk()
    app = VRFusionControlCenter(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()
