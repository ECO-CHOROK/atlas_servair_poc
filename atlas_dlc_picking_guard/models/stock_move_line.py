from odoo import _, fields, models
from odoo.exceptions import UserError


class StockMoveLine(models.Model):
    _inherit = 'stock.move.line'

    def _atlas_dlc_is_exempt(self):
        self.ensure_one()
        source, dest = self.location_id, self.location_dest_id
        # Receipts/returns originating outside internal stock are not withdrawals.
        if source.usage != 'internal':
            return True
        # Do not block the same-location bookkeeping case.
        if source == dest:
            return True
        # Only explicit scrap/inventory-adjustment destinations are exempt.
        # Do not exempt every inventory-usage location indiscriminately.
        if dest.scrap_location or dest.usage == 'inventory' and not dest.scrap_location:
            return True
        # Explicitly marked quarantine is an allowed destination, never an unrestricted bypass.
        if dest.usage == 'internal' and dest.atlas_dlc_quarantine:
            return True
        return False

    def _check_atlas_expired_source_lots(self):
        today = fields.Date.context_today(self)
        for line in self:
            # Zero-quantity lines are not being picked and must not block partial operations.
            if line.state in ('done', 'cancel') or not line.quantity or line._atlas_dlc_is_exempt():
                continue
            lot = line.lot_id
            if not lot or not lot.expiration_date:
                continue
            expiry_day = fields.Datetime.context_timestamp(line, lot.expiration_date).date()
            if expiry_day <= today:
                raise UserError(_(
                    'DLC ATTEINTE — PRÉLÈVEMENT REFUSÉ\n\n'
                    'Produit : %(product)s\nLot : %(lot)s\nDLC : %(dlc)s\n'
                    'Emplacement source : %(source)s\nDestination : %(destination)s\n\n'
                    'Ce lot ne peut plus être prélevé pour la production, la livraison '
                    'ou un stockage utilisable. Choisissez un autre lot valide. '
                    'Pour isoler ce lot, effectuez un transfert vers un emplacement '
                    'explicitement configuré comme quarantaine DLC, ou procédez à sa mise au rebut.'
                ) % {
                    'product': line.product_id.display_name,
                    'lot': lot.name,
                    'dlc': fields.Date.to_string(expiry_day),
                    'source': line.location_id.display_name,
                    'destination': line.location_dest_id.display_name,
                })

    def _action_done(self):
        self._check_atlas_expired_source_lots()
        return super()._action_done()
