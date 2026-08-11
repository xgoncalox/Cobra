package com.facerecog.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/**
 * Gates access to the Admin screen behind a 10-digit code.
 *
 * IMPORTANT: change ADMIN_CODE below to your own 10-digit code before building.
 * This is a simple app-side gate (fine for a small shared app among people you know);
 * it is not a substitute for real user accounts/roles.
 */
object AdminGate {

    // TODO: change this to your own 10-digit admin code.
    private const val ADMIN_CODE = "1234567890"

    fun promptForCode(context: Context, onSuccess: () -> Unit) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "10-digit admin code"

        AlertDialog.Builder(context)
            .setTitle("Admin access")
            .setMessage("Enter the admin code to manage people.")
            .setView(input)
            .setPositiveButton("Enter") { _, _ ->
                val code = input.text.toString().trim()
                if (code == ADMIN_CODE) {
                    onSuccess()
                } else {
                    Toast.makeText(context, "Incorrect code", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
