package com.k2fsa.sherpa.onnx.vad.asr

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.k2fsa.sherpa.onnx.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Settings.init(getExternalFilesDir(null) ?: filesDir)

        val langSp = findViewById<Spinner>(R.id.sp_lang)
        val asrDirEd = findViewById<EditText>(R.id.ed_asrdir)
        val engineSp = findViewById<Spinner>(R.id.sp_engine)
        val targetSp = findViewById<Spinner>(R.id.sp_target)
        val operitSw = findViewById<Switch>(R.id.sw_operit)
        val portEd = findViewById<EditText>(R.id.ed_port)
        val tokenEd = findViewById<EditText>(R.id.ed_token)
        val chatEd = findViewById<EditText>(R.id.ed_chat)
        val translSw = findViewById<Switch>(R.id.sw_translate)
        val urlEd = findViewById<EditText>(R.id.ed_transurl)
        val modelEd = findViewById<EditText>(R.id.ed_transmodel)
        val beepSw = findViewById<Switch>(R.id.sw_beep)

        val item = android.R.layout.simple_spinner_dropdown_item
        // vlastny layout pre spinnery (viditelny text na tmavom pozadi)
        val langs = arrayOf("SK (Parakeet - najpresnejsi)", "EN", "DE", "FR", "ES", "CZ", "PL", "IT")
        val langVals = arrayOf("sk", "en", "de", "fr", "es", "cz", "pl", "it")
        langSp.adapter = ArrayAdapter(this, R.layout.spinner_item, langs)
        langSp.setSelection(langVals.indexOf(Settings.asrLang).coerceAtLeast(0))

        asrDirEd.setText(Settings.asrModelDir)

        val engines = arrayOf("ML Kit (offline, jednoduche)", "GGUF (vlastny model/server)")
        engineSp.adapter = ArrayAdapter(this, R.layout.spinner_item, engines)
        engineSp.setSelection(if (Settings.translateEngine == "gguf") 1 else 0)

        targetSp.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("SK", "EN", "DE", "FR", "ES", "CZ", "PL", "IT"))
        targetSp.setSelection(arrayOf("SK","EN","DE","FR","ES","CZ","PL","IT").indexOf(Settings.translateTarget.uppercase()).coerceAtLeast(1))

        operitSw.isChecked = Settings.operitEnabled
        portEd.setText(Settings.operitPort)
        tokenEd.setText(Settings.operitToken)
        chatEd.setText(Settings.operitChatId)
        translSw.isChecked = Settings.translateEnabled
        urlEd.setText(Settings.translateUrl)
        modelEd.setText(Settings.translateModel)
        beepSw.isChecked = Settings.beep
        tokenEd.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_dlmlkit).setOnClickListener {
            val from = langVals[langSp.selectedItemPosition]
            val to = targetSp.selectedItem.toString().lowercase()
            Toast.makeText(this, "Stahujem ML Kit model $from->$to ...", Toast.LENGTH_LONG).show()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = Translate.downloadMlkit(from, to)
                runOnUiThread { Toast.makeText(this, if (ok) "ML Kit model stiahnuty" else "Stiahnutie zlyhalo", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Settings.asrLang = langVals[langSp.selectedItemPosition]
            Settings.asrModelDir = asrDirEd.text.toString()
            Settings.translateEngine = if (engineSp.selectedItemPosition == 1) "gguf" else "mlkit"
            Settings.translateTarget = targetSp.selectedItem.toString().uppercase()
            Settings.operitEnabled = operitSw.isChecked
            Settings.operitPort = portEd.text.toString().ifBlank { "8094" }
            Settings.operitToken = tokenEd.text.toString()
            Settings.operitChatId = chatEd.text.toString()
            Settings.translateEnabled = translSw.isChecked
            Settings.translateUrl = urlEd.text.toString()
            Settings.translateModel = modelEd.text.toString()
            Settings.beep = beepSw.isChecked
            Settings.save()
            Toast.makeText(this, "Ulozene", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}