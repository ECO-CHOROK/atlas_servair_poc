package com.econostic.zebrabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance le pont après redémarrage du PDA ou mise à jour de l'application. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.serviceEnabled && prefs.isPaired) {
            try {
                BridgeService.start(context)
            } catch (_: Exception) {
                // démarrage en arrière-plan refusé par le système : l'opérateur rouvrira l'app
            }
        }
    }
}
