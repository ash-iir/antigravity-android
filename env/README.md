# Архитектура и Аудит Окружения Antigravity (env/)

> [!NOTE]
> Данный документ является официальной технической спецификацией и отчетом по аудиту корневого пространства `env/` в Antigravity (AGY v2).
> Он описывает состав rootfs, принципы работы пакетного менеджера `pkg`, систему динамического переписывания путей Android/Termux, механизмы совместимости ARMv8.1-A+ (LSE), а также выявленные костыли, узкие места и рекомендации по оптимизации.

---

## 1. Состав и Структура Rootfs (`env/`)

Окружение `env/` представляет собой легковесную, самодостаточную среду userspace Bionic на базе ARM64 (`aarch64`), предназначенную для работы внутри песочницы приложения Android без необходимости прав суперпользователя (`root`) или эмуляторов с перехватом системных вызовов (`proot` / `ptrace`).

### Карта каталогов

```
env/
├── README.md               # Данная техническая документация и отчет по аудиту
├── README                  # Базовое описание старого формата
├── build.sh                # Главный скрипт сборки и упаковки ассетов в dist/env
├── bootstrap/              # Базовый архив rootfs и утилиты модификации
│   ├── bootstrap-aarch64.zip # Исходный бинарный zip-архив Termux bootstrap
│   ├── bootstrap.tar.gz      # Оптимизированный tar.gz (symlinks, pruned docs)
│   └── patch-prefix.py       # (Legacy) Скрипт бинарного патчинга ELF для dpkg
├── packages/               # Дополнительные пакеты, вшиваемые в bootstrap
│   └── git.deb             # Предварительно подготовленный пакет Git для автономности
├── overlay/                # Наложение файлов поверх $PREFIX (etc, bin, lib)
│   ├── bin/                # Системные скрипты и утилиты AGY
│   │   ├── agy-daemon      # Фоновый автономный диспетчер задач (flock на daemon.lock) и планировщик
│   │   ├── agy-fix-paths   # Утилита динамической нормализации шебангов и путей
│   │   ├── agy-notify      # Утилита управления системными уведомлениями Android
│   │   ├── agy-root        # Обертка root (uid=0) с поддержкой KernelSU/bakaSU/Magisk/APatch и -mm
│   │   ├── agy-sys         # Root-утилита диагностики, Doze/OOM-защиты, модулей KSU/Magisk, pm и logcat
│   │   ├── agy-upgrade     # Менеджер безопасного обновления, бэкапа и миграций
│   │   ├── rish            # Клиент Shizuku для привилегированного шелла (uid=2000)
│   │   ├── su              # KernelSU/bakaSU/Magisk/APatch-совместимая обертка su
│   │   ├── sudo            # Совместимый с sudo фронтенд поверх agy-root
│   │   └── cs              # Заглушка для language server
│   ├── etc/                # Системная конфигурация
│   │   ├── antigravity/    # AGENTS.md (глобальные правила агента)
│   │   ├── pkg/repo.conf   # Конфигурация репозиториев pkg (packages-cf/termux/bfsu, tuna в конце fallback)
│   │   ├── profile.d/      # Скрипты инициализации оболочки (00-env.sh)
│   │   ├── tls/            # Сертификаты cert.pem и конфигурация openssl.cnf
│   │   ├── hosts           # Базовая таблица разрешения имен localhost
│   │   ├── motd            # Приветственное сообщение терминала
│   │   ├── profile         # Корневой профиль bash-сессии
│   │   └── resolv.conf     # Публичные DNS-серверы (1.1.1.1, 8.8.8.8)
│   └── lib/                # Системные разделяемые библиотеки
│       ├── libtermux-exec.so # Библиотека перехвата execve для трансляции путей
│       └── daemon/         # Модули Python для реализации agy-daemon
├── pkg/                    # Автономный пакетный менеджер pkg (pkg_core.py + Bash/AWK fallback)
│   ├── pkg                 # Исполняемый интерфейс командной строки (CLI)
│   └── lib/                # Модульная библиотека функций pkg
│       ├── pkg_core.py     # Единый Python-движок (-B): mtime-кэш db/index, DFS/upgrade plan, stage-install, ELF-патч
│       ├── util.sh         # Утилиты, переменные окружения, rewrite_prefix
│       ├── deb.sh          # Парсинг ar/.deb через pkg_core.py (и dd/tar fallback)
│       ├── db.sh           # Локальная база установленных пакетов (.meta, .files)
│       ├── fetch.sh        # Параллельная загрузка curl -Z --http2 + per-file fallback и SHA256
│       ├── index.sh        # Работа с индексами Packages и генерация TSV
│       ├── index.awk       # AWK-парсер debian stanzas в формат TSV (fallback)
│       ├── resolver.sh     # Граф зависимостей и валидация циклов (DFS)
│       ├── version.sh      # Обертки сравнения версий Debian
│       └── vercmp.awk      # Реализация семантики dpkg vercmp на чистом AWK (fallback)
├── liblse_compat/          # Модуль эмуляции инструкций ARMv8.1-A+ (LSE, SIMD)
│   ├── liblse_compat.c     # Исходный код перехвата SIGILL и софтверной эмуляции
│   ├── liblse_compat.so    # Скомпилированная разделяемая библиотека
│   └── build.sh            # Скрипт сборки liblse_compat.so
├── repo/                   # Локальный каталог кэша репозиториев (по умолчанию пуст)
├── scripts/                # Инженерные скрипты сопровождения
│   ├── pack_bootstrap.py   # Конвертер zip -> tar.gz с очисткой doc/man и инъекцией extra-deb
│   └── make_manifest.py    # Генератор детерминированного manifest.json для проверки целостности
├── dist/                   # Результат сборки assets для включения в APK
│   └── env/                # Зеркало assets/env (bootstrap, overlay, pkg, manifest.json)
└── tests/                  # Комплексный набор тестов
    ├── fixtures/           # Тестовые фикстуры (hello.deb, Packages)
    ├── selftest            # Автономное тестирование pkg (vercmp, db, resolver, deb)
    ├── invariant           # Проверка целостности ассетов и симлинков 1:1
    └── e3                  # E2E-тест развертывания rootfs во временный каталог и запуска

```

### Размещение в памяти и песочнице Android

При запуске приложения Android класс `com.agy.install.EnvInstaller` распаковывает ассеты в изолированное файловое пространство приложения:
- **`$HOME`**: `/data/user/<id>/<package_id>/files` (или `/data/data/<package_id>/files` на старых версиях Android).
- **`$PREFIX`**: `$HOME/usr` — корень Bionic rootfs.
- **`$PREFIX/bin`**: исполняемые файлы (`bash`, `sh`, `git`, `pkg`, `agy-daemon` и др.).
- **`$PREFIX/lib`**: разделяемые библиотеки Bionic (`libtermux-exec.so`).
- **`$PREFIX/etc`**: конфигурационные файлы, включая TLS-сертификаты (`etc/tls/cert.pem`).
- **`$PREFIX/libexec/pkg`**: системные модули менеджера `pkg`.
- **`$PREFIX/var/lib/pkg`**: база данных установленных пакетов (`installed/`), кэш `.deb` (`cache/`) и индексы (`index/`).

---

## 2. Логика и Архитектура Пакетного Менеджера `pkg`

Менеджер пакетов `pkg` устраняет зависимость от тяжелых утилит `dpkg` и `apt`: при наличии встроенного `python3` горячие пути (синхронизация с `dpkg/status`, индексация, разрешение зависимостей, планирование и staging-распаковка `.deb`) выполняются единым движком `pkg_core.py` (запускаемым с `-B` / `sys.dont_write_bytecode = True`, без генерации `__pycache__`), а при его отсутствии сохранен чистый POSIX Bash + AWK fallback.

### Поддерживаемые команды
| Команда | Описание |
| :--- | :--- |
| `pkg update` | Условное скачивание `Packages.gz` через `curl -z` (пропуск при неизменном `Last-Modified`), распаковка в `repo.N.Packages` и кэшированная по `mtime` сборка TSV-индекса. |
| `pkg install [--keep-cache] <pkg...>` | Preflight-проверка свободного места, DFS-планирование (`pkg_core.py plan`), параллельная загрузка `curl -Z --http2` с per-file зеркальным fallback и SHA256, staging-распаковка + патчинг (включая byte-length-preserving ELF-патч), регистрация в БД, авто-очистка `.deb` (если не передан `--keep-cache`) и мосты `runtime/bin`. |
| `pkg upgrade [--keep-cache]` | Полное разрешение графа зависимостей устаревших пакетов и их новых зависимостей через единый DFS-планировщик, параллельная загрузка и staging-установка. |
| `pkg remove <pkg...>` | Чтение `.files`, безопасное удаление файлов пакета и пустых родительских каталогов, удаление `.meta`. |
| `pkg list [regex]` | Вывод всех доступных пакетов из TSV-индексов с фильтрацией. |
| `pkg installed` | Список локально установленных пакетов и их версий. |
| `pkg info <pkg>` | Подробные метаданные пакета (размер, архитектура, зависимости, хэш, провайдеры). |
| `pkg search <query>` | Быстрый поиск пакетов по имени через `pkg_core.py search` (или AWK fallback). |
| `pkg why <file>` | Определение пакета-владельца указанного файла через реверсивный поиск по `.files`. |
| `pkg clean` | Очистка загруженных архивов `.deb` в `$PKG_CACHE`. |
| `pkg selftest` | Встроенный оффлайн-тест целостности шелла, сертификатов и конфигурации репозиториев. |

### Механизм разрешения зависимостей (`pkg_core.py` / `resolver.sh`)
Разрешение зависимостей работает на базе топологической сортировки и поиска в глубину (DFS):
1. **Предотвращение циклов:** Отслеживаются состояния посещения `doing` и `done`; при повторном входе в узел `doing` операция прерывается до записи на диск.
2. **Виртуальные пакеты (`Provides`):** Поддерживаются провайдеры виртуальных пакетов и альтернативы `pkgA | pkgB`.
3. **Ограничения версий и upgrade:** Условия `(>= 1.0)`, `(<< 2.0)`, `(= 1.2)` валидируются через Debian `vercmp`; при нехватке версии уже установленной зависимости или при `pkg upgrade` кандидаты включаются в `upgrade_set` с дотяжкой новых транзитивных зависимостей.

### Формат базы данных (`pkg_core.py` / `db.sh`)
Логика хранения минималистична и устойчива к сбоям:
- `$PKG_DB/<package>.meta`: ключевые поля формата `key=value` (`name`, `version`, `arch`). Запись ведется через атомарный `os.replace` / `mv` из `.tmp`.
- `$PKG_DB/<package>.files`: сортированный список относительных путей файлов, принадлежащих пакету.
- Синхронизация с dpkg (`sync_dpkg_status` / `db_sync_dpkg`): при наличии `$PREFIX/var/lib/dpkg/status` база `pkg` импортирует предустановленные пакеты, пропуская повторный парсинг по сопоставлению `mtime` файла статуса (`$PKG_DB/.dpkg.mtime`).

### Безопасная однопроходная установка `.deb` (`stage_install_deb`)
- Стрим `data.tar.*` читается через `dpkg-deb --fsys-tarfile` (или чистый Python `ar`/`gzip`/`lzma` fallback) в приватный каталог `$PKG_ROOT/tmp/stage.<pid>.<pkg>`.
- Префикс `data/(data|user/<id>)/<pkg>/files/usr/` отрезается явно по паттерну (устойчиво как с `./`, так и без ведущих `./`), до записи отклоняются любые `..`, абсолютные пути, `\\`, NUL-байты и относительные симлинки, выходящие за корень.
- **Патчинг на этапе staging:** текстовые файлы нормализуются под `$PREFIX`, а ELF-бинарники (`/data/data/com.termux`, `/data/user/0/com.termux`) правятся только с сохранением точной длины байт (дополнением `/` для `len(pkg_id) <= 10`, например `com.agy///`, с безопасным пропуском для более длинных ID вроде `com.agy.dev`). После успешной проверки файлы и симлинки атомарно переносятся в `$PREFIX`.

---

## 3. Динамическая Трансляция Путей Termux/Android

Поскольку пакеты Termux собираются с жестко зашитым путем `/data/data/com.termux/files/usr`, а приложение AGY работает в произвольном контексте (например, `/data/user/0/com.agy/files/usr` или `/data/user/10/com.agy.dev/files/usr`), применяется трехуровневая система адаптации:

### Уровни переписывания
```
┌─────────────────────────────────────────────────────────────┐
│ 1. Уровень установки (Install-Time / Java)                  │
│    TermuxShebangRewriter, TermuxPrefixRewriter,             │
│    TermuxSymlinkFixer в EnvInstaller.java                   │
│    -> Распаковка, нормализация путей и прав chmod           │
├─────────────────────────────────────────────────────────────┤
│ 2. Уровень среды исполнения (Runtime / LD_PRELOAD)         │
│    libtermux-exec.so перехватывает execve / execv           │
│    -> Перенаправляет вызовы /bin/sh и Termux-пути в $PREFIX │
├─────────────────────────────────────────────────────────────┤
│ 3. Уровень динамических пакетов (Post-Install / CLI)        │
│    - rewrite_prefix в pkg/lib/util.sh                       │
│    - agy-fix-paths для сторонних pip / npm скриптов         │
└─────────────────────────────────────────────────────────────┘
```

1. **`TermuxShebangRewriter` & `TermuxPrefixRewriter` (Java):** При установке или обновлении приложения сканируются каталоги `bin`, `libexec`, `etc`, `share`, `var`. Все текстовые вхождения регулярного выражения `/data/(?:data|user/[0-9]+)/[^/]+/files/usr` заменяются на активный `$PREFIX`.
2. **`TermuxSymlinkFixer` (Java):** Абсолютные символические ссылки, указывающие на несуществующий префикс `com.termux`, динамически пересоздаются с указанием на локальный `$PREFIX`.
3. **`libtermux-exec.so` (C / Bionic Hook):** Библиотека внедряется через `LD_PRELOAD` во все дочерние процессы шелла (через `00-env.sh`). Она перехватывает семейство функций `execve`, `execvp`, `fexecve`, подменяя вызовы `/bin/sh` и устаревшие пути Termux на исполняемые файлы активного `$PREFIX/bin`.
4. **`agy-fix-paths` & `pkg rewrite_prefix` (Bash):** При установке пакетов через `pkg` или установке глобальных CLI-утилит через `pip install` / `npm install -g`, скрипты содержат пути интерпретаторов. `agy-fix-paths` производит безопасное сканирование только текстовых файлов (пропуская бинарники и ELF) и атомарно заменяет пути на текущий `$PREFIX`.

---

## 4. Совместимость с ARMv8.1-A+ и Модуль `liblse_compat`

### Проблема LSE на мобильных процессорах
Современные бинарные файлы (включая Go-рантаймы, Node.js, компиляторы и утилиты Core) собираются под современную архитектуру `ARMv8.1-A+` с флагом аппаратных атомиков LSE (Large System Extensions).
Однако ряд популярных мобильных SoC (например, **Snapdragon 685** на ядрах Cortex-A53 / Redmi Note 12) аппаратно поддерживают только базовый набор инструкций **ARMv8.0-A**. При выполнении инструкций LSE на таких ядрах процессор генерирует исключение **`SIGILL` (Illegal Instruction, сигнал 4)**, приводя к падению приложения с ошибкой:
`FATAL ERROR: This binary was compiled with lse enabled`.

### Принцип работы `liblse_compat`
Библиотека `liblse_compat.so` решает эту проблему прозрачно на уровне ядра Linux без изменения бинарных файлов:
1. **Установка ядерного обработчика:** Конструктор с наивысшим приоритетом `__attribute__((constructor(101)))` вызывает системный вызов ядра `rt_sigaction(134)` напрямую через ассемблер (`svc #0`), минуя обертки libc. Это гарантирует установку обработчика до запуска других библиотек.
2. **Защита от перезаписи (`sigaction` hook):** `liblse_compat` экспортирует собственную функцию `sigaction`, перехватывающую попытки гостевых сред (например, рантайма Go или JVM) затереть обработчик `SIGILL`. Пользовательский обработчик сохраняется в `old_sigill_act` и вызывается только в том случае, если упавшая инструкция не принадлежит к эмулируемым.
3. **Софтверный декодер и эмулятор:**
   - **FEAT_LSE (ARMv8.1-A):** Эмулирует атомарные операции памяти: `LDADD`, `LDCLR`, `LDEOR`, `LDSET`, `SWP`, знаковые и беззнаковые максимумы/минимумы (`LDSMAX`, `LDSMIN`, `LDUMAX`, `LDUMIN`), а также атомарные Compare-and-Swap (`CAS`, `CASA`, `CASL`, `CASAL`, `CASP`, `CASPA`, `CASPL`, `CASPAL`) с использованием стандартных встроенных атомиков GCC/Clang (`__atomic_fetch_add`, `__atomic_compare_exchange_n`).
   - **FEAT_LRCPC / LRCPC2 (ARMv8.3-A / 8.4-A):** Эмулирует операции Load-Acquire RCpc: `LDAPR`, `LDAPRB`, `LDAPRH`, `LDAPUR`, `STLUR` с барьерами памяти `__atomic_thread_fence`.
   - **FEAT_DotProd (ARMv8.2-A / 8.4-A):** Эмулирует векторные и элементные инструкции скалярного произведения `SDOT`, `UDOT` для SIMD-регистров `V0`–`V31` (структура контекста `fpsimd_context`).
   - **FEAT_RDM (ARMv8.1-A):** Эмулирует векторные операции с округлением и удвоением `SQRDMLAH`, `SQRDMLSH`.
   - **FEAT_FCMA (ARMv8.3-A):** Эмулирует комплексное векторное сложение и умножение с накоплением `FCADD`, `FCMLA` (для типов `_Float16`, `float`, `double`).
4. **Смещение PC:** После успешной эмуляции программный счетчик смещается на 4 байта (`uc->uc_mcontext.pc += 4`), и управление немедленно возвращается в прерванный поток.

### Разделение миров: Bionic vs Glibc (Критический аспект)
В Antigravity сосуществуют две различные среды исполнения:
1. **Core Language Server / Core Daemon:** Работает под управлением **Glibc** в `runtime/`.
2. **Терминал, bash, утилиты pkg:** Работают под управлением **Android Bionic libc** в `$PREFIX/`.

> [!WARNING]
> Текущая сборка `env/liblse_compat/liblse_compat.so` скомпилирована против `libc.so.6` и `libdl.so.2` (GNU Glibc).
> По этой причине она **НЕ ДОЛЖНА** добавляться в глобальный `LD_PRELOAD` для Bionic-процессов терминала. Если Bionic linker (`linker64`) встречает библиотеку, требующую `libc.so.6`, запуск любого процесса (`/system/bin/sh`, `bash`, `ls`) немедленно аварийно завершается с ошибкой линковки:
> `CANNOT LINK EXECUTABLE: library "libc.so.6" not found`.
>
> Для Bionic-окружения код `00-env.sh` безопасно проверяет наличие `$PREFIX/lib/liblse_compat.so`, которого там нет. Библиотека `liblse_compat.so` загружается исключительно Glibc-загрузчиком через аргумент `--preload` в `RuntimeManager.java`.

---

## 5. Результаты Аудита: Выявленные Костыли, Оверхеды и Ошибки

В ходе глубокого анализа исходных кодов `env/` были зафиксированы следующие замечания:

### 1. Катастрофический оверхед в `pkg/lib/deb.sh` и парсерах `[ИСПРАВЛЕНО - Заход 2 / Batch B]`
- **Проблема (историческая):** Ранее парсинг `ar` в Bash/dd и 4-проходная распаковка `data.tar.*`, пофайловый AWK `db_sync_dpkg`, рекурсивные форки `resolver.sh` и таймауты зеркала `tuna` замедляли установку до секунд/минут.
- **Решение:** Введен единый движок `env/pkg/lib/pkg_core.py` (с сохранением Bash/AWK fallback):
  - `db_sync_dpkg` и `index_rebuild_all` кэшируются по `mtime`;
  - `pkg update` использует `curl -z` условный кэш; `repo.conf` переносит `mirrors.tuna.tsinghua.edu.cn` в конец списка;
  - `fetch_batch` в `fetch.sh` качает архивы параллельно через `curl -Z --http2` с per-file проверкой SHA256 и точечным retry упавших файлов по зеркалам;
  - `stage_install_deb` объединяет валидацию, распаковку во временный stage-каталог, патчинг (включая безопасный byte-length-preserving ELF-патч), перенос в `$PREFIX`, регистрацию в БД, мосты и авто-очистку кэша (`--keep-cache` для сохранения `.deb`).
- Также в `overlay/bin/agy-daemon` и `overlay/lib/daemon/executor.py` (Заход 2 / Batch A) переведена блокировка инстанса на `fcntl.flock` постоянного файла `daemon.lock` (с PID-проверкой процесса) и устранено дублирование строк в `daemon.log`.

### 2. Захардкоженные пути в скриптах `overlay/bin/`
- **`env/overlay/bin/agy-notify` [ИСПРАВЛЕНО - Заход 1]:**
  Ранее содержал абсолютный путь разработчика `SRC_LOGO="/storage/emulated/0/Documents/antigravity_release/docs/assets/logo.png"`.
  Устранен локальный путь; добавлен поиск по `$AGY_LOGO` и стандартным путям ассетов (`$PREFIX/share/antigravity/logo.png`, `$PREFIX/share/pixmaps/antigravity.png`) с безопасным fallback на системную иконку Android при отсутствии файла.
- **`env/overlay/bin/agy-upgrade` [ИСПРАВЛЕНО - Заход 1]:**
  Исправлено вычисление пути `HOME`:
  Ранее ошибочно использовалось `export HOME="${HOME:-/data/user/0/$(basename "$(dirname "$PREFIX")")/files}"`, приводившее к пути вида `/data/user/0/files/files`.
  Исправлено на стандартное и корректное: `export HOME="${HOME:-$(dirname "$PREFIX")}"`.
- **Шебанги `com.agy`:**
  В файлах `env/pkg/pkg`, `env/overlay/bin/agy-fix-paths`, `env/overlay/bin/agy-notify`, `env/overlay/bin/agy-upgrade` первой строкой указан шебанг:
  `#!/data/data/com.agy/files/usr/bin/bash` или `#!/data/user/0/com.agy/files/usr/bin/bash`.
  `TermuxShebangRewriter` перезаписывает их при установке APK.

### 3. Оверхед генерации мостов в `pkg/pkg` [ИСПРАВЛЕНО - Заход 1]
- **Проблема:** Ранее при установке каждого пакета скрипт `pkg` выполнял цикл `for bin_file in "$PREFIX/bin"/*; do` и пересканировал все бинарники в `$PREFIX/bin`.
- **Решение:** Цикл оптимизирован: теперь генерация мостов в `$PREFIX/../runtime/bin` запускается исключительно для бинарников из состава текущего устанавливаемого пакета (`$pkg_files` с префиксом `bin/`), без повторного сканирования `$PREFIX/bin/*`.

### 4. Разрыв цепочки сборки `liblse_compat` в `env/build.sh`
- Скрипт `env/build.sh` собирает `bootstrap.tar.gz` и копирует каталоги `pkg` и `overlay` в `dist/env`.
- При этом `liblse_compat` собирается отдельным скриптом `env/liblse_compat/build.sh`, но не включается в процесс `env/build.sh all` и не копируется в `dist/`.

### 5. Дублирование кода нормализации путей
Логика замены путей Termux на активный `$PREFIX` дублируется в 4 независимых местах:
1. `com.agy.install.TermuxPrefixRewriter` / `TermuxShebangRewriter` (Java);
2. `env/pkg/lib/util.sh` -> функция `rewrite_prefix` (Bash/sed);
3. `env/overlay/bin/agy-fix-paths` (Bash/sed);
4. `env/bootstrap/patch-prefix.py` (Python).

---

## 6. Рекомендации по Устранению и Оптимизации

1. **Оптимизация распаковщика и пайплайна `pkg`:** [x] Выполнено в Заходе 2 (Batch B) — единый движок `pkg_core.py`, однопроходная staging-установка через `dpkg-deb --fsys-tarfile` / `ar`, `curl -Z --http2` и `mtime`-кэш.
2. **Исправление путей:**
   - [x] Исправить вычисление `$HOME` в `env/overlay/bin/agy-upgrade` на `HOME="${HOME:-$(dirname "$PREFIX")}"` (выполнено в Заходе 1).
   - [x] Заменить жесткий путь к логотипу в `env/overlay/bin/agy-notify` на относительный путь через ассеты приложения или `$PREFIX/share/antigravity/logo.png` с fallback на системную иконку (выполнено в Заходе 1).
   - Использовать переносимый шебанг `#!/usr/bin/env bash` или `#!/bin/sh` для всех скриптов репозитория перед упаковкой.
3. **Оптимизация bridge-генератора в `pkg`:**
   - [x] Генерировать runtime-мосты только для тех файлов, которые были добавлены текущим установленным пакетом (из списка `$pkg_files`), а не для всех файлов каталога `$PREFIX/bin` (выполнено в Заходе 1).
4. **Интеграция LSE-компонентов:**
   - Добавить цель `liblse_compat` в `env/build.sh`.
   - При необходимости использования LSE-эмуляции в Bionic предусмотреть явную сборку `liblse_compat_bionic.so` через Android NDK без зависимостей от `libc.so.6`.

---

## 7. Инструкция по Сборке и Тестированию

### Сборка окружения
```bash
# 1. Сборка оптимизированного bootstrap.tar.gz из bootstrap-aarch64.zip
./env/build.sh bootstrap

# 2. Упаковка всех слоев окружения в dist/env (готово к включению в APK)
./env/build.sh assets

# 3. Полная пересборка всех компонентов
./env/build.sh all
```

### Запуск тестов
```bash
# 1. Автономный модульный тест ядра pkg (vercmp, resolver, db, deb)
bash env/tests/selftest

# 2. Проверка инвариантов и целостности дерева ассетов
bash env/tests/invariant

# 3. Полный интеграционный E2E-тест развертывания префикса
bash env/tests/e3
```
