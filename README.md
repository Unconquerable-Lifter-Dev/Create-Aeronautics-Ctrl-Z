# Create Aeronautics: Ctrl + Z (`aeronautics_ctrlz`)

Самостоятельный сервер-авторитетный мод для **Minecraft 1.21.1 (NeoForge 21.1.77, Java 21)**, реализующий атомарную систему **Undo / Redo** (`Ctrl+Z`, `Ctrl+Y`, `Ctrl+Shift+Z`) для действий игрока, каскадных реакций (TNT, поршни, жидкости), кинетических сетей и физических конструкций (**контрапшенов**) **Create** и **Create Aeronautics**.

---

## 1. Архитектурные решения

### 1.1. Дельта или снимок (Delta vs Snapshot)
В моде применён **гибридный подход**, строго разделяющий точечные изменения мира и графовые физические сущности:
1. **Для блоков, параметров кинетических BlockEntity и каскадов (TNT, поршни, вода/лава) — Дельта (`BlockDelta` + `CascadeEntry`)**:
   - Каждое изменение хранит только координаты `BlockPos`, `beforeState`/`afterState`, `beforeBeNbt`/`afterBeNbt` и `FluidState`.
   - При взрыве TNT или разливе жидкости на сотни блоков дельты занимают десятки килобайт вместо мегабайт полных чанк-снимков, обеспечивая нулевую просадку TPS при записи.
2. **Для физических конструкций (контрапшенов Create / Create Aeronautics) — Снимок с жёстким лимитом размера (`ContraptionSnapshot`, лимит `history.maxSnapshotSizeKb = 512`)**:
   - Контрапшен в Create и Create Aeronautics — это связанный под-мир (или сущность `AbstractContraptionEntity` / `PhysicsEntity` / `AirshipContraption`) с собственной локальной палитрой блоков, матрицей трансформации, линейным и угловым импульсом и контроллером (`MechanicalBearingBlockEntity`, штурвал, левитатор).
   - Восстановить распавшийся после поломки одного блока контрапшен по одной лишь дельте сломанного блока невозможно — требуется полное состояние графа конструкции, векторов скорости и NBT контроллера. Снимок ограничен `maxSnapshotSizeKb` (по умолчанию 512 КБ) для гарантированной защиты памяти сервера.

### 1.2. Обеспечение детерминизма при откате физики
При откате физического контрапшена в `ContraptionStateHandler` и `UndoEngine` выполняется 4-фазный детерминированный протокол:
1. **Заморозка симуляции (`freezeContraption`)**: UUID контрапшена добавляется в `FROZEN_CONTRAPTIONS`. Mixin `EntityMixin` блокирует выполнение `Entity#tick()` для этого контрапшена на всё время транзакции, а линейная и угловая скорости сбрасываются или фиксируются.
2. **Пакетное восстановление структуры и кинетической сети**: В серверном потоке с флагом `PERFORMING_UNDO_REDO = true` (блокирующим побочные срабатывания поршней, взрывов и рекурсивную запись истории) восстанавливаются все блоки конструкции, их BlockEntity NBT (включая `Speed`, `ScrollValue`, `Direction`) и контроллер на `anchorPos`.
3. **Пересборка и возобновление (`assemble` / `EntityType.loadEntityRecursive`)**: Если целевое состояние — собранный контрапшен (`assembled = true`), вызывается сборка контроллера или десериализуется сущность контрапшена с точным восстановлением координат, ориентации, векторов скорости и (опционально) пассажиров.
4. **Атомарная верификация и разморозка (`unfreezeContraption`)**: Проверяются пост-условия всех затронутых координат. При любом сбое `RollbackJournal` мгновенно возвращает мир в состояние до начала транзакции.

---

## 2. Установка и сборка

1. Требования: **JDK 21**, **Minecraft 1.21.1**, **NeoForge 21.1.77+**.
2. Сборка JAR:
   ```bash
   ./gradlew build
   ```
3. Запуск юнит-тестов и серверных GameTest-сценариев:
   ```bash
   ./gradlew test runGameTestServer
   ```
4. Поместите собранный файл `build/libs/aeronautics_ctrlz-1.0.0-neoforge-1.21.1.jar` в папку `mods/` как на сервере, так и на клиенте. Моды `create` и `create_aeronautics` объявлены в `neoforge.mods.toml` как `optional`: без них полностью работает базовый Undo/Redo блоков и каскадов.

---

## 3. Конфигурация (`aeronautics_ctrlz-server.toml`)

| Секция и ключ | По умолчанию | Описание |
| :--- | :--- | :--- |
| `history.maxActions` | `50` | Максимальная глубина стека Undo на каждого игрока |
| `history.ttlMinutes` | `60` | Время жизни записей истории в минутах |
| `history.maxSnapshotSizeKb` | `512` | Лимит размера одного действия / снимка контрапшена в КБ |
| `history.persistAcrossRestarts` | `false` | Сохранение стеков истории в `SavedData` между перезапусками сервера |
| `undo.enableUndo` / `enableRedo` | `true` | Глобальное включение отмены и повтора |
| `undo.cooldownMs` | `500` | Минимальный интервал между запросами отката от одного игрока (мс) |
| `cascade.recordExplosions` | `true` | Запись каскадов взрывов атрибутированного игроку TNT |
| `cascade.recordPistons` | `true` | Запись перемещений и разрушений блоков от поршней игрока |
| `cascade.recordFluids` | `true` | Запись растекания воды/лавы и смытых блоков от вылитого игроком ведра |
| `cascade.recordUnattributed` | `false` | Запись неатрибутированных разрушений (криперы, случайный огонь) |
| `createAeronautics.enabled` | `true` | Включение модуля восстановления контрапшенов и кинетики |
| `createAeronautics.restorePassengers` | `false` | Повторная посадка пассажиров при восстановлении контрапшена |
| `createAeronautics.restoreInventories` | `false` | Восстановление содержимого инвентарей BlockEntity |
| `createAeronautics.restoreScheduledTicks` | `true` | Восстановление запланированных тиков блоков и жидкостей |
| `permissions.allowAdminUndoOthers` | `true` | Разрешить администраторам (OP уровень >= 2) откатывать чужие действия |
| `blacklist.dimensions` | `[]` | Список измерений, где запись и откат отключены |
| `blacklist.blockEntities` | `["minecraft:command_block", "minecraft:structure_block"]` | Чёрный список типов BlockEntity |

---

## 4. Команды и горячие клавиши

- **`Ctrl + Z`** — Отменить последнее действие (не срабатывает в чате, инвентаре, книгах, табличках и любых экранах с текстовым вводом).
- **`Ctrl + Y`** или **`Ctrl + Shift + Z`** — Повторить отменённое действие (Redo).
- `/ctrlz history [player]` — Показать состояние стеков Undo/Redo и последние действия.
- `/ctrlz undo [player]` — Выполнить серверный откат (для другого игрока — с правами администратора).
- `/ctrlz redo [player]` — Выполнить серверный повтор.
- `/ctrlz clear [player]` — Очистить стек истории игрока.
- `/ctrlz debug` — Включить/выключить подробное логирование отказов валидации.

---

## 5. Места, требующие сверки с исходниками Create Aeronautics (Verification Checklist)

Поскольку Create Aeronautics активно развивается (используя собственное физическое ядро / Landlord / Gable / VS2-подобные суб-чанки в зависимости от релиза), мод использует универсальный путь через ванильные точки входа (`Entity`, `BlockEntity`, `CompoundTag`), а при подключении конкретной сборки Create Aeronautics рекомендуется сверить следующие 4 точки в `ContraptionStateHandler.java`:

1. **Имена ключей NBT физической сущности (`ContraptionStateHandler#extractAngularVelocity` и `extractBlocksFromContraptionNbt`)**:
   - В Create стандартный ключ вложенного тега структуры — `"Contraption"` -> `"Blocks"` (`"Palette"`, `"BlockList"`), а якоря — `"Anchor"`.
   - Если в целевой сборке Create Aeronautics воздушные суда хранят угловую скорость и тензор инерции в тегах `"PhysicsData"` / `"Omega"` / `"RigidBody"`, уточните чтение этих ключей в `extractAngularVelocity(CompoundTag)`.
2. **Имя метода принудительной сборки на контроллере (`ContraptionStateHandler#tryInvokeControllerAssemble`)**:
   - Для `MechanicalBearingBlockEntity` и `ClockworkBearingBlockEntity` в Create вызывается `assemble()`.
   - Для специфичных контроллеров Create Aeronautics (например, `LevitatorBlockEntity`, `OpticalSensor`, `HelmBlockEntity` / `AirshipAssembleController`) сверьте точное имя метода сборки в исходниках целевой версии.
3. **Префиксы ID сущностей в реестре (`ContraptionStateHandler#isContraptionEntity`)**:
   - Сейчас проверяются пространства имён `create` и `create_aeronautics` с подстроками `contraption`, `airship`, `physics`. Если физический корабль регистрируется под иным `EntityType` path, добавьте его в фильтр.
4. **Ключи инвентарей (`ActionRecorder#stripInventoryTagsIfConfigured`)**:
   - Сейчас при `restoreInventories = false` очищаются стандартные теги `"Items"`, `"Inventory"`, `"InputInventory"`, `"OutputInventory"`. Если специфичное хранилище Create Aeronautics использует собственный ключ NBT, добавьте его в список исключений.

---

## 6. Известные ограничения P0
- Откат требует, чтобы все затронутые действием и его каскадами чанки были загружены (`UndoRejectionReason.CHUNKS_UNLOADED`). Принудительная подгрузка выгруженных чанков не выполняется, чтобы исключить лаг-спайки сервера.
- При `restoreInventories = false` предметы, выпавшие из сундука при разрушении, не удаляются из мира и не дублируются внутри восстановленного сундука (защита от дюпа).
