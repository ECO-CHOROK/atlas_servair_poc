"""API du pont d'impression, appelée par l'application Android « Pont Zebra ».

Authentification : en-tête « Authorization: Bearer <jeton> », jeton propre à chaque PDA,
généré à l'appairage (QR code) et stocké haché côté Odoo.
La base est résolue par Odoo comme pour toute route : base unique ou dbfilter.
"""
import json
import logging

from odoo import http
from odoo.http import request

_logger = logging.getLogger(__name__)

API_VERSION = 1


class ZebraBridgeController(http.Controller):

    def _json(self, payload, status=200):
        return request.make_json_response(payload, status=status)

    def _body(self):
        raw = request.httprequest.get_data(cache=False, as_text=True) or "{}"
        try:
            data = json.loads(raw)
        except ValueError:
            data = {}
        return data if isinstance(data, dict) else {}

    def _device(self):
        header = request.httprequest.headers.get("Authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else ""
        return request.env["zebra.bridge.device"].sudo()._find_by_token(token)

    @http.route("/zebra_bridge/v1/poll", type="http", auth="none", methods=["POST"],
                csrf=False, save_session=False, readonly=False)
    def poll(self, **kwargs):
        """Battement de cœur + récupération des étiquettes à imprimer."""
        device = self._device()
        if not device:
            return self._json({"error": "unauthorized"}, status=401)
        body = self._body()
        device._register_heartbeat(body)
        jobs = request.env["zebra.print.job"].sudo()._fetch_for_device(device)
        return self._json({
            "api": API_VERSION,
            "device": device.name,
            "company": device.company_id.name,
            "operators": device.user_ids.mapped("name"),
            "poll_interval": device.poll_interval,
            "jobs": jobs._to_bridge_payload(),
        })

    @http.route("/zebra_bridge/v1/jobs/<int:job_id>/ack", type="http", auth="none", methods=["POST"],
                csrf=False, save_session=False, readonly=False)
    def ack(self, job_id, **kwargs):
        """Accusé d'impression : {"status": "done" | "error", "message": "..."}."""
        device = self._device()
        if not device:
            return self._json({"error": "unauthorized"}, status=401)
        body = self._body()
        job = request.env["zebra.print.job"].sudo().browse(job_id).exists()
        if not job or job.device_id != device:
            return self._json({"error": "not_found"}, status=404)
        status = "done" if body.get("status") == "done" else "error"
        accepted = job._acknowledge(status, body.get("message"))
        return self._json({"ok": bool(accepted), "state": job.state})
