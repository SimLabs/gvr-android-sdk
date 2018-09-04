package com.google.vr.ndk.samples.treasurehunt

import android.support.v4.app.DialogFragment
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.CheckBox
import android.widget.TextView
import kotlinx.android.synthetic.main.dialog_streaming_setup.view.*
import ru.simlabs.stream.utils.StreamPreferencesConstants


class SetupStreamingDialog: DialogFragment() {
    private lateinit var exitListener: ExitListener

    interface ExitListener {
        fun onDialogExited()
    }

    @Suppress("DEPRECATION")
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the NoticeDialogListener so we can send events to the host
            exitListener = activity as ExitListener
        } catch (e: ClassCastException) {
            // The activity doesn't implement the interface, throw exception
            throw ClassCastException(activity.toString() + " must implement ExitListener")
        }

    }

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = activity ?: error("Activity should not be null")

        val builder = AlertDialog.Builder(activity)
        // Get the layout inflater
        val inflater = activity.layoutInflater

        val streamingPreferences = activity.getSharedPreferences(
                StreamPreferencesConstants.STREAMING_PREFERENCES_NAME,
                Context.MODE_PRIVATE
        )

        // Inflate and set the layout for the dialog
        // Pass null as the parent view because its going in the dialog layout
        builder.setView(
                inflater.inflate(R.layout.dialog_streaming_setup, null).apply {
                    streaming_enabled.isChecked = streamingPreferences
                            .getBoolean(StreamPreferencesConstants.STREAMING_ENABLED_KEY, false)
                    streamingPreferences
                            .getString(StreamPreferencesConstants.STREAMING_ADDRESS_KEY, null)
                            ?.let(streaming_server_address::setText)
                }
        )
                // Add action buttons
                .setPositiveButton("OK") { _, _ ->
                    val streamingPreferencesEditor = streamingPreferences.edit()

                    streamingPreferencesEditor.putBoolean(
                            StreamPreferencesConstants.STREAMING_ENABLED_KEY,
                            this@SetupStreamingDialog
                                    .dialog
                                    .findViewById<CheckBox>(R.id.streaming_enabled)
                                    .isChecked
                    )

                    streamingPreferencesEditor.putString(
                            StreamPreferencesConstants.STREAMING_ADDRESS_KEY,
                            this@SetupStreamingDialog
                                    .dialog
                                    .findViewById<TextView>(R.id.streaming_server_address)
                                    .text
                                    .toString()
                    )

                    streamingPreferencesEditor.apply()

                    exitListener.onDialogExited()
                }
                .setNegativeButton("Discard changes") { _, _ ->
                    exitListener.onDialogExited()
                }
        return builder.create()
    }
}