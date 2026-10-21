package com.golfswing.analyzer

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Clubs proposés avant chaque tir (tir par tir) ou au début de chaque session (enregistrement continu). */
object Clubs {

    const val UNSPECIFIED = "Non précisé"

    val LIST = arrayOf(
        "Driver", "Bois 3", "Bois 5", "Hybride",
        "Fer 4", "Fer 5", "Fer 6", "Fer 7", "Fer 8", "Fer 9",
        "Pitching wedge", "Sand wedge", "Lob wedge", "Putter", "Autre"
    )

    /** Nom sans espaces ni accents, pour les fichiers : « Fer 7 » -> « fer_7 ». */
    fun slug(club: String): String =
        club.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "non_precise" }

    /** Choix d'un club dans une liste ; [onCancel] si l'utilisateur ferme la liste sans choisir. */
    fun choose(context: Context, current: String, onPick: (String) -> Unit, onCancel: () -> Unit = {}) {
        val index = LIST.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Quel club utilisez-vous ?")
            .setSingleChoiceItems(LIST, index) { dialog, which ->
                dialog.dismiss()
                onPick(LIST[which])
            }
            .setNegativeButton("Annuler") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
