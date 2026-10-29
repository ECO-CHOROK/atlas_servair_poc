import json
from datetime import timedelta

from odoo import fields
from odoo.tests import HttpCase, TransactionCase, tagged


class ZebraBridgeCommon:

    @classmethod
    def _setup_zebra(cls):
        env = cls.env
        cls.user = env.user
        cls.user.groups_id |= env.ref("stock.group_production_lot") | env.ref("stock.group_stock_manager")
        cls.warehouse = env["stock.warehouse"].search([("company_id", "=", env.company.id)], limit=1)
        cls.ptype = cls.warehouse.in_type_id
        cls.ptype.write({
            "auto_print_lot_labels": True,
            "lot_label_format": "zpl_lots",
            "auto_print_delivery_slip": False,
            "auto_print_product_labels": False,
            "zebra_bridge_enabled": True,
            "zebra_bridge_fallback": "queue",
        })
        cls.product = env["product.product"].create({
            "name": "Yaourt ^nature~ & fruits",
            "default_code": "YAO-01",
            "is_storable": True,
            "tracking": "lot",
        })
        cls.supplier = env["res.partner"].create({"name": "Fournisseur test"})
        cls.device = env["zebra.bridge.device"].create({"name": "TC26 Réception 1", "user_ids": [(6, 0, cls.user.ids)]})
        cls.token = cls.device._generate_token()

    def _receipt(self, lot_name="LOT-A1", qty=3):
        picking = self.env["stock.picking"].create({
            "picking_type_id": self.ptype.id,
            "partner_id": self.supplier.id,
            "location_id": self.env.ref("stock.stock_location_suppliers").id,
            "location_dest_id": self.ptype.default_location_dest_id.id,
            "move_ids": [(0, 0, {
                "name": self.product.name,
                "product_id": self.product.id,
                "product_uom_qty": qty,
                "product_uom": self.product.uom_id.id,
                "location_id": self.env.ref("stock.stock_location_suppliers").id,
                "location_dest_id": self.ptype.default_location_dest_id.id,
            })],
        })
        picking.action_confirm()
        picking.move_ids.move_line_ids.unlink()
        self.env["stock.move.line"].create({
            "move_id": picking.move_ids.id,
            "picking_id": picking.id,
            "product_id": self.product.id,
            "lot_name": lot_name,
            "quantity": qty,
            "location_id": picking.location_id.id,
            "location_dest_id": picking.location_dest_id.id,
        })
        return picking

    def _set_online(self, online=True):
        self.device.last_seen = fields.Datetime.now() - timedelta(seconds=1 if online else 3600)


@tagged("post_install", "-at_install")
class TestZebraBridgeDispatch(ZebraBridgeCommon, TransactionCase):

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls._setup_zebra()

    def test_validate_routes_zpl_to_online_device(self):
        self._set_online()
        picking = self._receipt()
        res = picking.button_validate()
        self.assertEqual(picking.state, "done")
        self.assertIs(res, True, "Aucune action d'impression ne doit revenir au navigateur")
        job = picking.zebra_job_ids
        self.assertEqual(len(job), 1)
        self.assertEqual(job.device_id, self.device)
        self.assertEqual(job.state, "pending")
        self.assertIn("^XA", job.zpl)
        self.assertIn("LOT-A1", job.zpl)
        self.assertEqual(job.label_count, 1)

    def test_lot_dlc_template_substitution_and_sanitizing(self):
        self._set_online()
        self.ptype.zebra_lot_report_id = self.env.ref("econostic_zebra_bridge.report_lot_label_dlc_zpl")
        self.ptype.lot_label_format = "zpl_units"
        picking = self._receipt(lot_name="LOT-B2", qty=2)
        picking.button_validate()
        job = picking.zebra_job_ids
        self.assertIn("^PW576", job.zpl)
        self.assertIn("Lot : LOT-B2", job.zpl)
        self.assertIn("Réf. YAO-01", job.zpl)
        self.assertEqual(job.zpl.count("^XA"), 2, "Une étiquette par unité")
        # le nom produit ne doit ni casser le ZPL ni être échappé en HTML
        self.assertIn("^FDYaourt nature & fruits^FS", job.zpl)
        self.assertNotIn("&amp;", job.zpl)

    def test_offline_queue_then_pickup_by_user_device(self):
        self._set_online(False)
        picking = self._receipt()
        res = picking.button_validate()
        self.assertIs(res, True)
        job = picking.zebra_job_ids
        self.assertFalse(job.device_id)
        self.assertTrue(picking.message_ids.filtered(lambda m: "en attente" in (m.body or "")))
        jobs = self.env["zebra.print.job"]._fetch_for_device(self.device)
        self.assertEqual(jobs, job)
        self.assertEqual(job.state, "sent")
        self.assertEqual(job.device_id, self.device)
        # déjà transmis : pas renvoyé une seconde fois
        self.assertFalse(self.env["zebra.print.job"]._fetch_for_device(self.device))

    def test_offline_browser_fallback(self):
        self._set_online(False)
        self.ptype.zebra_bridge_fallback = "browser"
        picking = self._receipt()
        res = picking.button_validate()
        self.assertIsInstance(res, dict)
        self.assertEqual(res["tag"], "do_multi_print")
        self.assertEqual(res["params"]["reports"][0]["report_type"], "qweb-text")
        self.assertFalse(picking.zebra_job_ids)

    def test_non_bridge_type_untouched(self):
        self.ptype.zebra_bridge_enabled = False
        self._set_online()
        picking = self._receipt()
        res = picking.button_validate()
        self.assertEqual(res["tag"], "do_multi_print")
        self.assertFalse(picking.zebra_job_ids)

    def test_pdf_actions_kept(self):
        self._set_online()
        self.ptype.auto_print_delivery_slip = True
        picking = self._receipt()
        res = picking.button_validate()
        reports = res["params"]["reports"]
        self.assertEqual([r["report_type"] for r in reports], ["qweb-pdf"])
        self.assertEqual(len(picking.zebra_job_ids), 1)

    def test_reprint_and_ack(self):
        self._set_online()
        picking = self._receipt()
        picking.button_validate()
        picking.action_zebra_bridge_print()
        self.assertEqual(len(picking.zebra_job_ids), 2)
        jobs = self.env["zebra.print.job"]._fetch_for_device(self.device)
        self.assertEqual(len(jobs), 2)
        jobs[0]._acknowledge("done")
        jobs[1]._acknowledge("error", "Papier absent")
        self.assertEqual(jobs.mapped("state"), ["done", "error"])
        self.assertEqual(jobs[1].error_message, "Papier absent")

    def test_housekeeping(self):
        self._set_online()
        picking = self._receipt()
        picking.button_validate()
        job = picking.zebra_job_ids
        job.queued_at = fields.Datetime.now() - timedelta(hours=1)
        self.env["zebra.print.job"]._cron_housekeeping()
        self.assertEqual(job.state, "cancel")
        job.action_retry()
        self.assertEqual(job.state, "pending")
        self.assertEqual(self.env["zebra.print.job"]._fetch_for_device(self.device), job)

    def test_token_lookup(self):
        self.assertEqual(self.env["zebra.bridge.device"]._find_by_token(self.token), self.device)
        self.assertFalse(self.env["zebra.bridge.device"]._find_by_token("x" * 43))
        self.device.action_revoke()
        self.assertFalse(self.env["zebra.bridge.device"]._find_by_token(self.token))


@tagged("post_install", "-at_install")
class TestZebraBridgeHttp(ZebraBridgeCommon, HttpCase):

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls._setup_zebra()

    def _post(self, path, body, token=None):
        headers = {"Content-Type": "application/json"}
        if token is not False:
            headers["Authorization"] = "Bearer %s" % (token or self.token)
        return self.url_open(path, data=json.dumps(body), headers=headers)

    def test_poll_and_ack_roundtrip(self):
        self._set_online()
        picking = self._receipt()
        picking.button_validate()
        job = picking.zebra_job_ids

        self.assertEqual(self._post("/zebra_bridge/v1/poll", {}, token=False).status_code, 401)
        self.assertEqual(self._post("/zebra_bridge/v1/poll", {}, token="bad" * 10).status_code, 401)

        res = self._post("/zebra_bridge/v1/poll", {
            "app_version": "1.0.0", "model": "TC26",
            "printer": {"name": "ZQ511", "serial": "XXZKJ123", "address": "AC:3F:A4:00:11:22",
                        "transport": "bluetooth", "connected": True, "status": "Prête", "battery": 87},
        })
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertEqual(data["device"], "TC26 Réception 1")
        self.assertEqual([j["id"] for j in data["jobs"]], job.ids)
        self.assertIn("^XA", data["jobs"][0]["zpl"])
        self.device.invalidate_recordset()
        self.assertEqual(self.device.printer_serial, "XXZKJ123")
        self.assertEqual(self.device.printer_battery, 87)
        self.assertTrue(self.device.is_online)

        res = self._post("/zebra_bridge/v1/jobs/%s/ack" % job.id, {"status": "done"})
        self.assertEqual(res.json()["state"], "done")

        other = self.env["zebra.bridge.device"].create({"name": "Autre"})
        other_token = other._generate_token()
        res = self._post("/zebra_bridge/v1/jobs/%s/ack" % job.id, {"status": "error"}, token=other_token)
        self.assertEqual(res.status_code, 404)


@tagged("post_install", "-at_install")
class TestZebraLotDlcTemplate(ZebraBridgeCommon, TransactionCase):

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls._setup_zebra()

    def test_dlc_ddm_printed_when_product_expiry_installed(self):
        lot = self.env["stock.lot"].create({"name": "LOT-DLC", "product_id": self.product.id})
        if "expiration_date" not in lot._fields:
            self.skipTest("product_expiry non installé")
        lot.write({"expiration_date": "2026-11-30 10:00:00", "use_date": "2026-11-25 10:00:00"})
        zpl, _fmt = self.env["ir.actions.report"]._render_qweb_text(
            "econostic_zebra_bridge.report_lot_label_dlc_zpl", lot.ids)
        zpl = zpl.decode() if isinstance(zpl, bytes) else str(zpl)
        self.assertIn("DLC : 30/11/2026", zpl)
        self.assertIn("DDM : 25/11/2026", zpl)
        self.assertIn("^BCN,110,Y,N,N^FDLOT-DLC^FS", zpl)
        lot.use_date = lot.expiration_date
        zpl2 = self.env["ir.actions.report"]._render_qweb_text(
            "econostic_zebra_bridge.report_lot_label_dlc_zpl", lot.ids)[0]
        zpl2 = zpl2.decode() if isinstance(zpl2, bytes) else str(zpl2)
        self.assertNotIn("DDM", zpl2)


@tagged("post_install", "-at_install")
class TestZebraBridgeBatch(ZebraBridgeCommon, TransactionCase):

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls._setup_zebra()

    def test_multi_picking_validation_offline(self):
        self._set_online(False)
        pickings = self._receipt("LOT-M1") | self._receipt("LOT-M2")
        pickings.button_validate()
        self.assertTrue(all(p.state == "done" for p in pickings))
        job = pickings.zebra_job_ids
        self.assertEqual(len(job), 1)
        self.assertIn("LOT-M1", job.zpl)
        self.assertIn("LOT-M2", job.zpl)
        for picking in pickings:
            self.assertTrue(picking.message_ids.filtered(lambda m: "en attente" in (m.body or "")))


@tagged("post_install", "-at_install")
class TestZebraBridgeOperatorWithoutEmail(ZebraBridgeCommon, TransactionCase):

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls._setup_zebra()
        cls.operator = cls.env["res.users"].create({
            "name": "Opérateur sans e-mail", "login": "op_noemail",
            "groups_id": [(6, 0, (cls.env.ref("stock.group_stock_user") | cls.env.ref("stock.group_production_lot")).ids)],
        })
        cls.device.user_ids = [(6, 0, cls.operator.ids)]

    def test_offline_validation_does_not_fail_without_email(self):
        self._set_online(False)
        picking = self._receipt("LOT-NOMAIL")
        picking.with_user(self.operator).button_validate()
        self.assertEqual(picking.state, "done")
        self.assertEqual(len(picking.zebra_job_ids), 1)
        job = self.env["zebra.print.job"]._fetch_for_device(self.device)
        job._acknowledge("error", "Capot ouvert")
        self.assertTrue(picking.message_ids.filtered(lambda m: "Capot ouvert" in (m.body or "")))
