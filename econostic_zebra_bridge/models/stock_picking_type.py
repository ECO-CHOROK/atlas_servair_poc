from odoo import fields, models


class StockPickingType(models.Model):
    _inherit = "stock.picking.type"

    zebra_bridge_enabled = fields.Boolean(
        "Imprimer via le pont mobile Zebra",
        help="Les étiquettes ZPL cochées dans « Imprimer à la validation » (et l'étiquette colis "
             "au format ZPL) sont envoyées au PDA Android de l'opérateur, qui les imprime "
             "directement sur l'imprimante Zebra appairée. Les documents PDF restent inchangés.",
    )
    zebra_bridge_fallback = fields.Selection(
        [
            ("queue", "Mettre en file d'attente jusqu'au retour du PDA"),
            ("browser", "Laisser l'impression standard d'Odoo (téléchargement ou IoT)"),
        ],
        string="Si aucun PDA n'est en ligne",
        default="queue",
        required=True,
    )
    zebra_lot_report_id = fields.Many2one(
        "ir.actions.report",
        string="Gabarit ZPL des étiquettes lots",
        domain=[("model", "=", "stock.lot"), ("report_type", "=", "qweb-text")],
        help="Remplace l'étiquette lot ZPL standard d'Odoo lorsque l'impression passe par le pont. "
             "Laisser vide pour conserver le gabarit standard.",
    )
    zebra_copies = fields.Integer("Exemplaires par étiquette", default=1)
