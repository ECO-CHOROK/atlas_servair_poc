{
    "name": "Pont d'impression mobile Zebra (PDA Android)",
    "summary": "Impression ZPL directe sur imprimante mobile Zebra (Bluetooth ou Wi-Fi) "
               "via un PDA Android, déclenchée à la validation des transferts, "
               "y compris depuis l'application Code-barres.",
    "description": """
Pont d'impression mobile Zebra
==============================

À la validation d'un transfert (formulaire, application Code-barres dans le
navigateur ou application mobile Odoo), les étiquettes ZPL configurées dans
l'onglet « Matériel » du type d'opération ne sont plus téléchargées par le
navigateur : elles sont mises en file d'attente côté serveur et récupérées
par l'application Android « Pont Zebra » installée sur le PDA de l'opérateur,
qui les envoie à l'imprimante mobile Zebra appairée (Bluetooth SPP ou TCP 9100).

* Activation par type d'opération (case « Imprimer via le pont mobile Zebra »).
* Réutilise les réglages natifs « Imprimer à la validation » au format ZPL
  (étiquettes produits, lots/N° de série, colis).
* Gabarit ZPL « Lot + DLC/DDM » 72 mm pour imprimante 3 pouces (ZQ511/ZQ521).
* Appairage du PDA par QR code, jeton par appareil.
* Suivi des travaux d'impression (envoyé, imprimé, erreur imprimante remontée
  par le PDA : papier absent, capot ouvert, batterie...).
* Réimpression manuelle depuis le transfert.
""",
    "version": "18.0.1.1.0",
    "category": "Inventory/Inventory",
    "author": "Econostic",
    "website": "https://www.econostic.com",
    "license": "OPL-1",
    "depends": ["stock"],
    "data": [
        "security/zebra_bridge_security.xml",
        "security/ir.model.access.csv",
        "data/ir_cron.xml",
        "report/zebra_lot_label.xml",
        "wizard/zebra_bridge_pairing_wizard_views.xml",
        "views/zebra_bridge_device_views.xml",
        "views/zebra_print_job_views.xml",
        "views/stock_picking_type_views.xml",
        "views/stock_picking_views.xml",
        "views/menus.xml",
    ],
    "installable": True,
    "application": False,
}
