package dev.wristcontrol.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * The phone app has exactly one job: be somewhere the user can start a login
 * when the watch cannot open a browser by itself. Everything else happens on
 * the wrist.
 */
class PhoneMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_main)

        findViewById<Button>(R.id.sign_in_button).setOnClickListener {
            startActivity(Intent(this, OAuthActivity::class.java))
        }
    }
}
