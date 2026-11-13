from odoo import fields, models


class StockLocation(models.Model):
    _inherit = 'stock.location'

    atlas_dlc_quarantine = fields.Boolean(
        string='Atlas: DLC Quarantine Location',
        help='Allow expired lots to be transferred into this location for isolation only. '
             'This does not authorize picking them out again for normal use.',
    )
