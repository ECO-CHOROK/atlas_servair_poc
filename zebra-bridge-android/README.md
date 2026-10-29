# Pont Zebra : application Android

Application compagnon du module Odoo 18 `econostic_zebra_bridge`. Elle relie un PDA Android (Zebra TC26 et équivalents) à une imprimante mobile Zebra ZQ511 / ZQ521 et imprime les étiquettes ZPL qu'Odoo met en file d'attente à la validation des transferts.

Le guide complet (paramétrage Odoo, appairage, exploitation) est dans le `README.md` du module.

## Contenu

* `BridgeService.kt` : service de premier plan, interrogation d'Odoo, impression, accusés, reprise après coupure.
* `ZebraPrinter.kt` : connexion unique et partagée à l'imprimante, commandes SGD (identification, batterie, langage), lecture de l'état `~HS`, passage en ZPL.
* `PrinterTransport.kt` : liaisons Bluetooth SPP (RFCOMM) et TCP 9100, sans SDK propriétaire.
* `OdooClient.kt` : API `/zebra_bridge/v1/poll` et `/zebra_bridge/v1/jobs/<id>/ack`.
* `MainActivity.kt` : appairage par QR code, liaison de l'imprimante (NFC Print Touch, appareils appairés, MAC, IP), test, état et historique.

Aucune dépendance externe : uniquement le framework Android et la bibliothèque standard Kotlin.

## Compiler

JDK 17 ou 21, Android SDK (plateforme 34, build-tools 34).

```
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties
./gradlew assembleRelease
```

L'APK signé sort dans `app/build/outputs/apk/release/app-release.apk`.

## Signature

Le dossier `keystore/` contient la clé de signature de l'application et son fichier de mots de passe. Il faut le conserver précieusement et hors de tout dépôt public : Android n'accepte une mise à jour que si elle est signée avec la même clé. Pour une nouvelle version, incrémenter `versionCode` et `versionName` dans `app/build.gradle.kts`.

## Captures d'écran

`./gradlew testReleaseUnitTest` régénère les captures de l'application dans `shots/` (rendu Android natif via Robolectric), utilisées dans le guide d'exploitation.
