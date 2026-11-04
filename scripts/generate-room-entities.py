#!/usr/bin/env python3
"""Genera las entidades de Room de :data desde contracts/sync/schema.json (el manifiesto de la PC).

    scripts/generate-room-entities.py

Se corre al traer una revisión con columnas nuevas. RoomSchemaContractTest compara las tablas reales de Room contra el
manifiesto y falla si alguien olvida correrlo.
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = json.loads((ROOT / "contracts/sync/schema.json").read_text(encoding="utf-8"))
OUT = ROOT / "data/src/main/kotlin/io/github/marioalr/tpvca/data/entity/SyncedEntities.kt"
TYPES = {"TEXT": "String", "INTEGER": "Long", "REAL": "Double", "BLOB": "ByteArray"}
# Solo de esta base (no están en el manifiesto ni en la nube): lo que necesitan las listas de las pantallas del celular (K4)
SCREEN_INDICES = {
    "Patients": [["LastVisit"]],
    "Appointments": [["Date"]],
    "PatientConsultations": [["PatientRecordSyncId", "Date"]],
    "Sales": [["Date"]],
    "InventoryMovements": [["Date"], ["ProductSyncId", "Date"]],
}


def prop(name):
    """UpdatedAtUtc -> updatedAtUtc, BMI -> bmi."""
    head = re.match(r"^[A-Z]+(?=[A-Z][a-z]|$)|^[A-Z]", name).group(0)
    return head.lower() + name[len(head):]


def field(column, primary=False):
    kind = TYPES[column["remoteType"]]
    nullable = column["nullable"]
    annotations = []
    if primary:
        annotations.append("@PrimaryKey")
    annotations.append(f'@ColumnInfo(name = "{column["name"]}")')
    return f'    {" ".join(annotations)} val {prop(column["name"])}: {kind}{"?" if nullable else ""},'


lines = [
    "// GENERADO por scripts/generate-room-entities.py desde contracts/sync/schema.json. No se edita a mano.",
    "package io.github.marioalr.tpvca.data.entity",
    "",
    "import androidx.room.ColumnInfo",
    "import androidx.room.Entity",
    "import androidx.room.Index",
    "import androidx.room.PrimaryKey",
    "",
    f'/** La revisión del contrato de la que salieron estas entidades. */',
    f'const val ENTITIES_CONTRACT_REVISION: Int = {SCHEMA["contract"]}',
]
names = []
for table in SCHEMA["tables"]:
    name = table["entityType"] + "Entity"
    names.append(name)
    # Sin llaves foráneas; un índice en cada referencia para los borrados en cascada que hace el motor
    indices = "".join(f'Index("{r["column"]}"), ' for r in table["references"])
    indices += "".join("Index(" + ", ".join(f'"{c}"' for c in columns) + "), " for columns in SCREEN_INDICES.get(table["table"], []))
    lines += ["", f'@Entity(tableName = "{table["table"]}", indices = [{indices.rstrip(", ")}])', f"data class {name}("]
    for i, column in enumerate(table["columns"]):
        lines.append(field(column, primary=i == 0))
    lines.append(")")
lines += ["", "/** Las 14 tablas sincronizadas. */", "val SYNCED_ENTITIES = listOf(", *[f"    {n}::class," for n in names], ")", ""]
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(lines), encoding="utf-8")
print(f"{OUT.relative_to(ROOT)}: {len(names)} entidades, revisión {SCHEMA['contract']}")
