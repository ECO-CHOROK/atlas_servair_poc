import logging

from odoo import _, api, fields, models
from odoo.exceptions import UserError

_logger = logging.getLogger(__name__)


class StockPicking(models.Model):
    _inherit = "stock.picking"

    zebra_bridge_enabled = fields.Boolean(related="picking_type_id.zebra_bridge_enabled")
    zebra_job_ids = fields.Many2many(
        "zebra.print.job", "zebra_print_job_picking_rel", "picking_id", "job_id",
        string="Impressions Zebra", copy=False,
    )
    zebra_job_count = fields.Integer(compute="_compute_zebra_job_count")

    @api.depends("zebra_job_ids")
    def _compute_zebra_job_count(self):
        for picking in self:
            picking.zebra_job_count = len(picking.zebra_job_ids)

    # ------------------------------------------------------------------
    # Interception des impressions automatiques à la validation
    # ------------------------------------------------------------------
    def _get_autoprint_report_actions(self):
        """Point d'appel unique de button_validate (formulaire, Code-barres web ou app mobile).

        Les actions ZPL des types d'opération « pont Zebra » sont converties en travaux
        d'impression côté serveur et retirées de la réponse : le navigateur ne télécharge
        plus de fichier texte. Les autres actions (PDF...) sont retournées telles quelles.
        """
        if self.env.context.get("zebra_bridge_skip"):
            return super()._get_autoprint_report_actions()
        bridge = self.filtered(lambda p: p.picking_type_id.zebra_bridge_enabled)
        others = self - bridge
        actions = super(StockPicking, others)._get_autoprint_report_actions() if others else []
        for _ptype, pickings in bridge.grouped("picking_type_id").items():
            native = super(StockPicking, pickings)._get_autoprint_report_actions()
            actions += pickings._zebra_bridge_dispatch(native)
        return actions

    def _post_put_in_pack_hook(self, package_id):
        res = super()._post_put_in_pack_hook(package_id)
        if (
            isinstance(res, dict)
            and self.picking_type_id[:1].zebra_bridge_enabled
            and self.env["zebra.print.job"]._is_zpl_action(res)
        ):
            remaining = self._zebra_bridge_dispatch([res])
            return remaining[0] if remaining else package_id
        return res

    def _zebra_bridge_dispatch(self, actions, force=False):
        """Crée les travaux d'impression pour les actions ZPL ; retourne les actions restantes."""
        Job = self.env["zebra.print.job"]
        picking_type = self.picking_type_id[:1]
        device = self.env["zebra.bridge.device"]._pick_for_user(self.env.user, self.company_id[:1])
        remaining, jobs = [], Job.browse()
        for action in actions:
            if not Job._is_zpl_action(action):
                remaining.append(action)
                continue
            if not device and picking_type.zebra_bridge_fallback == "browser" and not force:
                remaining.append(action)
                continue
            try:
                with self.env.cr.savepoint():
                    jobs |= Job._create_from_report_action(action, self, device)
            except Exception:
                _logger.exception("Pont Zebra : rendu ZPL impossible pour %s", action.get("report_name"))
                remaining.append(action)
        if jobs and not device:
            body = _("%s étiquette(s) en attente : aucun PDA pont Zebra en ligne pour %s. "
                     "Elles seront imprimées dès sa reconnexion.",
                     sum(jobs.mapped("label_count")), self.env.user.name)
            # note de journal sans notification : ne doit jamais bloquer la validation
            # (par exemple pour un opérateur sans adresse e-mail)
            self._message_log_batch({picking.id: body for picking in self})
        return remaining

    # ------------------------------------------------------------------
    # Actions utilisateur
    # ------------------------------------------------------------------
    def action_zebra_bridge_print(self):
        """Réimprime les étiquettes ZPL du type d'opération sur le PDA de l'utilisateur."""
        total = 0
        for _ptype, pickings in self.grouped("picking_type_id").items():
            native = super(StockPicking, pickings.with_context(zebra_bridge_skip=True))._get_autoprint_report_actions()
            zpl_actions = [a for a in native if self.env["zebra.print.job"]._is_zpl_action(a)]
            if not zpl_actions:
                continue
            pickings._zebra_bridge_dispatch(zpl_actions, force=True)
            total += len(zpl_actions)
        if not total:
            raise UserError(_(
                "Aucune étiquette au format ZPL n'est cochée dans « Imprimer à la validation » "
                "(onglet Matériel) pour ce type d'opération."
            ))
        return {
            "type": "ir.actions.client",
            "tag": "display_notification",
            "params": {
                "title": _("Étiquettes envoyées au pont Zebra"),
                "message": _("Impression en cours sur votre PDA."),
                "type": "success",
                "sticky": False,
                "next": {"type": "ir.actions.act_window_close"},
            },
        }

    def action_view_zebra_jobs(self):
        self.ensure_one()
        action = self.env["ir.actions.act_window"]._for_xml_id("econostic_zebra_bridge.action_zebra_print_job")
        action["domain"] = [("id", "in", self.zebra_job_ids.ids)]
        return action
