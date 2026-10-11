package tech.hegnes.thermostat

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.hegnes.thermostat.ui.NfcAvailability
import tech.hegnes.thermostat.ui.MainScreen
import tech.hegnes.thermostat.ui.ThermostatViewModel
import tech.hegnes.thermostat.ui.theme.HeatitTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ThermostatViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var availability by mutableStateOf(NfcAvailability.AVAILABLE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            HeatitTheme {
                MainScreen(
                    viewModel = viewModel,
                    nfc = availability,
                    onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        availability = when {
            adapter == null -> NfcAvailability.MISSING
            !adapter.isEnabled -> NfcAvailability.DISABLED
            else -> NfcAvailability.AVAILABLE
        }
        if (adapter != null && adapter.isEnabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            adapter.enableReaderMode(this, { tag -> viewModel.onTag(tag) }, flags, extras)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }
}
