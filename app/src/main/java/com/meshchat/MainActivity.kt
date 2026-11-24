package com.meshchat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity(), MeshManager.Listener {

    private lateinit var statusView: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var input: EditText
    private var mesh: MeshManager? = null
    private var myName = ""
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        askName()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(28), dp(12), dp(12))
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#1B5E20"))
            text = "Starting..."
            setPadding(0, 0, 0, dp(8))
        }

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val s = getItem(position) ?: ""
                v.textSize = 15f
                v.setTextColor(if (s.contains("SOS")) Color.RED else Color.BLACK)
                return v
            }
        }
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        }

        input = EditText(this).apply { hint = "Type a message" }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener { sendText(false) }
        }
        val sos = Button(this).apply {
            text = "SOS"
            setTextColor(Color.RED)
            setOnClickListener { sendText(true) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
            addView(sos)
        }

        root.addView(statusView)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        setContentView(root)
    }

    private fun sendText(sos: Boolean) {
        var text = input.text.toString().trim()
        if (sos && text.isEmpty()) text = "I need help!"
        if (text.isEmpty()) return
        mesh?.send(text, sos)
        input.setText("")
    }

    // ---------- name + permissions ----------

    private fun askName() {
        val et = EditText(this).apply { setText("Phone-" + Build.MODEL.take(10)) }
        AlertDialog.Builder(this)
            .setTitle("Your name in the mesh")
            .setMessage("Use something short, like Phone A, Phone B, Phone C")
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("Start") { _, _ ->
                myName = et.text.toString().trim().ifEmpty { "Phone" }.replace("|", "")
                requestPerms()
            }
            .show()
    }

    private fun neededPermissions(): List<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_ADVERTISE
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT <= 31) p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        return p
    }

    private fun requestPerms() {
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMesh() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMesh()
        } else {
            statusView.text = "Permissions denied. Allow them in Settings > Apps > MeshChat."
        }
    }

    private fun startMesh() {
        mesh = MeshManager(applicationContext, myName, this).also { it.start() }
        onPeersChanged(emptyList())
    }

    // ---------- MeshManager.Listener ----------

    override fun onMessage(m: MeshMessage, mine: Boolean) = runOnUiThread {
        val t = timeFmt.format(Date(m.time))
        val tag = if (m.sos) "🆘 SOS " else ""
        val line = if (mine) {
            "[$t] ${tag}Me: ${m.text}"
        } else {
            "[$t] ${tag}${m.origin}: ${m.text}\n      via ${m.hops} hop(s): ${m.path}"
        }
        adapter.add(line)
    }

    override fun onPeersChanged(names: List<String>) = runOnUiThread {
        statusView.text = "Me: $myName   |   Connected: ${names.size}" +
                if (names.isEmpty()) "\nSearching for nearby phones..." else "\n" + names.joinToString(", ")
    }

    override fun onStatus(text: String) = runOnUiThread {
        if (text.contains("failed")) statusView.append("\n$text")
    }

    override fun onDestroy() {
        mesh?.stop()
        super.onDestroy()
    }
}
