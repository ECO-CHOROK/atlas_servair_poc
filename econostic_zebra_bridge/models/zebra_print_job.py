import logging
from datetime import timedelta

from odoo import _, api, fields, models

_logger = logging.getLogger(__name__)

PENDING_TTL_PARAM = "econostic_zebra_bridge.pending_ttl_minutes"
SENT_TIMEOUT_PARAM = "econostic_zebra_bridge.sent_timeout_minutes"
RETENTION_PARAM = "econostic_zebra_bridge.retention_days"


class ZebraPrintJob(models.Model):
    _name = "zebra.print.job"
    _description = "Travail d'impression Zebra"
    _order = "id desc"

    name = fields.Char("Libellé", required=True)
    state = fields.Selection(
        [
            ("pending", "En attente"),
            ("sent", "Transmis au PDA"),
            ("done", "Imprimé"),
            ("error", "Erreur"),
            ("cancel", "Annulé"),
        ],
        default="pending", required=True, index=True, string="État",
    )
    device_id = fields.Many2one("zebra.bridge.device", string="PDA", index=True, ondelete="set null")
    user_id = fields.Many2one(
        "res.users", string="Opérateur", required=True, index=True, default=lambda self: self.env.user
    )
    company_id = fields.Many2one(
        "res.company", string="Société", required=True, default=lambda self: self.env.company
    )
    picking_ids = fields.Many2many(
        "stock.picking", "zebra_print_job_picking_rel", "job_id", "picking_id", string="Transferts"
    )
    report_id = fields.Many2one("ir.actions.report", string="Rapport", ondelete="set null")
    zpl = fields.Text("Contenu ZPL", required=True)
    copies = fields.Integer("Exemplaires", default=1)
    label_count = fields.Integer("Étiquettes", compute="_compute_label_count", store=True)
    queued_at = fields.Datetime("Mis en file le", default=fields.Datetime.now, readonly=True, index=True)
    attempt_count = fields.Integer("Transmissions", readonly=True)
    date_sent = fields.Datetime("Transmis le", readonly=True)
    date_done = fields.Datetime("Terminé le", readonly=True)
    error_message = fields.Char("Message", readonly=True)

    @api.depends("zpl", "copies")
    def _compute_label_count(self):
        for job in self:
            job.label_count = (job.zpl or "").upper().count("^XA") * max(job.copies, 1)

    # ------------------------------------------------------------------
    # Création depuis une action de rapport Odoo
    # ------------------------------------------------------------------
    @api.model
    def _is_zpl_action(self, action):
        return (
            isinstance(action, dict)
            and action.get("type") == "ir.actions.report"
            and action.get("report_type") == "qweb-text"
        )

    @api.model
    def _render_action(self, action, picking_type=None):
        """Rend une action ir.actions.report de type qweb-text et retourne (rapport, texte ZPL)."""
        Report = self.env["ir.actions.report"]
        report = Report._get_report(action["report_name"])
        context = dict(action.get("context") or {})
        data = dict(action.get("data") or {})
        docids = context.get("active_ids") or data.get("docids") or []
        if isinstance(docids, str):
            docids = [int(x) for x in docids.split(",") if x]

        if (
            picking_type
            and picking_type.zebra_lot_report_id
            and report.report_name == "stock.label_lot_template_view"
        ):
            report = picking_type.zebra_lot_report_id.sudo()
            data = {}

        render_ctx = {k: v for k, v in context.items() if k not in ("active_ids", "active_id", "active_model")}
        content, _fmt = Report.with_context(render_ctx)._render_qweb_text(report, docids, data=data or None)
        if isinstance(content, bytes):
            content = content.decode("utf-8")
        return report, str(content or "")

    @api.model
    def _create_from_report_action(self, action, pickings, device=None):
        picking_type = pickings.picking_type_id[:1]
        report, zpl = self._render_action(action, picking_type)
        if "^XA" not in zpl.upper():
            return self.browse()
        name = "%s : %s" % (report.name, ", ".join(pickings.mapped("name"))[:120])
        return self.sudo().create({
            "name": name,
            "device_id": device.id if device else False,
            "user_id": self.env.user.id,
            "company_id": (pickings.company_id[:1] or self.env.company).id,
            "picking_ids": [(6, 0, pickings.ids)],
            "report_id": report.id,
            "zpl": zpl,
            "copies": max(picking_type.zebra_copies or 1, 1) if picking_type else 1,
        })

    # ------------------------------------------------------------------
    # Échanges avec le PDA
    # ------------------------------------------------------------------
    @api.model
    def _fetch_for_device(self, device, limit=20):
        """Réserve les travaux en attente pour ce PDA (verrou SKIP LOCKED : jamais deux fois le même)."""
        ttl = self._param_minutes(PENDING_TTL_PARAM, 15)
        since = fields.Datetime.now() - timedelta(minutes=ttl)
        user_ids = device.with_context(active_test=False).user_ids.ids or [0]
        self.env["zebra.print.job"].flush_model()
        self.env.cr.execute(
            """
            SELECT id FROM zebra_print_job
             WHERE state = 'pending'
               AND company_id = %s
               AND queued_at >= %s
               AND (device_id = %s OR (device_id IS NULL AND user_id = ANY(%s)))
             ORDER BY id
             LIMIT %s
             FOR UPDATE SKIP LOCKED
            """,
            (device.company_id.id, since, device.id, user_ids, limit),
        )
        jobs = self.sudo().browse([row[0] for row in self.env.cr.fetchall()])
        for job in jobs:
            job.write({
                "state": "sent",
                "device_id": device.id,
                "date_sent": fields.Datetime.now(),
                "attempt_count": job.attempt_count + 1,
            })
        return jobs

    def _to_bridge_payload(self):
        return [
            {"id": job.id, "name": job.name, "zpl": job.zpl, "copies": max(job.copies, 1)}
            for job in self
        ]

    def _acknowledge(self, status, message=None):
        self.ensure_one()
        if self.state not in ("sent", "pending"):
            return False
        if status == "done":
            self.write({"state": "done", "date_done": fields.Datetime.now(), "error_message": False})
        else:
            self.write({
                "state": "error",
                "date_done": fields.Datetime.now(),
                "error_message": (message or _("Erreur d'impression signalée par le PDA"))[:250],
            })
            if self.picking_ids:
                body = _("Échec d'impression sur le PDA %(device)s : %(msg)s",
                         device=self.device_id.name or "", msg=self.error_message)
                self.picking_ids._message_log_batch({picking.id: body for picking in self.picking_ids})
        return True

    # ------------------------------------------------------------------
    # Actions utilisateur
    # ------------------------------------------------------------------
    def action_retry(self):
        for job in self:
            device = job.device_id if job.device_id.is_online else job.device_id._pick_for_user(job.user_id)
            job.write({
                "state": "pending",
                "device_id": device.id if device else job.device_id.id,
                "error_message": False,
                "date_done": False,
                "queued_at": fields.Datetime.now(),
            })
        return True

    def action_cancel(self):
        self.filtered(lambda j: j.state in ("pending", "sent", "error")).write({"state": "cancel"})
        return True

    # ------------------------------------------------------------------
    # Tâche planifiée
    # ------------------------------------------------------------------
    @api.model
    def _param_minutes(self, key, default):
        try:
            return max(int(self.env["ir.config_parameter"].sudo().get_param(key, default)), 1)
        except (TypeError, ValueError):
            return default

    @api.model
    def _cron_housekeeping(self):
        now = fields.Datetime.now()
        expired = self.search([
            ("state", "=", "pending"),
            ("queued_at", "<", now - timedelta(minutes=self._param_minutes(PENDING_TTL_PARAM, 15))),
        ])
        expired.write({"state": "cancel", "error_message": _("Expiré : aucun PDA n'a récupéré l'étiquette à temps")})
        lost = self.search([
            ("state", "=", "sent"),
            ("date_sent", "<", now - timedelta(minutes=self._param_minutes(SENT_TIMEOUT_PARAM, 5))),
        ])
        lost.write({"state": "error", "error_message": _("Aucun accusé d'impression reçu du PDA")})
        old = self.search([
            ("state", "in", ("done", "cancel", "error")),
            ("create_date", "<", now - timedelta(days=self._param_minutes(RETENTION_PARAM, 30))),
        ])
        old.unlink()
