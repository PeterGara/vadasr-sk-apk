package com.k2fsa.sherpa.onnx.vad.asr

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var translateSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var ttsModeSpinner: Spinner
    private lateinit var ttsSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Settings.init(getExternalFilesDir(null) ?: filesDir)

        list = findViewById(R.id.providers_list)
        translateSpinner = findViewById(R.id.sp_translate)
        targetSpinner = findViewById(R.id.sp_target)
        ttsModeSpinner = findViewById(R.id.sp_tts_mode)
        ttsSpinner = findViewById(R.id.sp_tts_provider)

        val port = findViewById<EditText>(R.id.ed_port)
        val token = findViewById<EditText>(R.id.ed_token)
        val chat = findViewById<EditText>(R.id.ed_chat)
        port.setText(Settings.operitPort)
        token.setText(Settings.operitToken)
        chat.setText(Settings.operitChatId)

        findViewById<Button>(R.id.btn_add).setOnClickListener { editProvider(-1) }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save_operit).setOnClickListener {
            Settings.operitPort = port.text.toString()
            Settings.operitToken = token.text.toString()
            Settings.operitChatId = chat.text.toString()
            Settings.save(); toast("Ulozene")
        }
        ttsModeSpinner.setSelection(if (Settings.ttsMode == "http") 1 else 0)
    }

    override fun onResume() { super.onResume(); renderProviders(); renderSpinners() }

    private fun renderProviders() {
        list.removeAllViews()
        if (Settings.providers.isEmpty()) list.addView(TextView(this).apply { text = "Ziadne API. Pridaj"; setPadding(24, 24, 24, 24) })
        Settings.providers.forEachIndexed { i, p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16,16,16,16) }
            val tv = TextView(this).apply {
                text = "${p.name}\n${p.endpoint}\n${p.model}  [${if (p.apiKey.isBlank()) "?" else "OK"}]"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val ed = Button(this).apply { text = "Uprav"; setOnClickListener { editProvider(i) } }
            val dl = Button(this).apply { text = "Zmaz"; setOnClickListener { Settings.providers.removeAt(i); Settings.save(); renderProviders(); renderSpinners() } }
            row.addView(tv); row.addView(ed); row.addView(dl); list.addView(row)
        }
    }

    private fun renderSpinners() {
        val names = Settings.providers.map { it.name }.toTypedArray()
        val arr = if (names.isEmpty()) arrayOf("(ziadne)") else names
        translateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arr)
        ttsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arr)
        translateSpinner.setSelection(Settings.translateProviderIndex.coerceIn(0, arr.size - 1))
        ttsSpinner.setSelection(Settings.ttsProviderIndex.coerceIn(0, arr.size - 1))
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("SK","EN","DE","FR","ES"))
        targetSpinner.setSelection(arrayOf("SK","EN","DE","FR","ES").indexOf(Settings.translateTarget).coerceAtLeast(0))
        translateSpinner.onItemSelectedListener = Sel { Settings.translateProviderIndex = it; Settings.save() }
        ttsSpinner.onItemSelectedListener = Sel { Settings.ttsProviderIndex = it; Settings.save() }
        targetSpinner.onItemSelectedListener = Sel { Settings.translateTarget = arrayOf("SK","EN","DE","FR","ES")[it]; Settings.save() }
        ttsModeSpinner.onItemSelectedListener = Sel { Settings.ttsMode = if (it == 1) "http" else "android"; Settings.save() }
    }

    private fun editProvider(idx: Int) {
        val p = if (idx >= 0) Settings.providers[idx] else ApiProvider()
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,16) }
        val name = EditText(this).apply { hint = "Nazov (DeepSeek)"; setText(p.name) }
        val ep = EditText(this).apply { hint = "Endpoint (https://api.deepseek.com/v1)"; setText(p.endpoint); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val key = EditText(this).apply { hint = "API kluc"; setText(p.apiKey); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = EditText(this).apply { hint = "Model"; setText(p.model) }
        listOf(name, ep, key, model).forEach { v.addView(it) }
        AlertDialog.Builder(this).setTitle(if (idx >= 0) "Upravit API" else "Nove API").setView(v)
            .setPositiveButton("Ulozit") { _, _ ->
                p.name = name.text.toString(); p.endpoint = ep.text.toString(); p.apiKey = key.text.toString(); p.model = model.text.toString()
                if (idx < 0) Settings.providers.add(p)
                Settings.save(); renderProviders(); renderSpinners()
            }.setNegativeButton("Zrusit", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private class Sel(val cb: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) = cb(pos)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
