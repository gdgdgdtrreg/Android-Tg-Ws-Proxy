# Сторонние компоненты

Собственный код проекта (Kotlin/Compose, мост `app/src/main/python/proxy_runner.py`, ресурсы и скрипты сборки) публикуется под
**GPL-3.0-or-later** — GNU General Public License версии 3 или новее, полный текст
в [LICENSE](LICENSE). Copyright (C) 2026 gdgdgdtrreg. Свои файлы помечены
SPDX-заголовком `SPDX-License-Identifier: GPL-3.0-or-later`; вендоренное ядро
`app/src/main/python/proxy/` оставлено байт в байт как в upstream и своих
заголовков не получило — оно остаётся под своей MIT.

Ниже перечислено всё стороннее, что попадает в исходники и в собранный APK.

**Совместимость с GPL-3.0.** Все лицензии в этом списке — MIT, MIT-0,
BSD-3-Clause, PSF-2.0, Apache-2.0 и MPL-2.0 — разрешают включать свой код в
произведение под GPL-3.0: производное произведение в целом распространяется под
GPLv3, а отдельные файлы сторонних компонентов остаются под своими лицензиями.
Apache-2.0 совместим с GPLv3 начиная с третьей версии; MPL-2.0 у certifi действует
пофайлово и распространяется только на сам certifi, чей исходный код доступен по
адресу из раздела 3. JUnit под EPL-1.0 объявлен как `testImplementation` и в APK
не попадает — иначе EPL-1.0 с GPLv3 не сочетался бы.

**Что видит пользователь.** Те же уведомления встроены в приложение: экран
«О приложении» (кнопка на главном экране) показывает версию, лицензию приложения с
полным текстом, адрес исходного кода и список компонентов с их лицензиями. Полные
тексты лицензий лежат в `app/src/main/res/raw/` — `gpl3.txt`, `mit.txt`,
`mit0.txt`, `bsd3_clause.txt`, `apache2.txt`, `psf2.txt`, `mpl2.txt`; сводное
уведомление на английском — в `res/raw/notices.txt`. Собственные тексты лицензий
Python-пакетов попадают в APK вместе с их метаданными
(`<имя>-<версия>.dist-info/LICENSE` внутри `assets/chaquopy/`).

## 1. Вендоренное ядро прокси

| Что | Где лежит | Версия | Лицензия |
| --- | --- | --- | --- |
| [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) | `app/src/main/python/proxy/` | 1.11.2 | MIT |

Python-код ядра скопирован из репозитория **без изменений**: обновление ядра —
замена этой папки целиком. Мост между Kotlin и ядром
(`app/src/main/python/proxy_runner.py`) написан в этом проекте и в вендоренную
папку не входит.

```
MIT License

Copyright (c) 2026 Flowseal

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. Chaquopy — Python для Android

| Что | Откуда берётся | Версия | Лицензия |
| --- | --- | --- | --- |
| Gradle-плагин `com.chaquo.python` | репозиторий Chaquopy (тянет Gradle) | 17.0.0 | MIT |
| Рантайм Python и нативные библиотеки | артефакты `com.chaquo.python:target-*` | 3.10–3.13 | MIT |

В собранный APK попадают интерпретатор Python (`libpython3.x.so`),
`libchaquopy_java.so`, стандартная библиотека и установленные pip-пакеты —
в `lib/<abi>/` и в `assets/chaquopy/`.

```
MIT License

Copyright (c) 2017-2025 Chaquo Ltd and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## 3. Python-пакеты (ставит Chaquopy при сборке)

Прямо заданы в `app/build.gradle.kts`, блок `chaquopy { defaultConfig { pip { … } } }`:
`httpx[http2]==0.28.1`, `cryptography==42.0.8`, `certifi`. Остальное — их
зависимости. Версии и лицензии ниже фактически разрешены pip на CPython 3.11;
при сборке под конкретный ABI Chaquopy может взять иные минорные версии — точный
состав виден в логе сборки.

| Пакет | Версия | Лицензия | Проект |
| --- | --- | --- | --- |
| httpx | 0.28.1 | BSD-3-Clause | https://github.com/encode/httpx |
| httpcore | 1.0.9 | BSD-3-Clause | https://github.com/encode/httpcore |
| h11 | 0.16.0 | MIT | https://github.com/python-hyper/h11 |
| h2 | 4.4.1 | MIT | https://github.com/python-hyper/h2 |
| hpack | 4.2.0 | MIT | https://github.com/python-hyper/hpack |
| hyperframe | 6.1.0 | MIT | https://github.com/python-hyper/hyperframe |
| anyio | 4.16.0 | MIT | https://github.com/agronholm/anyio |
| idna | 3.20 | BSD-3-Clause | https://github.com/kjd/idna |
| typing_extensions | 4.16.0 | PSF-2.0 | https://github.com/python/typing_extensions |
| cryptography | 42.0.8 | Apache-2.0 OR BSD-3-Clause | https://github.com/pyca/cryptography |
| cffi | 2.1.1 | MIT-0 | https://foss.heptapod.net/pypy/cffi |
| pycparser | 3.12 | BSD-3-Clause | https://github.com/eliben/pycparser |
| certifi | 2026.10.23 | MPL-2.0 | https://github.com/certifi/python-certifi |

Пояснения по двум пунктам:

* **certifi (MPL-2.0)** — это набор доверенных корневых сертификатов
  `cacert.pem`. MPL-2.0 — «файловый» копилефт: обязательство раскрыть исходники
  касается самого пакета certifi, они доступны по ссылке выше; на код приложения
  лицензия не распространяется.
* **cryptography** — единственный пакет с нативным кодом (OpenSSL + Rust).
  Chaquopy 17 ставит пакеты только готовыми wheels, собранными в репозитории
  Chaquopy, поэтому версия зафиксирована на 42.0.8 (для более новых нет wheels
  под Android для всех нужных версий Python).

Полные тексты BSD-3-Clause, MIT, Apache-2.0 и MPL-2.0 лежат в репозиториях
соответствующих пакетов; Apache-2.0 — также на
https://www.apache.org/licenses/LICENSE-2.0.

## 4. Android- и Kotlin-зависимости (тянет Gradle)

| Компонент | Версия в проекте | Лицензия |
| --- | --- | --- |
| Android Gradle Plugin | 8.13.0 | Apache-2.0 |
| Gradle и wrapper (`gradle/wrapper/gradle-wrapper.jar`) | 8.13 | Apache-2.0 |
| Kotlin, Compose-компилятор | 2.2.20 | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| AndroidX: core-ktx, activity-compose, lifecycle-runtime-ktx | 1.15.0, 1.9.3, 2.8.7 | Apache-2.0 |
| Jetpack Compose (BOM) | 2024.12.01 | Apache-2.0 |
| JUnit | 4.13.2 | EPL-1.0 (только `testImplementation`, в APK не попадает) |

Части AndroidX и Compose происходят из Android Open Source Project (Apache-2.0).

## 5. Значки и графика

* Кружок «СТАРТ», значок уведомления, фон иконки и плитка нарисованы в этом
  проекте (`app/src/main/res/drawable/`, `app/src/main/res/mipmap-anydpi*/`) —
  растровых файлов нет, иконки целиком описаны векторным XML.
* Контуры «обновить» (стрелка) и «бумажный самолётик» для плитки взяты из набора
  [Material Symbols](https://fonts.google.com/icons) — Apache-2.0, © Google.

## 6. Прочее

* Приложение не связано с Telegram Messenger Inc. и не является официальным
  клиентом Telegram.
* Адреса DC по умолчанию (`2:149.154.167.220`, `4:149.154.167.220`) — публичные
  серверы Telegram из конфигурации ядра; секрет прокси генерируется на устройстве
  и никуда не отправляется.
