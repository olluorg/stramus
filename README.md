# stramus

Менеджер вкладок в духе Toby: сохраняет открытые вкладки в коллекции, разложенные по секциям.
Работает как отдельное веб-приложение и как расширение для Chrome, подменяющее страницу новой вкладки.

Написан на Kotlin/JS (React через kotlin-wrappers). Данные лежат в SQLite, который крутится в браузере
поверх IndexedDB — через [Kormium](https://github.com/olluorg/korm) и его движок `kormium-sqlite-js`.
Никакого сервера и никаких аккаунтов: всё хранится локально в браузере.

## Что нужно для сборки

- **JDK 21** (в CI — Temurin 21).
- Больше ничего ставить не надо: Gradle приезжает через wrapper (`./gradlew`), а Node и Yarn
  Kotlin/JS-плагин скачивает себе сам при первой сборке.

## Веб-приложение

Дев-сервер с горячей пересборкой:

```bash
./gradlew :webapp:jsBrowserDevelopmentRun --continuous
```

Приложение поднимется на <http://localhost:8080> и будет пересобираться при каждом сохранении файла.
Без `--continuous` сервер тоже запустится, но правки подхватываться не будут.

Продакшн-бандл:

```bash
./gradlew :webapp:jsBrowserDistribution
```

Результат — статика в `webapp/build/dist/js/productionExecutable/`, её можно отдавать любым
статическим сервером. Именно эта папка уезжает на GitHub Pages.

## Расширение для Chrome

```bash
./gradlew :extension:jsBrowserDistribution
```

Дальше в браузере: `chrome://extensions` → включить **Режим разработчика** → **Загрузить распакованное
расширение** → указать папку

```
extension/build/dist/js/productionExecutable/
```

В ней уже лежит `manifest.json` (MV3). После установки расширение занимает страницу новой вкладки —
открывайте новую вкладку и увидите stramus.

После пересборки расширение нужно обновить кнопкой ↻ на карточке расширения в `chrome://extensions`.
Дев-сборка (`:extension:jsBrowserDevelopmentExecutableDistribution`, папка `developmentExecutable/`)
тоже загружается как распакованная — webpack в этом модуле специально настроен на `source-map`
вместо `eval`, потому что CSP у MV3 запрещает `unsafe-eval`.

Расширение просит разрешения `tabs` (сохранить открытые вкладки) и `history` (импорт из истории
браузера). В веб-версии этих возможностей нет — там доступно ручное добавление ссылок.

## Модули

| Модуль | Что внутри |
| --- | --- |
| `core` | Модели, схема БД, репозитории поверх Kormium, PIN-хеши |
| `ui-shared` | Весь React-UI: он общий для веба и расширения |
| `webapp` | `main()` для веб-версии + `index.html` со стилями |
| `extension` | `main()` для расширения, `manifest.json`, обёртки над Chrome API |

Разные точки входа держат разные реализации `TabCapture` и `HistoryAccess` из `core`: в расширении
они ходят в Chrome API, в вебе — заглушки.

## Проверка сборки

Отдельных тестов пока нет; то же, что гоняет CI:

```bash
./gradlew :webapp:jsBrowserDistribution :extension:jsBrowserDistribution
```

## Kormium из соседнего чекаута

Если рядом с репозиторием лежит папка `../korm`, она подключается как composite build, и правки в
Kormium подхватываются сразу, без публикации артефактов. Если её нет (как в CI или в свежем клоне) —
берётся опубликованный `io.github.kormium:*:0.11.0` с Maven Central. Специально делать ничего не надо,
условие живёт в `settings.gradle.kts`.

## CI/CD

- **CI** (`ci.yml`) — собирает оба бандла на каждый PR и push в `main`.
- **Pages** (`pages.yml`) — деплоит веб-версию на <https://olluorg.github.io/stramus/> при push в `main`.
- **Release** (`release.yml`) — по тегу собирает ZIP с расширением и вешает его на GitHub Release:

  ```bash
  git tag v0.1.0 && git push origin v0.1.0
  ```
