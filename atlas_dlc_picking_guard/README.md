# Atlas Servair — DLC Picking Guard (Odoo 18 Enterprise)

## Current implementation (v2)
Server-side validation on `stock.move.line._action_done()`, used by standard Odoo stock validation and normally reached through Barcode validation. On expiry day or after, withdrawals from internal locations are blocked with a French actionable error naming product, lot, DLC, source and destination.

### Exceptions
- Supplier receipts and customer returns coming from non-internal locations: permitted.
- Scrap destination (`scrap_location`) and inventory adjustment destination (`usage='inventory'`): permitted.
- Internal destination explicitly marked `Atlas: DLC Quarantine Location`: permitted for isolation.
- Normal transfers *out* of quarantine, deliveries, manufacturing consumption: blocked.
- No DLC: allowed (missing expiry enforcement is out of scope).

### Configure quarantine
Inventory > Configuration > Locations: choose an internal quarantine location and enable `Atlas: DLC Quarantine Location`. Only do this on genuinely segregated locations.

### Barcode behavior — IMPORTANT
This module depends on `stock_barcode`, and its server-side error will block final Barcode validation when it invokes standard stock completion. **It does not yet provide immediate scan-time feedback.** The exact Barcode frontend model and patch points must be checked against the client's installed Odoo 18 Enterprise build before adding JS code. Do not claim this ZIP provides instant scan rejection.

### Limits and tests
- Requires installation on a staging copy and testing with Atlas's custom modules and Zebra/PDA devices.
- Standard validation paths are protected, but custom integrations bypassing `_action_done` are not.
- Time zone uses operator context; for consistent multi-user behavior, align timezone policy.
- A lot with expiry datetime exactly at midnight may map to a prior calendar day in some time zones; agree date-only DLC policy before production.
- Check receipt, customer return, normal picking, production, delivery, scrap, quarantine in/out, inventory adjustment, Barcode, partial validation and backorders.
- Confirm that XML view ID `stock.view_location_form` is present on target version.
- No automatic lot substitution or FEFO rerouting.
