# VRFusion 🥽

[![Release](https://img.shields.io/github/v/release/MishkaTeva/VRFusion?color=00e676&label=Release&logo=github)](https://github.com/MishkaTeva/VRFusion/releases/latest)
[![SteamVR](https://img.shields.io/badge/SteamVR-2.x%20Ready-0070BA?logo=steam)](https://store.steampowered.com/app/250820/SteamVR/)
[![Display](https://img.shields.io/badge/Display-120%20FPS%20AMOLED-76B900?logo=nvidia)](https://github.com/MishkaTeva/VRFusion)
[![Tracking](https://img.shields.io/badge/FBT-Xbox%20360%20Kinect-107C41?logo=xbox)](https://github.com/MishkaTeva/VRFusion)
[![Client](https://img.shields.io/badge/Client-Android%20APK-3DDC84?logo=android)](https://github.com/MishkaTeva/VRFusion/releases/latest)
[![Driver](https://img.shields.io/badge/Driver-C%2B%2B17%20OpenVR-00599C?logo=c%2B%2B)](https://github.com/MishkaTeva/VRFusion)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/MishkaTeva/VRFusion/actions/workflows/ci.yml/badge.svg)](https://github.com/MishkaTeva/VRFusion/actions)

**High-performance DIY SteamVR System: 120 FPS NVENC Stereo Streaming, Android HMD, Xbox 360 Kinect Full Body Tracking (FBT) & Unified Control Center.**

*Единая высокопроизводительная VR-система 120 FPS на базе смартфона (Samsung Galaxy S21 FE), нативного драйвера SteamVR и трекинга тела через Xbox 360 Kinect.*

---

## 📥 Загрузка (Quick Download)

Готовый клиент для Android доступен в разделе релизов:
* **[Скачать VRFusion.apk (v2.0.0)](https://github.com/MishkaTeva/VRFusion/releases/latest)**

---

## 🌟 Основные возможности

* **⚡ Прямой стерео-стриминг 120 FPS NVENC:**
  Захват кадров напрямую из виртуального дисплея композитора SteamVR через именованный пайп (`\\.\pipe\vrfusion_stream`). Аппаратное кодирование через NVIDIA NVENC (H.264 Ultra-Low Latency, профиль `ull`, 120 к/с) с передачей по USB (ADB) или прямому Wi-Fi (порт 6004). Минимальная задержка передачи (<15 мс).
* **📶 Двойной режим связи (USB + Wi-Fi):**
  Полная поддержка работы как по USB-кабелю (нулевые потери, мгновенный отклик), так и по беспроводной сети Wi-Fi 5 ГГц. Автоматическое определение типа подключения, авто-пробуждение и проброс портов.
* **🎯 Full Body Tracking (FBT) на базе Xbox 360 Kinect:**
  Нативная C++ интеграция с Microsoft Kinect SDK 1.8 прямо внутри драйвера SteamVR. Создает 7 полноценных виртуальных трекеров Vive (таз/пояс, колени, стопы, локти).
* **🤖 Интеллектуальный авто-фокус и защита мотора Kinect:**
  Плавный поворот в один шаг, динамическая мертвая зона комфорта ($\pm 14^\circ$), программное ограничение диапазона $[-8^\circ, +14^\circ]$ (предотвращает уход в пол/потолок), автовозврат в 0° при потере скелета и аппаратная блокировка мотора (Motor Lock).
* **📱 Высокочастотный трекинг ориентации шлема 120 Hz (IMU):**
  Android-приложение считывает показания высокочастотного датчика вращения (`TYPE_GAME_ROTATION_VECTOR`) и передает кватернион ориентации по UDP (порт 9550) с суб-миллисекундным джиттером.
* **🛠️ Нативный драйвер SteamVR (`driver_vrfusion.dll`):**
  Полноценная реализация интерфейсов OpenVR (`ITrackedDeviceServerDriver`, `IVRDisplayComponent`, `IVRVirtualDisplay`). Реализована поддержка датчика присутствия (`/input/proximity/click`) и Standby: при отсутствии движения SteamVR экономит ресурсы GPU.
* **🎛️ VRFusion Control Center (Python GUI):**
  Интуитивная панель управления на Tkinter для запуска в 1 клик, калибровки на лету (IPD, смещение осей, масштаб линз, разделитель экранов), контроля мотора Kinect и живой телеметрии.

---

## 📐 Архитектура портов и сетевых каналов

```
+-----------------------------------+               +-----------------------------------+
|          PC (Windows 11)          |               |     Phone (Samsung S21 FE)        |
|                                   |               |                                   |
| SteamVR Compositor                |               |  VRFusion App (Android)           |
|   └── Named Pipe (Raw RGBA 120Hz) |               |                                   |
|          │                        |               |                                   |
|        FFmpeg                     |  TCP 6004     |  Low-latency H.264 Decoder        |
|   └── NVENC H.264 Encoder ───────┼──────────────>│  └── Stereo SBS Render (120 Hz)   |
|                                   | (USB / Wi-Fi) |                                   |
| driver_vrfusion.dll (OpenVR)      |               |                                   |
|   ├── Kinect 360 Skeleton FBT     |  UDP 9550     |  IMU Sensor Service               |
|   └── HMD Orientation Receiver <──┼───────────────┤  └── Fast Quaternion Stream (120Hz)
|                                   |               |                                   |
| VRFusion Control Center (GUI)     |  UDP/TCP 9552 |  Settings & Touch HUD             |
|   └── Driver UDP API (9555) <────>│<─────────────>│  └── IPD / HUD / Volume Center    |
+-----------------------------------+               +-----------------------------------+
```

| Порт | Протокол | Направление | Описание |
|---|---|---|---|
| **9550** | UDP | Телефон $\rightarrow$ ПК | Пакеты ориентации IMU (120 Гц, кватернион $W, X, Y, Z$) |
| **6004** | TCP | ПК $\rightarrow$ Телефон | Видеопоток 120 FPS Stereo H.264 (NVENC) |
| **9552** | UDP / TCP | ПК $\leftrightarrow$ Телефон | Командный протокол настроек (IPD, масштаб, смещение, сброс) |
| **9555** | UDP | Control Center $\leftrightarrow$ Driver | Телеметрия драйвера, угол мотора Kinect, Lock мотора, IPD |

---

## 🚀 Быстрый старт

### 1. Подготовка оборудования
1. Подключите смартфон к ПК по USB или подключите к общей сети Wi-Fi 5 ГГц (включите «Отладку по USB» в параметрах разработчика Android).
2. Подключите датчик Xbox 360 Kinect через USB-адаптер с питанием 12V.

### 2. Запуск в 1 клик
Запустите `VRFusion_Control_Center.bat` (или `Start_VR_Session.bat`).

Центр управления автоматически:
* Определит тип подключения (USB или Wi-Fi);
* Настроит проброс портов и свяжется с телефоном;
* Запустит приложение `com.vrfusion.tracker` на смартфоне;
* Инициализирует драйвер SteamVR с поддержкой Kinect FBT;
* Поднимет NVENC 120 FPS стерео-трансляцию.

### 3. Калибровка в шлеме
* Вставьте телефон в VR-кейс (очки).
* Посмотрите прямо перед собой и нажмите **кнопку громкости (+ или -)** на телефоне — ориентация мгновенно сцентрируется.
* Коснитесь экрана смартфона один раз, чтобы скрыть служебный HUD со счетчиками.
* Для сброса наклона Kinect нажмите **0° Горизонт** в панели Control Center.

---

## 🛠️ Сборка проекта

### Требования
* **ОС:** Windows 10/11 x64
* **Компилятор C++:** Visual Studio 2022 (MSVC v143 x64)
* **SDK:** Microsoft Kinect for Windows SDK v1.8
* **Android:** Java JDK 17+, Android SDK (Build-Tools 34)
* **Python:** Python 3.10+ (`tkinter`, `PIL`)

### Сборка драйвера SteamVR (`driver_vrfusion.dll`)
Выполните скрипт сборки:
```cmd
build_driver.bat
```
Скрипт автоматически скомпилирует `driver_vrfusion.dll` и скопирует его в директорию `SteamVR\drivers\vrfusion\bin\win64\`.

### Сборка приложения Android (`VRFusion.apk`)
Выполните сборку через PowerShell:
```powershell
.\build_apk.ps1
```
Скрипт скомпилирует исходники из `app_src`, создаст `classes.dex`, упакует ресурсы, выполнит `zipalign`, подпишет APK ключом `debug.keystore` и установит его на подключенное устройство через ADB.

---

## 📁 Структура репозитория

```
VRFusion/
├── .github/                     # GitHub Actions CI & шаблоны Issues
│   ├── workflows/ci.yml         # Непрерывная интеграция и проверка синтаксиса
│   └── ISSUE_TEMPLATE/          # Шаблоны баг-репортов и предложений
├── AndroidApp/                  # Исходный Android-проект
├── app_src/                     # Исходный код приложения VRFusion (Java, AndroidManifest, res)
├── driver_vrfusion/             # Исходный код C++ драйвера SteamVR
│   ├── openvr/                  # Заголовочные файлы OpenVR Driver API
│   ├── resources/               # Манифесты драйвера, иконки статусов и профили ввода
│   └── src/driver_vrfusion.cpp  # Основной код драйвера (Display, Kinect FBT, Named Pipe)
├── platform-tools/              # Утилиты Android Debug Bridge (ADB)
├── tests/                       # Диагностические тесты Kinect и скрипты проверки
├── build_driver.bat             # Скрипт сборки драйвера SteamVR
├── build_driver.ps1             # PowerShell-сборщик драйвера
├── build_apk.ps1                # Автоматический сборщик и установщик Android APK
├── Start_VR_Session.bat         # 1-Click запуск VR сессии
├── Start_VR_Session.ps1         # Логика запуска ADB, SteamVR и FFmpeg NVENC пайплайна
├── Stop_VR_Session.bat          # Корректное завершение всех процессов сессии
├── VRFusion_Control_Center.py   # Графическая панель управления (GUI)
├── VRFusion_Control_Center.bat  # Быстрый запуск панели управления
├── CONTRIBUTING.md              # Руководство по участию в проекте
├── LICENSE                      # Лицензия MIT
└── README.md                    # Документация проекта
```

---

## 📜 Лицензия

Проект распространяется под открытой лицензией **MIT**. Подробности в файле [LICENSE](LICENSE).
