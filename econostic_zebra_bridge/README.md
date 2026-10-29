# Pont d'impression mobile Zebra pour Odoo 18

Module `econostic_zebra_bridge` et application Android « Pont Zebra » (`com.econostic.zebrabridge`).

## Le principe

L'impression ne part pas du navigateur : elle part du serveur. Quand un opérateur valide un transfert, Odoo appelle toujours la même méthode (`button_validate`), qu'il travaille dans le formulaire, dans l'application Code-barres ouverte dans Chrome ou dans l'application mobile Odoo. C'est là que le module intervient : les étiquettes ZPL prévues par le type d'opération sont rendues côté serveur et mises en file d'attente pour le PDA de l'opérateur, au lieu d'être renvoyées au navigateur sous forme de fichier texte.

L'application Pont Zebra tourne en service de premier plan sur le PDA. Toutes les deux secondes, elle interroge Odoo, récupère les étiquettes qui lui sont destinées, contrôle l'état de l'imprimante (papier, capot, pause) et envoie le ZPL à la ZQ511 par Bluetooth ou par Wi-Fi. Elle renvoie ensuite un accusé à Odoo : imprimé, ou erreur avec le motif.

```
Opérateur (Code-barres web ou app Odoo)
        │  valide la réception
        ▼
Odoo 18 : button_validate → _get_autoprint_report_actions
        │  actions ZPL interceptées → rendu QWeb texte → zebra.print.job
        ▼
API /zebra_bridge/v1/poll  ◄────── PDA TC26 : app Pont Zebra (toutes les 2 s)
        │                                   │ ~HS (état) puis ZPL
        │                                   ▼
        │                          ZQ511 (Bluetooth SPP ou TCP 9100)
        ▼
/zebra_bridge/v1/jobs/<id>/ack  ◄── imprimé / erreur (papier, capot...)
```

Délai mesuré en test entre la validation et l'arrivée du ZPL à l'imprimante : environ une seconde.

## Prérequis

* Odoo 18 Enterprise (ou Community) avec Inventaire. `product_expiry` est facultatif : s'il est installé, la DLC et la DDM s'impriment sur l'étiquette lot.
* Le serveur doit résoudre la base sans session : base unique, ou `dbfilter` qui ne retient qu'une base pour le nom d'hôte utilisé par les PDA. C'est la configuration normale d'une production on-premise.
* PDA Android 8 ou plus récent (TC26, TC27, TC73...). Le PDA doit joindre l'URL Odoo (Wi-Fi de l'entrepôt).
* Imprimante mobile Zebra en langage ZPL : ZQ511 ou ZQ521, version Bluetooth + Wi-Fi.
* HTTPS recommandé. Un certificat d'une autorité interne fonctionne s'il est installé sur le PDA (MDM ou réglages Sécurité).

## Installation du module

1. Copier `econostic_zebra_bridge` dans le répertoire des modules complémentaires et redémarrer Odoo.
2. Mettre à jour la liste des applications et installer « Pont d'impression mobile Zebra (PDA Android) ».

## Paramétrage dans Odoo

**Type d'opération** (Inventaire > Configuration > Types d'opérations, onglet Matériel) :

1. Dans « Imprimer à la validation », cocher les étiquettes voulues au format ZPL. Pour la réception : « Étiquettes lot/N° de série » avec « Étiquettes ZPL : une par lot » ou « une par unité ».
2. Dans le bloc « Pont mobile Zebra », cocher « Imprimer via le pont mobile Zebra ».
3. « Gabarit ZPL des étiquettes lots » : choisir « Étiquette lot + DLC (ZPL 72 x 50 mm) » pour la ZQ511, ou laisser vide pour garder l'étiquette standard d'Odoo.
4. « Si aucun PDA n'est en ligne » : file d'attente (l'étiquette sortira au retour du PDA, 15 minutes maximum) ou impression standard d'Odoo.

Les documents PDF cochés au même endroit (bon de livraison, rapport de réception...) gardent leur comportement habituel. L'étiquette colis au format ZPL, imprimée sur « Mettre en colis », passe aussi par le pont.

**PDA** (Inventaire > Configuration > Pont mobile Zebra > PDA appairés) :

1. Créer une fiche par terminal, par exemple « TC26 Réception 1 ».
2. Renseigner les opérateurs : les utilisateurs Odoo dont les validations doivent sortir sur ce PDA. Un opérateur rattaché à plusieurs PDA imprime sur le dernier PDA actif.
3. Cliquer sur « Appairer (QR code) ». Le QR code n'est affiché qu'une fois ; un nouvel appairage révoque le précédent.

## Installation de l'application sur le PDA

* Par câble : `adb install -r ZebraBridge-1.0.1.apk`
* En parc : publier l'APK via le MDM (SOTI, StageNow, Workspace ONE...). Avec StageNow, on peut aussi pré-accorder les autorisations et exempter l'app de l'optimisation batterie.

Au premier lancement, accepter l'accès aux appareils à proximité (Bluetooth) et les notifications.

Ensuite, dans l'application :

1. **Appairer avec Odoo** (bloc Odoo de l'application) : toucher le champ d'appairage et scanner le QR code affiché dans Odoo avec la gâchette du terminal (DataWedge en sortie clavier, réglage d'usine). L'appairage se fait seul dès la lecture.
2. **Lier l'imprimante**, au choix :
   * poser le dos du PDA sur le logo NFC de la ZQ511 (Print Touch) : l'adresse Bluetooth est lue et l'imprimante est liée ;
   * « Choisir parmi les appareils appairés » si la ZQ511 a déjà été appairée dans les réglages Bluetooth d'Android ;
   * saisir ou scanner l'adresse MAC Bluetooth (12 caractères, imprimée sur l'étiquette de configuration) ;
   * en Wi-Fi : onglet Wi-Fi, adresse IP fixe de l'imprimante, port 9100.
3. **Identifier** : l'app interroge l'imprimante et affiche modèle, numéro de série, batterie et langage. Si le langage n'est pas ZPL (certaines ZQ5xx sortent en `line_print`), utiliser « Passer l'imprimante en ZPL ».
4. **Étiquette test**, puis activer « Imprimer automatiquement les étiquettes Odoo ».
5. Toucher « Désactiver l'optimisation batterie » pour que le service ne soit pas mis en sommeil en cours de poste.

Le service redémarre seul après un redémarrage du PDA ou une mise à jour de l'application. La notification permanente indique l'état d'Odoo, de l'imprimante et le nombre d'étiquettes du jour.

## En exploitation

* Une étiquette n'est jamais transmise deux fois : chaque travail est réservé par un verrou en base au moment où le PDA le récupère.
* Si l'imprimante est hors de portée, capot ouvert ou sans papier, l'app réessaie pendant une minute environ, puis signale l'erreur à Odoo. L'erreur apparaît dans le travail d'impression et dans le fil de discussion du transfert.
* Réimpression : bouton « Étiquettes Zebra » sur un transfert terminé, ou « Réimprimer » sur un travail d'impression.
* La fiche PDA affiche en direct : en ligne / hors ligne, imprimante liée, numéro de série, batterie, état.
* Les travaux en attente expirent après 15 minutes, les travaux transmis sans accusé passent en erreur après 5 minutes, l'historique est purgé après 30 jours (tâche planifiée toutes les 5 minutes).

Paramètres système facultatifs (Réglages > Technique > Paramètres système) :

| Clé | Défaut | Rôle |
|---|---|---|
| `econostic_zebra_bridge.online_timeout` | 45 | secondes sans contact avant de considérer un PDA hors ligne |
| `econostic_zebra_bridge.pending_ttl_minutes` | 15 | durée de vie d'une étiquette en attente |
| `econostic_zebra_bridge.sent_timeout_minutes` | 5 | délai d'accusé avant passage en erreur |
| `econostic_zebra_bridge.retention_days` | 30 | conservation de l'historique |

## Points d'attention

* Ne pas router en même temps les mêmes étiquettes vers une imprimante IoT Odoo : sur un type d'opération « pont », le ZPL est retiré de la réponse et l'IoT ne le reçoit plus. Les PDF peuvent continuer à passer par l'IoT.
* Le gabarit DLC est calibré pour 72 x 50 mm à 203 dpi (`^PW576 ^LL400`). Pour un autre rouleau, dupliquer la vue `econostic_zebra_bridge.label_lot_dlc_zpl_view` et ajuster ces deux valeurs.
* Le code-barres de l'étiquette est un Code 128 du numéro de lot : l'application Code-barres d'Odoo le reconnaît directement. Éviter le caractère `>` dans les numéros de lot (caractère de commande en Code 128 ZPL).
* Les ordres de fabrication ne sont pas couverts dans cette version : seuls les transferts (réceptions, transferts internes, livraisons) et la mise en colis.
* Le jeton d'appairage est stocké haché côté Odoo. Un PDA perdu se neutralise avec « Révoquer » sur sa fiche.

## API (pour information)

Authentification : `Authorization: Bearer <jeton>`.

* `POST /zebra_bridge/v1/poll` : corps facultatif `{"app_version", "model", "printer": {"name", "serial", "address", "transport", "connected", "status", "battery"}}`. Réponse : `{"device", "operators", "poll_interval", "jobs": [{"id", "name", "zpl", "copies"}]}`.
* `POST /zebra_bridge/v1/jobs/<id>/ack` : `{"status": "done" | "error", "message": "..."}`.
