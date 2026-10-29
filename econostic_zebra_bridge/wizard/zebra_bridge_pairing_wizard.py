import base64
import json

from odoo import api, fields, models


class ZebraBridgePairingWizard(models.TransientModel):
    _name = "zebra.bridge.pairing.wizard"
    _description = "Appairage d'un PDA pont Zebra"

    device_id = fields.Many2one("zebra.bridge.device", required=True, ondelete="cascade")
    token = fields.Char(readonly=True)
    server_url = fields.Char("Adresse Odoo", compute="_compute_payload")
    payload = fields.Char("Code d'appairage", compute="_compute_payload")
    qr_image = fields.Binary("QR code", compute="_compute_payload")

    @api.depends("token", "device_id")
    def _compute_payload(self):
        base_url = self.env["ir.config_parameter"].sudo().get_param("web.base.url", "").rstrip("/")
        for wizard in self:
            payload = json.dumps(
                {"v": 1, "url": base_url, "token": wizard.token, "name": wizard.device_id.name},
                separators=(",", ":"), ensure_ascii=False,
            )
            wizard.server_url = base_url
            wizard.payload = payload
            try:
                png = self.env["ir.actions.report"].barcode("QR", payload, width=360, height=360, quiet=True)
                wizard.qr_image = base64.b64encode(png)
            except Exception:
                # rendu du QR indisponible sur ce serveur : le code reste copiable en texte
                wizard.qr_image = False
