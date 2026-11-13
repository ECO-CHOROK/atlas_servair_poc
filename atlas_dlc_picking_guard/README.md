# Atlas Servair — DLC Picking Guard v3 (Odoo 18 Enterprise)

## Scope
This addon complements, rather than replaces, standard Odoo Barcode expiry warnings. It enforces a server-side hard stop on completion of stock move lines involving expired lots. The standard Barcode red highlight and expiry confirmation wizard remain unchanged. Clicking **Continue** cannot complete a prohibited withdrawal if the operation reaches the standard `stock.move.line._action_done()` method.

## Rule
A lot is prohibited when `expiration_date` (DLC) in the operating user's timezone is **on or before today**. `use_date` (best-before/DDM) is not used. The module checks only move lines with a nonzero done quantity.

## Exceptions
- Incoming goods and returns originating outside internal stock: allowed, subject to existing quality rules.
- Transfer from internal stock to a designated internal quarantine location: allowed.
- Scrap and inventory-adjustment destination: allowed.
- Normal transfers from quarantine to usable stock, manufacturing consumption, deliveries: blocked.
- Lots with no expiration date: allowed; mandatory DLC entry is a separate requirement.

## Configuration
Inventory > Configuration > Locations > designated quarantine internal location > **Atlas: DLC Quarantine Location**. Do not mark a general storage location as quarantine.

## Barcode test plan
1. Scan expired lot in internal picking; expect standard red warning. Choose **Continue**; expect French blocking error and no completed move.
2. Choose **Continue without expired lots**; valid quantities should complete and expired ones remain unprocessed; inspect backorder.
3. Scan lot expiring today: blocked. Scan tomorrow's lot: allowed.
4. Scan expired lot to quarantine: allowed. Then try moving it out for normal production: blocked.
5. Scrap expired lot: allowed. Supplier receipt and customer return: allowed.
6. Test partial picking, backorders, serial/lot quantities, manufacturing consumption and normal backend validation.
7. Verify the existing Odoo expiry wizard and custom Atlas modules do not bypass `_action_done`.

## Important limitations
- The standard Barcode interface still permits selecting/scanning an expired lot and shows its existing warning; the hard stop is at final validation.
- No frontend JavaScript modifications are included.
- Odoo 18 Enterprise runtime and Atlas database integration tests have **not** been executed here. Install on staging first.
- Custom code writing stock quantities without normal move-line completion may bypass this guard.
- The inventory-adjustment exception uses destination `usage='inventory'`; validate that this is restricted to the intended operation types in the client's database.
