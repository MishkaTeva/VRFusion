# Руководство по участию в проекте (Contributing to VRFusion)

Спасибо за интерес к развитию **VRFusion**! Проект создан для обеспечения максимально плавного, доступного и высокопроизводительного VR с частотой 120 FPS и отслеживанием всего тела (Full Body Tracking).

---

## 🛠️ Как внести вклад

### 1. Сообщить об ошибке или предложить функцию
- Откройте новый [Issue](https://github.com/MishkaTeva/VRFusion/issues), выбрав подходящий шаблон (`Bug report` или `Feature request`).
- Опишите проблему подробно, указав модель смартфона, версию SteamVR и тип подключения (USB или Wi-Fi).

### 2. Разработка и Pull Requests
1. Сделайте **Fork** репозитория.
2. Создайте тематическую ветку (`git checkout -b feature/amazing-feature`).
3. Внесите изменения, соблюдая стиль кода:
   - C++17 для драйвера SteamVR (`driver_vrfusion.cpp`).
   - Python 3.10+ для панели управления (`VRFusion_Control_Center.py`).
   - Java 8 / Android SDK 34 для Android-приложения (`app_src`).
4. Проверьте компиляцию:
   - Драйвер: `build_driver.bat`
   - Приложение: `.\build_apk.ps1`
   - Панель управления: `python -m py_compile VRFusion_Control_Center.py`
5. Сделайте коммит (`git commit -m "Add amazing feature"`) и отправьте в свой форк (`git push origin feature/amazing-feature`).
6. Откройте **Pull Request** в ветку `master` основного репозитория.

---

## 📜 Лицензия
Участвуя в разработке, вы соглашаетесь с тем, что ваш код будет лицензирован в соответствии с лицензией [MIT](LICENSE).
