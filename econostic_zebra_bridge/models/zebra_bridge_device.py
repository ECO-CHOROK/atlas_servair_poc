import hashlib
import secrets
from datetime import timedelta

from odoo import _, api, fields, models
from odoo.exceptions import UserError

ONLINE_TIMEOUT_PARAM = "econostic_zebra_bridge.online_timeout"
DEFAULT_ONLINE_TIMEOUT = 45  # secondes sans appel du PDA avant de le considérer hors ligne


def _hash_token(token):
    return hashlib.sha256((token or "").encode()).hexdigest()


class ZebraBridgeDevice(models.Model):
    _name = "zebra.bridge.device"
    _description = "PDA pont d'impression Zebra"
    _order = "name"

    name = fields.Char("Nom du PDA", required=True, help="Ex. : TC26 Réception 1")
    active = fields.Boolean(default=True)
    company_id = fields.Many2one(
        "res.company", string="Société", required=True, default=lambda self: self.env.company
    )
    user_ids = fields.Many2many(
        "res.users",
        "zebra_bridge_device_user_rel",
        "device_id",
        "user_id",
        string="Opérateurs",
        help="Utilisateurs Odoo dont les validations impriment sur ce PDA. "
             "Si un opérateur est rattaché à plusieurs PDA, le dernier PDA actif est utilisé.",
    )
    token_hash = fields.Char(copy=False, readonly=True, index=True, groups="stock.group_stock_manager")
    token_hint = fields.Char("Jeton", copy=False, readonly=True)
    paired = fields.Boolean(compute="_compute_paired")
    poll_interval = fields.Integer(
        "Intervalle d'interrogation (s)", default=2,
        help="Fréquence à laquelle le PDA interroge Odoo pour récupérer les étiquettes.",
    )

    # Télémétrie remontée par l'application Android
    last_seen = fields.Datetime("Dernier contact", readonly=True)
    is_online = fields.Boolean("En ligne", compute="_compute_is_online")
    app_version = fields.Char("Version de l'application", readonly=True)
    android_model = fields.Char("Modèle du terminal", readonly=True)
    printer_name = fields.Char("Imprimante", readonly=True)
    printer_serial = fields.Char("N° de série imprimante", readonly=True)
    printer_address = fields.Char("Adresse imprimante", readonly=True, help="Adresse MAC Bluetooth ou IP.")
    printer_transport = fields.Selection(
        [("bluetooth", "Bluetooth"), ("tcp", "Wi-Fi (TCP 9100)")], string="Liaison", readonly=True
    )
    printer_connected = fields.Boolean("Imprimante joignable", readonly=True)
    printer_status = fields.Char("État imprimante", readonly=True)
    printer_battery = fields.Integer("Batterie imprimante (%)", readonly=True)

    job_ids = fields.One2many("zebra.print.job", "device_id", string="Travaux d'impression")
    pending_job_count = fields.Integer(compute="_compute_job_counts")
    error_job_count = fields.Integer(compute="_compute_job_counts")

    _sql_constraints = [
        ("poll_interval_positive", "CHECK(poll_interval >= 1)", "L'intervalle doit être d'au moins 1 seconde."),
    ]

    @api.model
    def _online_timeout(self):
        value = self.env["ir.config_parameter"].sudo().get_param(ONLINE_TIMEOUT_PARAM)
        try:
            return max(int(value), 5)
        except (TypeError, ValueError):
            return DEFAULT_ONLINE_TIMEOUT

    @api.depends("token_hint")
    def _compute_paired(self):
        for device in self:
            device.paired = bool(device.token_hint)

    @api.depends("last_seen")
    def _compute_is_online(self):
        limit = fields.Datetime.now() - timedelta(seconds=self._online_timeout())
        for device in self:
            device.is_online = bool(device.last_seen and device.last_seen >= limit)

    def _compute_job_counts(self):
        Job = self.env["zebra.print.job"]
        for device in self:
            device.pending_job_count = Job.search_count(
                [("device_id", "=", device.id), ("state", "in", ("pending", "sent"))]
            )
            device.error_job_count = Job.search_count(
                [("device_id", "=", device.id), ("state", "=", "error")]
            )

    # ------------------------------------------------------------------
    # Appairage
    # ------------------------------------------------------------------
    def _generate_token(self):
        self.ensure_one()
        token = secrets.token_urlsafe(32)
        self.sudo().write({"token_hash": _hash_token(token), "token_hint": "…" + token[-4:]})
        return token

    def action_pair(self):
        """Génère un nouveau jeton (l'ancien est révoqué) et affiche le QR code d'appairage."""
        self.ensure_one()
        if not self.env.user.has_group("stock.group_stock_manager"):
            raise UserError(_("Seul un responsable d'entrepôt peut appairer un PDA."))
        token = self._generate_token()
        wizard = self.env["zebra.bridge.pairing.wizard"].create({"device_id": self.id, "token": token})
        return {
            "type": "ir.actions.act_window",
            "name": _("Appairer %s", self.name),
            "res_model": "zebra.bridge.pairing.wizard",
            "res_id": wizard.id,
            "view_mode": "form",
            "target": "new",
        }

    def action_revoke(self):
        self.sudo().write({"token_hash": False, "token_hint": False})

    @api.model
    def _find_by_token(self, token):
        if not token or len(token) < 20:
            return self.browse()
        return self.sudo().search([("token_hash", "=", _hash_token(token)), ("active", "=", True)], limit=1)

    # ------------------------------------------------------------------
    # Routage des impressions
    # ------------------------------------------------------------------
    @api.model
    def _pick_for_user(self, user, company=None, online_only=True):
        domain = [("user_ids", "in", user.ids), ("token_hint", "!=", False)]
        if company:
            domain.append(("company_id", "=", company.id))
        if online_only:
            limit = fields.Datetime.now() - timedelta(seconds=self._online_timeout())
            domain.append(("last_seen", ">=", limit))
        return self.sudo().search(domain, order="last_seen desc nulls last", limit=1)

    def _register_heartbeat(self, payload):
        """Met à jour la télémétrie envoyée par l'application à chaque interrogation."""
        self.ensure_one()
        payload = payload or {}
        printer = payload.get("printer") or {}
        vals = {"last_seen": fields.Datetime.now()}
        for src, dst in (("app_version", "app_version"), ("model", "android_model")):
            if payload.get(src):
                vals[dst] = str(payload[src])[:64]
        if printer:
            transport = printer.get("transport")
            vals.update({
                "printer_name": (printer.get("name") or "")[:64] or False,
                "printer_serial": (printer.get("serial") or "")[:64] or False,
                "printer_address": (printer.get("address") or "")[:64] or False,
                "printer_transport": transport if transport in ("bluetooth", "tcp") else False,
                "printer_connected": bool(printer.get("connected")),
                "printer_status": (printer.get("status") or "")[:128] or False,
            })
            battery = printer.get("battery")
            if isinstance(battery, (int, float)) and 0 <= battery <= 100:
                vals["printer_battery"] = int(battery)
        self.sudo().write(vals)

    def action_view_jobs(self):
        self.ensure_one()
        action = self.env["ir.actions.act_window"]._for_xml_id("econostic_zebra_bridge.action_zebra_print_job")
        action["domain"] = [("device_id", "=", self.id)]
        action["context"] = {"default_device_id": self.id}
        return action

    def action_send_test_label(self):
        self.ensure_one()
        zpl = (
            "^XA^CI28^PW576^LL300\n"
            "^FO30,30^A0N,40,40^FDTest pont Zebra Odoo^FS\n"
            f"^FO30,90^A0N,28,28^FDPDA : {self.name.replace('^', ' ').replace('~', ' ')}^FS\n"
            f"^FO30,135^A0N,28,28^FD{fields.Datetime.to_string(fields.Datetime.context_timestamp(self, fields.Datetime.now()))}^FS\n"
            "^FO30,185^BY2^BCN,80,Y,N,N^FDODOO-ZEBRA^FS\n"
            "^XZ\n"
        )
        self.env["zebra.print.job"].sudo().create({
            "name": _("Étiquette de test"),
            "device_id": self.id,
            "user_id": self.env.user.id,
            "company_id": self.company_id.id,
            "zpl": zpl,
        })
        return {
            "type": "ir.actions.client",
            "tag": "display_notification",
            "params": {
                "title": _("Étiquette de test envoyée"),
                "message": _("Elle sera imprimée dès le prochain contact du PDA %s.", self.name),
                "type": "success",
                "sticky": False,
            },
        }
