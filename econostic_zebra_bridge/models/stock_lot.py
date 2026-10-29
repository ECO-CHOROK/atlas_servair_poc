from markupsafe import Markup

from odoo import fields, models


def zpl_safe(value, size=None):
    """Texte sûr pour un champ ^FD : sans caractères de commande ZPL ni retours ligne."""
    text = str(value or "")
    for char in ("^", "~", "\\", "\r", "\n", "\t"):
        text = text.replace(char, " ")
    text = " ".join(text.split())
    if size and len(text) > size:
        text = text[: size - 1] + "…"
    return Markup(text)  # pas d'échappement HTML dans un rapport texte


class StockLot(models.Model):
    _inherit = "stock.lot"

    def _zebra_label_values(self):
        """Valeurs prêtes à imprimer pour le gabarit ZPL « Lot + DLC »."""
        self.ensure_one()

        def fmt(dt):
            if not dt:
                return False
            return Markup(fields.Datetime.context_timestamp(self, dt).strftime("%d/%m/%Y"))

        product = self.product_id
        dlc = fmt(self["expiration_date"]) if "expiration_date" in self._fields else False
        ddm = fmt(self["use_date"]) if "use_date" in self._fields else False
        return {
            "product": zpl_safe(product.name, 60),
            "ref": zpl_safe(product.default_code, 30),
            "lot": zpl_safe(self.name, 40),
            "lot_barcode": zpl_safe(self.name),
            "dlc": dlc,
            # sans durée DDM sur l'article, Odoo recopie la DLC : inutile de l'imprimer deux fois
            "ddm": ddm if ddm != dlc else False,
            "printed": fmt(fields.Datetime.now()),
        }
