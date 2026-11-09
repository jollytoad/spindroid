package com.sendspin.sender

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder screen. The real interface arrives with the source role.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.app_name)
            },
        )
    }
}
