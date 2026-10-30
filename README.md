# VRFusion 🥽

**Единая высокопроизводительная VR-система 120 FPS на базе смартфона (Samsung Galaxy S21 FE), нативного драйвера SteamVR и трекинга тела через Xbox 360 Kinect.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Android-green.svg)]()
[![SteamVR](https://img.shields.io/badge/SteamVR-2.x%20Ready-black.svg)]()
[![Framerate](https://img.shields.io/badge/FPS-120%20Hz-orange.svg)]()

---

## 🌟 Основные возможности

* **⚡ Прямой стерео-стриминг 120 FPS NVENC:**
  Захват кадров напрямую из виртуального дисплея композитора SteamVR через именованный пайп (`\\.\pipe\vrfusion_stream`). Аппаратное кодирование через NVIDIA NVENC (H.264 Ultra-Low Latency, профиль `ull`, 120 к/с) с передачей по USB через ADB или Wi-Fi (порт 6004). Задержка передачи минимальна.
* **🎯 Full Body Tracking (FBT) на базе Xbox 360 Kinect:**
  Нативная C++ интеграция с Microsoft Kinect SDK 1.8 прямо внутри драйвера SteamVR. Создает 7 полноценных виртуальных трекеров Vive (таз/пояс, колени, стопы, локти).
* **🕹️ Безопасное управление мотором и акселерометром Kinect:**
  Плавная регулировка угла наклона Kinect ($\pm 2^\circ$), автовыравнивание в 0°, защита по току USB и аппаратная блокировка мотора (Motor Lock) во избежание сбоев контроллера шины.
* **📱 Трекинг ориентации шлема 120 Hz (IMU):**
  Android-приложение на телефоне считывает показания высокочастотного датчика вращения (`TYPE_ROTATION_VECTOR`) и передает кватернион ориентации по UDP (порт 6000) с минимальным джиттером.
* **🛠️ Нативный драйвер SteamVR (`driver_vrfusion.dll`):**
  Полноценная реализация интерфейсов OpenVR (`ITrackedDeviceServerDriver`, `IVRDisplayComponent`, `IVRVirtualDisplay`). Реализована поддержка датчика присутствия (`/input/proximity/click`) и Standby: при отсутствии движения более 15 секунд SteamVR приостанавливается и мгновенно просыпается при надевании шлема.
* **🎛️ VRFusion Control Center (Python GUI):**
  Интуитивная панель управления на Tkinter для запуска всей связки в 1 клик, настройки калибровки (IPD, смещение осей, масштаб линз), контроля мотора Kinect и отображения телеметрии реального времени.

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
|                                   | (ADB/Wi-Fi)   |                                   |
| driver_vrfusion.dll (OpenVR)      |               |                                   |
|   ├── Kinect 360 Skeleton FBT     |  UDP 6000     |  IMU Sensor Service               |
|   └── HMD Orientation Receiver <──┼───────────────┤  └── Fast Quaternion Stream (120Hz)
|                                   |               |                                   |
| VRFusion Control Center (GUI)     |  TCP 9550/9552|  Settings & Touch HUD             |
|   └── Driver UDP API (9555) <────>│<─────────────>│  └── IPD / HUD / Volume Center    |
+-----------------------------------+               +-----------------------------------+
```

| Порт | Протокол | Направление | Описание |
|---|---|---|---|
| **6000** | UDP | Телефон $\rightarrow$ ПК | Пакеты ориентации IMU (120 Гц, кватернион $W, X, Y, Z$) |
| **6004** | TCP | ПК $\rightarrow$ Телефон | Видеопоток 120 FPS Stereo H.264 (NVENC) |
| **9550 / 9552** | TCP | ПК $\leftrightarrow$ Телефон | Командный протокол настроек очков (IPD, шкала, переключение HUD) |
| **9555** | UDP | Control Center $\leftrightarrow$ Driver | Телеметрия драйвера, угол мотора Kinect, Lock мотора, IPD |

---

## 🚀 Быстрый старт

### 1. Подготовка оборудования
1. Подключите смартфон Samsung к ПК по USB-кабелю (включите режим «Отладка по USB» в параметрах разработчика Android).
2. Подключите датчик Xbox 360 Kinect через специальный USB-переходник с блоком питания 12V.

### 2. Запуск в 1 клик
Запустите `VRFusion_Control_Center.bat` (или `Start_VR_Session.bat`).

Центр управления автоматически:
* Проверит связь с телефоном и настроит проброс портов ADB;
* Запустит приложение `com.vrfusion.tracker` на смартфоне;
* Инициализирует драйвер SteamVR с поддержкой Kinect FBT;
* Поднимет NVENC 120 FPS стерео-трансляцию.

### 3. Калибровка в шлеме
* Вставьте телефон в VR-кейс (очки).
* Посмотрите прямо перед собой и нажмите **кнопку громкости (+ или -)** на телефоне — ориентация шлема мгновенно отцентрируется.
* Коснитесь экрана смартфона один раз, чтобы скрыть служебный HUD со счетчиками.
* Для сброса наклона Kinect нажмите кнопку **Установить 0°** в панели Control Center.

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
Скрипт скомпилирует Java/Kotlin исходники из `app_src`, создаст `classes.dex`, упакует ресурсы, выполнит `zipalign`, подпишет APK ключом `debug.keystore` и установит его на подключенное устройство через ADB.

---

## 📁 Структура репозитория

```
VRFusion/
├── AndroidApp/                  # Исходный Android-проект
├── app_src/                     # Исходный код приложения VRFusion (Java, AndroidManifest, res)
├── driver_vrfusion/             # Исходный код C++ драйвера SteamVR
│   ├── openvr/                  # Заголовочные файлы OpenVR Driver API
│   ├── resources/               # Манифесты драйвера, иконки статусов и профили ввода
│   └── src/driver_vrfusion.cpp  # Основной код драйвера (Display, Kinect FBT, Named Pipe)
├── platform-tools/              # Утилиты Android Debug Bridge (ADB)
├── tests/                       # Диагностические тесты Kinect и скрипты проверки окон
├── build_driver.bat             # Скрипт сборки драйвера SteamVR
├── build_driver.ps1             # PowerShell-сборщик драйвера
├── build_apk.ps1                # Автоматический сборщик и установщик Android APK
├── Start_VR_Session.bat         # 1-Click запуск VR сессии
├── Start_VR_Session.ps1         # Логика запуска ADB, SteamVR и FFmpeg NVENC пайплайна
├── Stop_VR_Session.bat          # Корректное завершение всех процессов сессии
├── VRFusion_Control_Center.py   # Графическая панель управления (GUI)
├── VRFusion_Control_Center.bat  # Быстрый запуск панели управления
├── LICENSE                      # Лицензия MIT
└── README.md                    # Документация проекта
```

---

## 📜 Лицензия

Проект распространяется под открытой лицензией **MIT**. Подробности в файле [LICENSE](LICENSE).
