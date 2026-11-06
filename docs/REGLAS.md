# Reglas portadas de la PC y sus vectores

Las reglas de la PC (TPVC, C#) que la app usa, portadas a Kotlin puro en `:domain`. Cada una se comprueba con los vectores de
`contracts/golden/` que genera la PC: la prueba evalúa **cada caso desde su `input`** (como la segunda comprobación de
`GoldenVectorTests` en la PC) y compara lo que da con el `expected` guardado. Una regla no está portada hasta que pasa todos los
casos de su archivo. Los vectores no se editan aquí: si uno parece mal, se corrige en TPVC.

`GoldenCoverageTest` lista `contracts/golden/*.json` y falla si un archivo no está portado ni en la lista de pendientes con su
paso, o si este documento no lo nombra: un vector nuevo de la PC no se puede ignorar sin que se note.

Paquetes: `io.github.marioalr.tpvca.domain` + `search`, `business`, `patients`, `agenda`, `documents`, `consultation`, `model` (datos) y `text`
(decimales y formatos de .NET).

## Portados en K1 (lo que necesita el celular)

### Búsqueda

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `text-search.json` | `TextSearch.normalize`, `matches`, `findBestMatchIndex` (K0) | 27/27 | ✅ |
| `rank-patients.json` | `PatientSearchRules.rank` | 17/17 | ✅ |
| `rank-products.json` | `TextSearch.rankProducts`; `CatalogSearchRules.parseQuantityPrefix`, `posCatalog`, `serviceSupplySuggestions`, `supplySuggestions`, `browserSupplies` | 36/36 | ✅ |
| `medicine-search.json` | `MedicineSearchRules.rank`, `matchTier`; `CatalogSearchRules.prescriptionSuggestions`, `browserMedications` | 16/16 | ✅ |
| `catalog-matching.json` | `CatalogMatchingRules.productMatchesName`, `findProductByName`, `isProductMedication`; `PrescriptionNaming.medicineLine`, `parts`, `withActiveIngredient` | 69/69 | ✅ |

### Negocio e inventario

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `alerts.json` | `StockAlertRules.lowStock`, `splitByExpiration`; `ProductBatch.statusTextOn`; `StockReviewRules.inventoryReview`, `alertLine`, `stockBadgeText`, `shortage`, `warningText`, `closingDeltas` | 19/19 | ✅ |
| `cash-cut.json` | `CashCutRules.compute`, `classifyPayment` | 5/5 | ✅ |
| `suggested-purchase.json` | `SuggestedPurchaseRules.build` (`SuggestedPurchaseItem.unitCostText`) | 1/1 | ✅ |
| `inventory-value.json` | `Product.effectiveUnitCost`, `inventoryValue`, `hasCost`; `InventoryValueRules.summarize`, `detail` | 8/8 | ✅ |
| `business-summary.json` | `BusinessReportRules.periodFor`, `previousPeriod`, `periodName`, `periodText`, `costToRecord`, `lines`, `summarize`, `compare`, `changeText`, `estimatedCostNote`, `timeline` | 57/57 | ✅ |
| `sales-by-type.json` | `BusinessReportRules.byType` | 4/4 | ✅ |
| `best-sellers.json` | `BusinessReportRules.topProducts`, `topServices` | 8/8 | ✅ |
| `purchases.json` | `BusinessStockRules.isSupplierOrder`, `supplierOf`, `purchases` | 14/14 | ✅ |
| `supply-consumption.json` | `BusinessStockRules.supplyConsumption` | 6/6 | ✅ |

### Pacientes y agenda

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `patient-summary.json` | `PatientSummaryRules.next`, `lastVisit`, `timeOfDay` | 27/27 | ✅ |
| `patient-identity.json` | `Patient.initials`, `age`, `ageDisplay`, `isFemale`, `isPediatric`, `showsGynecoObstetric`; `PatientIdentity.totalMonths`, `formatPhoneNumber`; desde K6 `PatientFormRules.parseFullName`, `composeFullName`, `tryParseBirthDate`, `isValidEmail`, `validate` | 82/82 | ✅ |
| `chart-antecedents.json` | `PatientChartRules.buildAntecedentSections` | 7/7 | ✅ |
| `chart-timeline.json` | `PatientChartRules.buildTimeline`, `filterTimeline`, `firstLine` | 9/9 | ✅ |
| `latest-vitals.json` | `PatientChartRules.buildLatestVitals` | 7/7 | ✅ |
| `vitals-evolution.json` | `VitalSignsRules.parse`, `ensureNumeric`, `evolution` (`EvolutionPoint` y sus textos); `PatientChartRules.latestTexts` | 6/6 | ✅ |
| `vitals.json` | `VitalSignsRules.bmiText`, `bmiCategory`, `warning`, `format`, `heightInMeters` | 48/48 | ✅ |
| `allergy-check.json` | `AllergyRules.checkMedication` (con el catálogo para las marcas); `Patient.hasAllergies`, `allergySummary`, `allergyBadgeText`, `hasChronicDiseases`, `chronicBadgeText` | 48/48 | ✅ |
| `agenda.json` | `AgendaRules.buildSlots`, `daySummary`, `week`, `dayCountText`, `todayAppointments`, `nextAppointment`, `overlapQuestion`, `panelTitle`, `weekTitle`, `startOfWeek` | 14/14 | ✅ |
| `appointment-overlap.json` | `AppointmentRules.overlaps`, `start`, `minutes`, `end`, `pairs`, `overlapsWith`, `timeText` | 31/31 | ✅ |

### Documentos (el modelo y el texto; el PDF llega con K4)

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `prescription-sheet.json` | `PrescriptionSheet.create` | 8/8 | ✅ |
| `prescription-html-fields.json` | `PrescriptionLayout.of`: qué valor va en cada lugar de la hoja (los `data-field` del HTML de la PC), el papel, la alerta de alergias y los iconos del pie | 4/4 | ✅ |
| `ticket-text.json` | `TicketText.lines`, `build`, `wrap` | 4/4 | ✅ |

## Portados en K2 (en `:sync`, donde se usan)

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `values.json` | `ValueCodec.encode`, `decode`; `HranaValue.fromJson` | 50/50 | ✅ |
| `stamping.json` | `SyncRules.nextLocalStamp` | 8/8 | ✅ |
| `lww.json` | `SyncRules.remoteRowWins`, `remoteTombstoneDeletes`, `afterRejectedUpsert`, `serverRowAppliesAfterReject` (17 casos) y, desde K5, los 16 de `server-upsert` y `server-tombstone`: `Statements.upsertSql`, `TOMBSTONE_UPSERT` y `deleteTombstonedSql` ejecutados de verdad en SQLite (`SqlCloud`). `SyncRulesGoldenTest`. |

Pruebas: `ValuesGoldenTest` y `SyncRulesGoldenTest`. Cómo se usan al bajar: [`SINCRONIZACION.md`](SINCRONIZACION.md).

## Portados en K3 (en `:sync`)

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `link-qr.json` | `LinkCode.encode`, `decode` | 7/7 | ✅ |

Prueba: `LinkCodeGoldenTest`. Cómo se usa: [`SINCRONIZACION.md`](SINCRONIZACION.md).

## Portados en K4

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `lab-files.json` | `LabFileRules.chunks`, `chunkCount`, `sha256`, `STATE_SQL`, `READ_CHUNKS_SQL` y los topes de bajar; `LabFileDownloader` (en `:sync`) | 6/6 (de `sql`, lo de bajar: `state` y `readChunks`) | 🟡 Lo de bajar. Subir es de la tablet: **K7** |

Prueba: `LabFilesGoldenTest`. Lo demás de K4 no trae vector nuevo: arma lo portado para las pantallas, con pruebas propias (`BusinessPanelTest`):

- `BusinessCsv` (`BusinessReportRules.ToCsv` de la PC, que no está en un vector) y `BusinessPanel` (`BusinessViewModel.Recompute`).
- `InventoryListRules`, `MovementRules` y `HomeAgendaRules`: cómo se combinan `alerts.json`, `agenda.json` y `appointment-overlap.json` en cada pantalla, y el signo del Kardex con la `note` de `InventoryMovements.Delta`.
- `PrescriptionSheet.indicationsOf`, `doctorFontPx` (`ConsultationDetailViewModel.Indications`, `LetterheadHtml.DoctorFontPx`).

## Portados en K6

Lo que no trae vector arma lo portado para la tablet, con pruebas propias (`PatientFormTest`, `TabletWritesTest`, `TabletAppTest`, `TabletDeviceTest`):

- `PatientForm` (`PatientFormViewModel`): secciones, qué se pide según sexo y edad, barra de avance, alta desde la búsqueda. `DuplicateQuestion` y `AgendaTexts`: los textos de la PC.
- `PatientFormFields` (`ApplyFormTo`, `FormBaselineFor`, `PatientFields.ToWrite`): **una edición escribe solo lo que el médico cambió**.
- `SyncNotices.merged`, `load`, `dismiss` (`Services/SyncNotices.cs`): los avisos con su depuración y lo descartado.

## Portados en K7 (las reglas de la consulta, sin pantallas ni base)

En `:domain`, paquete `consultation`; lo que usa los renglones de las hojas o se serializa, en `:sync`, paquete `rules.consultation`.

| Vector | Kotlin | Casos | Estado |
|---|---|---|---|
| `cie10-catalog.json` | `Cie10Catalog.all` (`Cie10Diagnosis.displayText`) | 1/1 | ✅ |
| `cie10-search.json` | `Cie10Catalog.search`; `CatalogSearchRules.cie10Suggestions` | 12/12 | ✅ |
| `pediatric-dose.json` | `PediatricDoseRule`; `PediatricDoseRules.suggestWeight`, `resultText`, `dosageText` | 16/16 | ✅ |
| `checkout.json` | `CheckoutRules`; `CheckoutCart` (renglones, avisos, recibido, cambio, botón) | 41/41 | ✅ |
| `consultation-account.json` | `ConsultationAccountRules`; `AccountLines.add`, `remove` (en `:sync`) | 30/30 | ✅ |
| `synced-json-columns.json` | `SheetRows`, `ServiceRowSupplies`, sobre `PatientSheets` (en `:sync`) | 63/63 | ✅ |
| `sheet-charges.json` | `ConsultationAccount` (en `:sync`) | 13/13 | ✅ |

Pruebas: `ConsultationGoldenTest` (`:domain`) y `ConsultationRulesGoldenTest` (`:sync`). Lo que no trae vector, con pruebas propias: `CheckoutCartTest`, `ConsultationNoteRulesTest` (`ConsultationNoteRules`: T/A, chips, los valores por omisión de la nota, el reloj; `ConsultationRecordRules`: «Repetir» la receta anterior) y `ConsultationAccountTest`.

Cómo difiere de la PC:

- **Sin Id locales.** Un renglón de la cuenta (`AccountLine`) se reconoce por `LineKind`, `CatalogSyncId` e `IsIndividualUnits`; un cargo libre, por el Id que sale de su nombre (`ConsultationAccountRules.freeChargeId`, igual en todos los equipos), que `AccountLine` no guarda. `LineRef` es el `SupplyLine` de la PC. `ChargedLineId` y `ServiceId` no se escriben en un renglón nuevo y uno que los trae los conserva; de ellos solo cuentan un `ChargedLineId` negativo (cargo libre) y un `ServiceId` en 0 sin `ServiceSyncId` (fuera del catálogo).
- **`pcOnly` y lo que depende de un Id local**: `AccountLegacySupplyLine`, `AccountLegacyServiceId`, el paso «Id viejo con el mismo nombre» y `TranslateReceivedRows` no se portan: un renglón viejo se busca por nombre. El único caso `pcOnly` (`synced-json-columns.json`) se corre igual y da lo mismo por nombre. En los casos `escribir` de ese vector, `input` no trae las instrucciones, las notas, la categoría ni el precio: la prueba los toma de `expected` y compara la forma canónica, sin los Id locales.
- **Los renglones son valores.** En la PC se cambian en su lugar; aquí cada acción regresa el renglón como quedó. Un texto que llegó como JSON null se lee vacío.
- **`CheckoutCart` no registra la venta** (`ChargeAsync`): `chargeStep` dice qué sigue. No trae `RequiresRefrigeration`, que `Product` no tiene aquí.
- **`appendLine`** usa "\n"; la PC, `Environment.NewLine` ("\r\n" en Windows).
- **`Product.syncId`, `ClinicalService.syncId`, `estimatedDurationMinutes` y `defaultProducts`** son nuevos, con valor por omisión: `:data` los debe llenar cuando una pantalla use estas reglas.

`VitalSignsRules.format`, `bmiText` y `warning`, `AllergyRules`, `PrescriptionNaming` y `MedicineSearchRules` ya cubrían lo que llaman estas pantallas; a `CatalogSearchRules` le faltaba `cie10Suggestions`.

## Pendientes (con el paso que los porta)

| Vector | Paso | Qué es |
|---|---|---|
| `uuid5.json` | K5, hecho | `rules/SyncIds`: UUIDv5, los SyncId deterministas de devoluciones y cierres. `WriteRulesGoldenTest`. |
| `canonical-json.json` | K5, hecho | `rules/CanonicalJson` y `rules/PatientSheets`: la forma canónica. `WriteRulesGoldenTest`. |
| `field-stamps.json` | K5, hecho | `rules/PatientFieldStamps`: las marcas por campo. `WriteRulesGoldenTest`. |
| `patient-merge.json` | K5, hecho | `rules/PatientMerge`: la combinación por campo y por renglón. `WriteRulesGoldenTest`. |
| `duplicate-patients.json` | K5, hecho | `DuplicatePatientRules`: el aviso de posibles duplicados después de bajar (`SyncNotices`). `PatientsGoldenTest`. |
| `folios.json` | K5, hecho | `rules/FolioFactory`: los folios con y sin conexión. `WriteRulesGoldenTest`. |
| `date-mask.json` | K6, hecho | `DateMaskText`: el campo de fecha dd/mm/aaaa, con su cursor (31/31). `PatientsGoldenTest`. `applyChange` lo convierte desde lo que entrega el teclado de Android. |
| `handoff-payload.json` | K8 | La consulta entre equipos: la copia que viaja |
| `handoff-accept.json` | K8 | La consulta entre equipos: aceptar la copia |
| `linked-device.json` | K8 | A quién se le ofrece «Enviar a…» |
| `duplicate-catalog.json` | K9b | Productos y servicios duplicados entre equipos |

## Cómo se repite .NET en Kotlin

- **Decimales** (`text/NetDecimal`): `BigDecimal` con la escala de `decimal` ("120.50" no es "120.5"). Suma, resta y producto
  dan la misma escala que .NET; la división (`NetDecimal.divide`) da el cociente exacto con la escala que prefiere .NET o 28
  dígitos al par si no termina. `Math.Round(x, n)` es `NetDecimal.round`, que nunca agrega ceros: **`HALF_EVEN`** donde la PC usa
  el redondeo por omisión (`ToEven`: margen, participación, cambio, ticket promedio, IMC de la nota, costo estimado con el
  precio) y **`HALF_UP`** donde usa `AwayFromZero` (el IVA del costo, el costo por pieza suelta). Los formatos `F2`, `0.0`,
  `0.#`, `0` y `N2` redondean el punto medio hacia arriba (`HALF_UP`), como .NET al formatear un `decimal`.
- **double** (el aviso de signos vitales): se lee como `Double` y se escribe desde su texto más corto (`Double.toString`) con el
  punto medio hacia arriba, como .NET ("38.25" → "38.3", "69.5" → "70").
- **Cultura es-MX**: los importes "$1,234.56" (`MexicanText.currency`), "N2" "3,803.53" y "F2" "1234.50"; los días y meses
  con el `Locale` es-MX (`agenda.json`: "Lunes 12 de octubre"). Los textos con meses o días abreviados (`d MMM`, `ddd`) no van
  en los vectores: dependen de los datos de la cultura de cada sistema.
- **Orden**: donde la PC compara con la cultura (`StringComparer.CurrentCulture`, `StringComparer.Create(es-MX)`, el `OrderBy`
  de texto sin comparador) se usa `java.text.Collator` de es-MX (`MexicanText.nameOrder`); donde compara ordinal
  (`MedicineSearchRules`, el desempate por SyncId, `StockReviewRules`), el `compareTo` de `String` (por unidad UTF-16, igual
  que `Ordinal`).
- **Hoy**: ninguna regla lee el reloj; reciben el día o la hora del consultorio. `Clinic.today(instante)` da el día en
  America/Mexico_City (o UTC−6 si el equipo no trae esa zona).
- **Expresiones regulares**: con `NetRegex.of` para que `\w`, `\d`, `\s` y `\b` sean de Unicode como en .NET: en el JDK agrega `(?U)` (y no cambian entre el JDK 17 de la CI y
  uno más nuevo, que cambió `\b`); en Android no, porque ICU ya es Unicode y **rechaza `(?U)` al compilar**. Nunca se escribe `(?U)` a mano: las pruebas de la JVM no lo notan
  (`DomainRegexDeviceTest`, en un equipo).

## Diferencias que hubo que resolver

1. **El `Collator` del JDK ignora los espacios y los guiones**; ICU (el de .NET) no: los ordena antes que la puntuación, los
   dígitos y las letras. Con el del JDK, "Para-Dol" quedaba después de "Paracetamol" (8 casos de `rank-products.json` fallaban)
   y "a b" después de "aa". `MexicanText` pasa esas reglas de ignorables a las primeras de la tabla (espacios, después
   guiones, después lo de siempre). En Android `java.text.Collator` ya es ICU: se detecta y se usa tal cual. El orden entre
   signos de puntuación del JDK no es exactamente el de ICU (por ejemplo `_` y `-`); ningún vector depende de él.
2. **`TextInfo.ToTitleCase`** (la marca en mayúsculas, "VALCLAN" → "Valclan") no existe en Java: `MexicanText.titleCase` repite
   la regla de .NET (una palabra sigue hasta un separador; los dígitos son parte de ella).
3. **El prefijo de cantidad** (`3*para`): en .NET `.` es todo menos `\n` y `$` acepta un `\n` final; en Java se escribe
   `(?dU)…([^\n]*)$`.
4. **Números de los signos con dígitos que no son ASCII** ("TA: ١٢٠/80"): .NET los encuentra con `\d` pero `decimal.TryParse`
   no los lee; Kotlin igual (coinciden y quedan sin dato).
5. **`NumberStyles.Any`**: Kotlin acepta espacios a los lados, signo, punto decimal y exponente; no los paréntesis, el símbolo
   de moneda invariante (¤) ni "Infinity"/"NaN" del `double`. Nadie los escribe en un signo vital.
6. **La división de `decimal`** llega a 29 dígitos en .NET cuando le caben; Kotlin usa 28. Solo cambia el último dígito, y
   ninguna regla lo usa sin redondear antes a 1 o 2 decimales.
7. **APIs de Java 9 o más nuevas** (`Duration.toHoursPart`…) no se usan: la app corre desde la API 26 de Android.

## Lo que la regla hace hoy (tal cual, sin corregir)

Se portó lo que dicen las notas de cada vector, también lo que parece raro: los miembros con acento de las familias de
fármacos de `AllergyRules` nunca coinciden (se compara sin acentos); "3 PM" y "7:5" no se leen y cuentan como las 10:00; un
renglón de venta sin la marca de piezas sueltas cuenta como paquetes; el orden de los empates de stock bajo es el de la lista;
un medicamento sin nombre no sale en la receta.
