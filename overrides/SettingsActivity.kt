package com.k2fsa.sherpa.onnx.vad.asr

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.R

/**
 * Nastavenia v apke — aby to vedel nastavit kazdy (bez editovania suborov).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Settings.init(getExternalFilesDir(null) ?: filesDir)

        val langSp = findViewById<Spinner>(R.id.sp_lang)
        val targetSp = findViewById<Spinner>(R.id.sp_target)
        val operitSw = findViewById<Switch>(R.id.sw_operit)
        val portEd = findViewById<EditText>(R.id.ed_port)
        val tokenEd = findViewById<EditText>(R.id.ed_token)
        val chatEd = findViewById<EditText>(R.id.ed_chat)
        val translSw = findViewById<Switch>(R.id.sw_translate)
        val urlEd = findViewById<EditText>(R.id.ed_transurl)
        val modelEd = findViewById<EditText>(R.id.ed_transmodel)
        val beepSw = findViewById<Switch>(R.id.sw_beep)

        val langs = arrayOf("SK", "EN", "DE", "FR", "ES")
        langSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        langSp.setSelection(langs.indexOf(Settings.asrLang.uppercase()).coerceAtLeast(0))
        targetSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        targetSp.setSelection(langs.indexOf(Settings.translateTarget.uppercase()).coerceAtLeast(0))

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
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Settings.asrLang = langs[langSp.selectedItemPosition].lowercase()
            Settings.translateTarget = langs[targetSp.selectedItemPosition]
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