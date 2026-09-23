# Terminal Free

Изолированный терминал с Ubuntu 26.04 под PRoot для Android (arm64).
Работает без root — в основе лежит эмуляция chroot через `proot`.

📦 **Скачать APK**: [TerminalFree-v1.0.0.apk](https://github.com/Flawl3ssss/terminal-free/releases/download/v1.0.0/TerminalFree-v1.0.0.apk)

- **Изоляция**: контейнер не видит папки телефона. Никаких биндов
  `/sdcard`, `/storage`, `/mnt`, `/data`; приложение не запрашивает
  разрешений на хранилище/медиа, `allowBackup=false`.
- **Оптимизация фона**: по умолчанию — никаких постоянных уведомлений и
  wakelock; свайп приложения из списка задач полностью убивает все процессы
  (сервис + proot + дочерние процессы через `killProcessGroup`).
- **Опциональный фоновый режим**: переключатель «Background mode» включает
  foreground-service с уведомлением (для длинных сборок `apt`/`make`,
  когда экран погашен).

Целевое устройство: Xiaomi 13T (Dimensity 8200-Ultra, arm64), Android 16.

## Как это устроено

```
TerminalFree (APK, com.terminalfree.app)
└── TerminalActivity ── launch.sh
    └── libproot.so (bionic, arm64)          ← jniLibs
        -r  <app-private>/files/rootfs/ubuntu   ← корень контейнера
        -b  /dev /proc /sys                     ← только системные узлы
        -b  /system /apex /linkerconfig         ← системные файлы Android
                                                  (для bootstrap-шелла)
        ✗ НЕ монтируются: /sdcard /storage /mnt /data
        └── /bin/bash (Ubuntu 26.04, из rootfs)
```

- Rootfs собирается workflow'ем **Build Rootfs** (debootstrap/ubuntu-base +
  QEMU) и публикуется как release-тег `rootfs-v1` этого репозитория.
- Приложение качает `ubuntu-aarch64-rootfs.tar.xz` при первом запуске
  (URL зашит в `DistroRegistry.kt`), распаковывает в приватную папку
  и запускает через proot.

## Режимы фоновой работы

| Действие | Поведение |
| --- | --- |
| Свёртывание (Home) | Сессии продолжают работать в кэше системы; на HyperOS процесс может быть убит системой — это штатно. |
| Свайп из «Недавних» (по умолчанию) | Полное завершение: все сессии, proot и процесс приложения уничтожаются, уведомлений не остаётся. Отключается переключателем «Full close on swipe». |
| «Background mode» = ON | Foreground-service с уведомлением (CPU%, Wake lock, Exit), `START_STICKY`, сессии переживают сворачивание и свайп. Закрывается кнопкой **Exit** в уведомлении. |
| «Wake lock» = ON | PARTIAL_WAKE_LOCK на 30 минут + `FLAG_KEEP_SCREEN_ON`, пока экран открыт. |
| Кнопка Exit в уведомлении | Грейс-terminate сессий + `killProcessGroup` +自杀-процесса — ноль фоновых процессов. |

Нет: автозапуска по загрузке, циклов JobScheduler/AlarmManager (кроме
опционального auto-night mode), виджетов, веб-views. В релизе отключён
ANR-watchdog.

## Сборка через GitHub Actions

1. **Build Rootfs** (Actions → Build Rootfs → Run workflow) — соберёт
   `ubuntu-aarch64-rootfs.tar.xz` и положит в release `rootfs-v1`.
2. **Build APK** (Actions → Build APK → Run workflow) — соберёт подписанный
   release APK, артефакт `terminal-free-apk`. Тег вида `v1.0.0` дополнительно
   создаёт GitHub Release с APK.

Секреты репозитория (Settings → Secrets → Actions):

| Secret | Содержимое |
| --- | --- |
| `KEYSTORE_BASE64` | base64 от `app/terminal-free.jks` |
| `KEYSTORE_PASSWORD` | пароль keystore |
| `KEY_ALIAS` | alias ключа |
| `KEY_PASSWORD` | пароль ключа |

Локальная сборка: `./gradlew assembleRelease` (нужен Android SDK 37 и
keystore в `app/terminal-free.jks`).

## Установка на телефон

1. Скачайте `TerminalFree-*.apk` из артефактов/релизов.
2. На телефоне: открыть APK → «Установить» (нужно разрешение «Установка из
   неизвестных источников» для файлового менеджера/браузера).
3. Первый запуск: согласиться на уведомления (нужны только для фонового
   режима), выбрать Ubuntu → Install (качается rootfs, ~30–60 сек на Wi-Fi).

### Рекомендации для Xiaomi 13T / HyperOS

- Если включён «Background mode» — в *Настройки → Приложения → Terminal Free →
  Батарея* выбрать **Без ограничений**, иначе MIUI может убить сервис.
- В обычном режиме (по умолчанию) ничего настраивать не нужно: приложение
  не живёт в фоне и не расходует батарею.

## Структура репозитория

```
app/                     Android-приложение (Kotlin, AGP, compileSdk 37)
  src/main/jniLibs/      libproot.so + loader (bionic, arm64/arm)
  src/main/assets/       xz, libfakeuid, шаблоны bashrc, шрифты
rootfs/builders/         скрипты сборки дистрибутивов (ubuntu.sh и др.)
.github/workflows/       Build APK, Build Rootfs
native/                  скрипт пересборки proot (build-proot.sh)
```

## Лицензия

GPL-3.0 (см. `LICENSE`). Основа проекта — [RedTerm](https://github.com/GlobalTechInfo/RedTerm)
(GPL-3.0), терминальный виджет — termux terminal-view/emulator, proot —
GPL-2.0+. Список компонентов: `NOTICE.md`.
