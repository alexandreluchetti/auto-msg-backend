# Spreadsheet import

Until the panel edits business data, a tenant's menu, opening hours and delivery zones are loaded
from spreadsheets saved as CSV (UTF-8). The tools of the conversation engine read **only** this
data for prices, hours and fees (D-007, ADR-006).

## How to run it

Put the files in one folder and point `BOOTSTRAP_DATA_DIR` at it (with `BOOTSTRAP_TENANT_SLUG`
set). On every start, each file present replaces that data set of the bootstrap tenant; a missing
file leaves its data untouched. In a container, mount the folder (e.g. `./data:/data:ro`) and use
the path inside the container.

Each file is validated as a whole: if any line is wrong, the startup stops with the list of errors
(`line N: ...`) and nothing of that file is stored.

## Common rules

- Separator `;` or `,` (whichever the header uses; Excel in pt-BR saves with `;`). Fields with the
  separator, quotes or line breaks go between double quotes (`"Calabresa; cebola"`).
- Header names in lowercase as below; unknown or missing columns are errors. Rows with only empty
  fields are ignored.
- Amounts: `39,90`, `39.90` or `R$ 1.234,50`; zero or more, at most 2 decimals.
- Yes/no: `sim`/`não`, `yes`/`no`, `true`/`false`, `1`/`0`; empty means yes.

## `menu.csv`

| Column | Required | Meaning |
|---|---|---|
| `code` | yes | The business's own item code, unique. Re-imports update items by it, so their ids stay stable |
| `category` | yes | Category name; categories appear in the order they first show up |
| `name` | yes | Item name |
| `description` | no | Ingredients, size, notes |
| `price` | yes | Base price |
| `available` | no | `não` keeps the item in the menu as out of stock today |

Items missing from a new `menu.csv` are removed, and so are categories left empty.

## `options.csv` (with `menu.csv`)

| Column | Required | Meaning |
|---|---|---|
| `item_code` | yes | `code` of an item in `menu.csv` |
| `group` | yes | Option group, e.g. `Tamanho`, `Borda` |
| `name` | yes | Choice, e.g. `Grande` |
| `price_delta` | no | Added to the item price (empty = 0) |

Importing the menu always replaces every option: without `options.csv`, items have no options.

## `hours.csv`

| Column | Required | Meaning |
|---|---|---|
| `day` | yes | `mon`, `tue`, `wed`, `thu`, `fri`, `sat`, `sun` or `1` (Monday) to `7` (Sunday) |
| `opens` | yes | `18:00` |
| `closes` | yes | `23:00`; at or before `opens` means the next day (`18:00` → `02:00`) |

Several rows for the same day are several periods (lunch and dinner). Days without rows are closed.
Times are in the tenant's time zone (`tenant.timezone`, default `America/Sao_Paulo`). Holidays and
special dates are not supported yet.

## `zones.csv`

| Column | Required | Meaning |
|---|---|---|
| `neighborhood` | yes | Neighborhood served, unique ignoring case and accents |
| `fee` | yes | Delivery fee |

Customers' spelling is matched ignoring case and accents; close names are offered as suggestions,
never charged without confirmation.

## Example

```text
code;category;name;description;price;available
P01;Pizzas;Pizza Calabresa;Calabresa, cebola e azeitonas;39,90;sim
B01;Bebidas;Refrigerante Lata;350 ml;6,50;
```
