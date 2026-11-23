package com.shobi.fieldtracker

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Order booking: the booker's assigned customers, the product catalogue (pricelist prices from
 * Odoo, pieces and cartons, barcode scan), review and send. Orders taken without internet are
 * queued and sent later. Prices are never sent by the phone: Odoo prices each order itself.
 */
class OrdersUi(
    private val act: MainActivity,
    private val prefs: Prefs,
    private val box: LinearLayout,
    private val worker: Executor,
    private val post: (Runnable) -> Unit,
    private val onQueued: () -> Unit,
) {
    private var screen = "customers"        // customers | orders | products | review | detail
    private var search = ""
    private var period = "today"
    private var detailId = 0
    private var customer: JSONObject? = null
    private var deliveryDate = ""
    private var note = ""
    private var currency = "Rs."

    /** Cart: product id -> [pieces, cartons]. */
    private val cart = LinkedHashMap<Int, DoubleArray>()
    private var catalogue: List<JSONObject> = emptyList()
    private var byId: Map<Int, JSONObject> = emptyMap()
    private var catalogueAt = 0L
    private val catalogueFile get() = File(act.filesDir, "catalogue.json")

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private val money: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private fun num(v: Double) = money.format(Math.round(v))
    private fun qtyText(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun text(v: CharSequence, sp: Float = 14f, bold: Boolean = false, colorRes: Int? = null): TextView {
        val tv = TextView(act)
        tv.text = v
        tv.textSize = sp
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        if (colorRes != null) tv.setTextColor(color(colorRes))
        return tv
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(top)
        return p
    }

    private fun rounded(bg: Int, radius: Int = 12, stroke: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(radius).toFloat()
        d.setColor(bg)
        if (stroke != null) d.setStroke(dp(1), stroke)
        return d
    }

    private fun card(bgRes: Int): LinearLayout {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(color(bgRes))
        c.setPadding(dp(12), dp(10), dp(12), dp(10))
        return c
    }

    private fun button(label: String, filled: Boolean = true, onClick: () -> Unit): TextView {
        val b = text(label, 16f, bold = true)
        b.gravity = Gravity.CENTER
        b.setPadding(0, dp(12), 0, dp(12))
        if (filled) {
            b.setTextColor(0xFFFFFFFF.toInt())
            b.background = rounded(color(R.color.brand), 24)
        } else {
            b.setTextColor(color(R.color.brand))
            b.background = rounded(0xFFFFFFFF.toInt(), 24, color(R.color.brand))
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun pill(label: String, bg: Int, fg: Int): TextView {
        val t = text(label, 11f, bold = true)
        t.setTextColor(fg)
        t.background = rounded(bg, 10)
        t.setPadding(dp(8), dp(2), dp(8), dp(2))
        return t
    }

    /** Bordered box for rows, with a line between rows and shaded alternate rows. */
    private fun lined(): LinearLayout {
        val l = LinearLayout(act)
        l.orientation = LinearLayout.VERTICAL
        l.background = rounded(0xFFFFFFFF.toInt(), 8, 0xFFB4B2A9.toInt())
        l.setPadding(dp(1), dp(1), dp(1), dp(1))
        l.clipToOutline = true
        box.addView(l, lp(8))
        return l
    }

    private fun addRow(target: LinearLayout, r: View) {
        if (target.childCount > 0) {
            val line = View(act)
            line.setBackgroundColor(0xFFB4B2A9.toInt())
            target.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        if (((target.childCount + 1) / 2) % 2 == 1) r.setBackgroundColor(0xFFF4F3EE.toInt())
        target.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        val scroll = HorizontalScrollView(act)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(act)
        for ((key, label) in options) {
            val on = key == selected
            val c = text(label, 13f, bold = on)
            c.setTextColor(color(if (on) R.color.ok_text else R.color.off_text))
            c.background = rounded(color(if (on) R.color.ok_bg else android.R.color.white), 16,
                color(if (on) R.color.brand else R.color.off_bg))
            c.setPadding(dp(12), dp(6), dp(12), dp(6))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.rightMargin = dp(6)
            c.setOnClickListener { onPick(key) }
            row.addView(c, p)
        }
        scroll.addView(row)
        box.addView(scroll, lp(10))
    }

    private fun searchBox(hint: String, withScan: Boolean, onSearch: (String) -> Unit) {
        val row = LinearLayout(act)
        row.gravity = Gravity.CENTER_VERTICAL
        val e = EditText(act)
        e.hint = hint
        e.setText(search)
        e.inputType = InputType.TYPE_CLASS_TEXT
        e.imeOptions = EditorInfo.IME_ACTION_SEARCH
        e.setSingleLine()
        e.setOnEditorActionListener { v, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                onSearch(v.text.toString().trim()); true
            } else false
        }
        row.addView(e, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (withScan) {
            val scan = text("📷 Scan", 14f, bold = true, colorRes = R.color.brand)
            scan.setPadding(dp(12), dp(8), dp(4), dp(8))
            scan.setOnClickListener { scanBarcode() }
            row.addView(scan)
        }
        box.addView(row, lp(8))
    }

    private fun backLink(label: String, onClick: () -> Unit) {
        val b = text("‹ $label", 15f, bold = true, colorRes = R.color.brand)
        b.setPadding(0, dp(10), 0, dp(6))
        b.setOnClickListener { onClick() }
        box.addView(b, lp(4))
    }

    private fun showError(msg: String) {
        val c = card(R.color.warn_bg)
        c.addView(text(msg, 14f, colorRes = R.color.warn_text))
        box.addView(c, lp(12))
    }

    private fun loading() {
        box.removeAllViews()
        box.addView(text("Loading…", 14f, colorRes = R.color.off_text), lp(16))
    }

    // ------------------------------------------------------------------ navigation
    fun open() {
        if (screen == "detail") screen = "orders"
        render()
    }

    /** Android back: true when handled inside these screens. */
    fun back(): Boolean {
        when (screen) {
            "review" -> { screen = "products"; render(); return true }
            "products" -> { leaveOrder(); return true }
            "detail" -> { screen = "orders"; render(); return true }
            "orders" -> { screen = "customers"; search = ""; render(); return true }
        }
        return false
    }

    private fun render() {
        when (screen) {
            "orders" -> loadOrders()
            "products" -> withCatalogue { renderProducts() }
            "review" -> renderReview()
            "detail" -> loadDetail()
            else -> loadCustomers()
        }
    }

    private fun call(path: String, body: JSONObject, onData: (JSONObject) -> Unit, onFail: (String) -> Unit) {
        worker.execute {
            var data: JSONObject? = null
            var error: String? = null
            try {
                data = Api.post(prefs, Api.BOOK + path, body).optJSONObject("data")
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "No connection. Check the internet and try again."
            }
            post(Runnable { if (data != null) onData(data) else onFail(error ?: "Something went wrong.") })
        }
    }

    // ------------------------------------------------------------------ customers
    private fun loadCustomers() {
        loading()
        call("customers", JSONObject(), { d ->
            prefs.bookerCustomers = (d.optJSONArray("customers") ?: JSONArray()).toString()
            renderCustomers(false)
        }, { _ -> renderCustomers(true) })
    }

    private fun renderCustomers(offline: Boolean) {
        box.removeAllViews()
        chips(listOf("customers" to "My customers", "orders" to "My orders" + queuedLabel()), "customers") {
            if (it == "orders") { screen = "orders"; render() }
        }
        val queued = prefs.pendingOrderList().length()
        if (offline) {
            val c = card(R.color.warn_bg)
            c.addView(text("No internet: showing the saved customer list. Orders are queued and sent later.",
                13f, colorRes = R.color.warn_text))
            box.addView(c, lp(8))
        } else if (queued > 0) {
            box.addView(text("$queued order(s) waiting to send.", 13f, colorRes = R.color.warn_text), lp(8))
        }
        box.addView(text("Prices: " + prefs.bookerPricelist.ifEmpty { "customer's pricelist" }, 12f,
            colorRes = R.color.off_text), lp(6))
        searchBox("Search customer or code", false) { s -> search = s; renderCustomers(offline) }
        val all = try { JSONArray(prefs.bookerCustomers) } catch (e: Exception) { JSONArray() }
        val t = lined()
        var shown = 0
        val q = search.lowercase()
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            if (q.isNotEmpty() && !c.optString("name").lowercase().contains(q) && !c.optString("code").lowercase().contains(q)) continue
            shown++
            val r = LinearLayout(act)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(10), dp(10), dp(10), dp(10))
            val l = LinearLayout(act)
            l.orientation = LinearLayout.VERTICAL
            l.addView(text(c.optString("name"), 15f, bold = true))
            val meta = listOf(c.optString("code"), c.optString("zone"),
                if (c.has("balance")) "bal " + num(c.optDouble("balance")) else "").filter { it.isNotBlank() }
            if (meta.isNotEmpty()) l.addView(text(meta.joinToString(" · "), 12f, colorRes = R.color.off_text))
            r.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (c.optBoolean("ordered_today")) r.addView(pill("ordered today", 0xFFE1F5EE.toInt(), 0xFF085041.toInt()))
            val add = text("  + Order", 15f, bold = true, colorRes = R.color.brand)
            r.addView(add)
            r.setOnClickListener { startOrder(c) }
            addRow(t, r)
        }
        if (shown == 0) addRow(t, text("No customers assigned. Ask your administrator.", 14f, colorRes = R.color.off_text)
            .also { it.setPadding(dp(10), dp(12), dp(10), dp(12)) })
    }

    private fun queuedLabel(): String {
        val n = prefs.pendingOrderList().length()
        return if (n > 0) " ($n waiting)" else ""
    }

    // ------------------------------------------------------------------ catalogue
    private fun withCatalogue(then: () -> Unit) {
        if (catalogue.isEmpty() && catalogueFile.exists()) readCatalogue()
        val fresh = System.currentTimeMillis() - catalogueAt < 30 * 60_000L
        if (catalogue.isNotEmpty() && fresh) { then(); return }
        loading()
        call("catalogue", JSONObject(), { d ->
            try {
                catalogueFile.writeText(d.toString())
            } catch (e: Exception) { }
            applyCatalogue(d)
            catalogueAt = System.currentTimeMillis()
            then()
        }, { msg ->
            if (catalogue.isNotEmpty()) {
                Toast.makeText(act, "No internet: using the saved product list.", Toast.LENGTH_LONG).show()
                then()
            } else {
                box.removeAllViews()
                backLink("Customers") { leaveOrder() }
                showError("The product list could not be loaded: $msg")
            }
        })
    }

    private fun readCatalogue() {
        try {
            applyCatalogue(JSONObject(catalogueFile.readText()))
            catalogueAt = 0L     // saved copy: refresh when online
        } catch (e: Exception) { }
    }

    private fun applyCatalogue(d: JSONObject) {
        currency = d.optString("currency", "Rs.")
        val arr = d.optJSONArray("products") ?: JSONArray()
        val list = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        catalogue = list
        byId = list.associateBy { it.optInt("id") }
    }

    private fun firstPack(p: JSONObject): JSONObject? = p.optJSONArray("packs")?.let { if (it.length() > 0) it.getJSONObject(0) else null }

    private fun lineQty(p: JSONObject, q: DoubleArray): Double {
        val pk = firstPack(p)
        return q[0] + (if (pk != null) q[1] * pk.optDouble("qty") else 0.0)
    }

    private fun cartTotal(): Double = cart.entries.sumOf { (id, q) -> byId[id]?.let { lineQty(it, q) * it.optDouble("price") } ?: 0.0 }

    private fun cartQty(): Double = cart.entries.sumOf { (id, q) -> byId[id]?.let { lineQty(it, q) } ?: 0.0 }

    // ------------------------------------------------------------------ new order
    private fun startOrder(c: JSONObject) {
        customer = c
        cart.clear()
        note = ""
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, 1)
        deliveryDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        search = ""
        screen = "products"
        render()
    }

    private fun leaveOrder() {
        if (cart.isEmpty()) { screen = "customers"; search = ""; render(); return }
        AlertDialog.Builder(act)
            .setMessage("Discard this order? The products you picked will be removed.")
            .setPositiveButton("Discard") { _, _ -> cart.clear(); screen = "customers"; search = ""; render() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun matches(p: JSONObject, q: String): Boolean {
        if (q.isEmpty()) return true
        if (p.optString("name").lowercase().contains(q) || p.optString("code").lowercase().contains(q) ||
            p.optString("barcode") == q) return true
        val packs = p.optJSONArray("packs") ?: return false
        for (i in 0 until packs.length()) if (packs.getJSONObject(i).optString("barcode") == q) return true
        return false
    }

    private fun renderProducts() {
        box.removeAllViews()
        val c = customer ?: return
        backLink(c.optString("name")) { leaveOrder() }
        searchBox("Search product, code or barcode", true) { s -> search = s; renderProducts() }
        val q = search.lowercase()
        val list = catalogue.filter { matches(it, q) }
        val inCart = list.filter { cart.containsKey(it.optInt("id")) }
        val rest = list.filter { !cart.containsKey(it.optInt("id")) }
        val shown = (inCart + rest).take(if (q.isEmpty()) 60 else 120)
        val t = lined()
        for (p in shown) addRow(t, productRow(p))
        if (shown.isEmpty()) addRow(t, text("No products found.", 14f, colorRes = R.color.off_text)
            .also { it.setPadding(dp(10), dp(12), dp(10), dp(12)) })
        if (list.size > shown.size) box.addView(text("Showing ${shown.size} of ${list.size}. Search to find more.",
            11f, colorRes = R.color.off_text).also { it.gravity = Gravity.CENTER }, lp(6))
        val sum = card(R.color.ok_bg)
        val row = LinearLayout(act)
        row.addView(text("${cart.size} items · ${qtyText(cartQty())} pcs", 14f, colorRes = R.color.ok_text),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("$currency ${num(cartTotal())}", 16f, bold = true, colorRes = R.color.ok_text))
        sum.addView(row)
        sum.addView(text("Before tax. Final prices and tax come from Odoo.", 11f, colorRes = R.color.ok_text))
        box.addView(sum, lp(10))
        if (cart.isNotEmpty()) box.addView(button("Review order →") { screen = "review"; render() }, lp(8))
    }

    private fun productRow(p: JSONObject): View {
        val id = p.optInt("id")
        val r = LinearLayout(act)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(10), dp(8), dp(10), dp(8))
        r.addView(text(p.optString("name"), 15f, bold = true))
        val pk = firstPack(p)
        val stock = if (p.isNull("stock")) "" else " · stock " + qtyText(p.optDouble("stock"))
        r.addView(text("$currency ${num(p.optDouble("price"))} / ${p.optString("uom", "pc")}" +
            (if (pk != null) " · ${pk.optString("name")} = ${qtyText(pk.optDouble("qty"))}" else "") + stock,
            12f, colorRes = if (!p.isNull("stock") && p.optDouble("stock") <= 0) R.color.danger else R.color.off_text))
        val q = cart[id] ?: doubleArrayOf(0.0, 0.0)
        val ctrl = LinearLayout(act)
        ctrl.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        if (pk != null) ctrl.addView(stepper(if (pk.optString("name").lowercase().startsWith("box")) "Box" else "Ctn", q[1]) { v -> setQty(id, 1, v) })
        ctrl.addView(stepper("Pcs", q[0]) { v -> setQty(id, 0, v) })
        r.addView(ctrl, lp(4))
        return r
    }

    private fun stepper(label: String, value: Double, onChange: (Double) -> Unit): LinearLayout {
        val s = LinearLayout(act)
        s.gravity = Gravity.CENTER_VERTICAL
        s.setPadding(dp(10), 0, 0, 0)
        s.addView(text("$label ", 12f, colorRes = R.color.off_text))
        fun box(t: String, bold: Boolean): TextView {
            val b = text(t, 16f, bold = bold)
            b.gravity = Gravity.CENTER
            b.minWidth = dp(36)
            b.setPadding(dp(6), dp(4), dp(6), dp(4))
            b.background = rounded(0xFFFFFFFF.toInt(), 6, 0xFFB4B2A9.toInt())
            return b
        }
        val minus = box("−", true)
        minus.setOnClickListener { onChange(maxOf(0.0, value - 1)) }
        val v = box(qtyText(value), true)
        v.setOnClickListener { askNumber(label, value, onChange) }
        val plus = box("+", true)
        plus.setOnClickListener { onChange(value + 1) }
        s.addView(minus)
        s.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.leftMargin = dp(4); it.rightMargin = dp(4) })
        s.addView(plus)
        return s
    }

    private fun askNumber(label: String, current: Double, onChange: (Double) -> Unit) {
        val input = EditText(act)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (current > 0) input.setText(qtyText(current))
        input.selectAll()
        val pad = dp(20)
        val wrap = android.widget.FrameLayout(act)
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(input)
        AlertDialog.Builder(act)
            .setTitle("Quantity ($label)")
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onChange(maxOf(0.0, input.text.toString().trim().toDoubleOrNull() ?: 0.0))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setQty(id: Int, idx: Int, v: Double) {
        val q = cart[id] ?: doubleArrayOf(0.0, 0.0)
        q[idx] = v
        if (q[0] <= 0 && q[1] <= 0) cart.remove(id) else cart[id] = q
        renderProducts()
    }

    // ------------------------------------------------------------------ barcode
    private fun scanBarcode() {
        try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(act).startScan()
                .addOnSuccessListener { code ->
                    val value = code.rawValue ?: ""
                    if (value.isNotEmpty()) addByBarcode(value)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(act, "Scanner not available: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(act, "Scanner not available. Type the barcode in the search box.", Toast.LENGTH_LONG).show()
        }
    }

    /** A product barcode adds 1 piece; a carton (packaging) barcode adds 1 carton. */
    private fun addByBarcode(code: String) {
        for (p in catalogue) {
            val id = p.optInt("id")
            if (p.optString("barcode") == code) {
                val q = cart[id] ?: doubleArrayOf(0.0, 0.0)
                q[0] += 1.0; cart[id] = q
                Toast.makeText(act, "+1 pc " + p.optString("name"), Toast.LENGTH_SHORT).show()
                search = ""; renderProducts(); return
            }
            val packs = p.optJSONArray("packs") ?: continue
            for (i in 0 until packs.length()) if (packs.getJSONObject(i).optString("barcode") == code) {
                val q = cart[id] ?: doubleArrayOf(0.0, 0.0)
                q[1] += 1.0; cart[id] = q
                Toast.makeText(act, "+1 " + packs.getJSONObject(i).optString("name") + " " + p.optString("name"), Toast.LENGTH_SHORT).show()
                search = ""; renderProducts(); return
            }
        }
        Toast.makeText(act, "No product with barcode $code", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ review & send
    private fun renderReview() {
        box.removeAllViews()
        val c = customer ?: return
        backLink("Products") { screen = "products"; render() }
        box.addView(text("Order for " + c.optString("name"), 19f, bold = true), lp(2))
        val t = lined()
        for ((id, q) in cart) {
            val p = byId[id] ?: continue
            val pk = firstPack(p)
            val parts = mutableListOf<String>()
            if (q[1] > 0 && pk != null) parts.add(qtyText(q[1]) + " " + pk.optString("name"))
            if (q[0] > 0) parts.add(qtyText(q[0]) + " pcs")
            val r = LinearLayout(act)
            r.setPadding(dp(10), dp(8), dp(10), dp(8))
            val l = LinearLayout(act)
            l.orientation = LinearLayout.VERTICAL
            l.addView(text(p.optString("name"), 14f))
            l.addView(text(parts.joinToString(" + ") + " = " + qtyText(lineQty(p, q)) + " × " + num(p.optDouble("price")),
                12f, colorRes = R.color.off_text))
            r.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(text(num(lineQty(p, q) * p.optDouble("price")), 14f, bold = true))
            addRow(t, r)
        }
        val tot = LinearLayout(act)
        tot.setPadding(dp(10), dp(8), dp(10), dp(8))
        tot.addView(text("Total before tax", 14f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tot.addView(text("$currency ${num(cartTotal())}", 15f, bold = true))
        addRow(t, tot)

        val dd = text("Delivery date: " + dateText(deliveryDate) + "  ▾", 15f, colorRes = R.color.brand)
        dd.setPadding(dp(10), dp(12), dp(10), dp(12))
        dd.background = rounded(0xFFFFFFFF.toInt(), 8, 0xFFB4B2A9.toInt())
        dd.setOnClickListener { pickDelivery() }
        box.addView(dd, lp(10))
        val n = EditText(act)
        n.hint = "Note for the warehouse (optional)"
        n.setText(note)
        n.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { note = s?.toString() ?: "" }
        })
        box.addView(n, lp(6))
        box.addView(text("📍 Your location is saved with the order. Without internet the order waits and is sent later.",
            12f, colorRes = R.color.off_text), lp(6))
        box.addView(button("Send order") { sendOrder() }, lp(10))
    }

    private fun dateText(iso: String): String = try {
        SimpleDateFormat("dd/MM/yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
    } catch (e: Exception) { iso }

    private fun pickDelivery() {
        val cal = Calendar.getInstance()
        try { cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(deliveryDate)!! } catch (e: Exception) { }
        val dlg = android.app.DatePickerDialog(act, { _, y, m, d ->
            deliveryDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d); renderReview()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.minDate = System.currentTimeMillis() - 1000
        dlg.show()
    }

    @SuppressLint("MissingPermission")
    private fun sendOrder() {
        val c = customer ?: return
        if (cart.isEmpty()) return
        val lines = JSONArray()
        for ((id, q) in cart) {
            val p = byId[id] ?: continue
            val ln = JSONObject().put("product_id", id).put("pcs", q[0]).put("ctn", q[1])
            firstPack(p)?.let { ln.put("packaging_id", it.optInt("id")) }
            lines.put(ln)
        }
        val order = JSONObject()
            .put("uid", UUID.randomUUID().toString())
            .put("customer_id", c.optInt("id"))
            .put("customer_name", c.optString("name"))
            .put("t", System.currentTimeMillis())
            .put("delivery_date", deliveryDate)
            .put("note", note)
            .put("total", cartTotal())
            .put("lines", lines)
        fun queue(lat: Double?, lon: Double?) {
            if (lat != null && lon != null) order.put("lat", lat).put("lon", lon)
            val list = prefs.pendingOrderList()
            list.put(order)
            prefs.pendingOrders = list.toString()
            cart.clear()
            Toast.makeText(act, "Order for " + c.optString("name") + " saved. Sending…", Toast.LENGTH_LONG).show()
            screen = "orders"
            period = "today"
            render()
            onQueued()
        }
        try {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(act).lastLocation
                .addOnSuccessListener { loc -> queue(loc?.latitude, loc?.longitude) }
                .addOnFailureListener { queue(null, null) }
        } catch (e: Exception) {
            queue(null, null)
        }
    }

    // ------------------------------------------------------------------ my orders
    private fun loadOrders() {
        loading()
        call("orders", JSONObject().put("period", period), { d -> renderOrders(d) }, { msg -> renderOrders(null, msg) })
    }

    private fun renderOrders(d: JSONObject?, error: String? = null) {
        box.removeAllViews()
        chips(listOf("customers" to "My customers", "orders" to "My orders" + queuedLabel()), "orders") {
            if (it == "customers") { screen = "customers"; search = ""; render() }
        }
        chips(listOf("today" to "Today", "week" to "Last 7 days"), period) { period = it; loadOrders() }
        if (d != null) {
            val s = card(R.color.ok_bg)
            val n = d.optJSONArray("orders")?.length() ?: 0
            s.addView(text((if (period == "today") "Today" else "Last 7 days") + ": $n orders", 12f, colorRes = R.color.ok_text))
            s.addView(text("$currency ${num(d.optDouble("total"))}", 20f, bold = true, colorRes = R.color.ok_text))
            box.addView(s, lp(8))
        } else if (error != null) {
            showError(error)
        }
        val t = lined()
        val pending = prefs.pendingOrderList()
        for (i in 0 until pending.length()) {
            val o = pending.getJSONObject(i)
            addRow(t, orderRow("Not sent · " + o.optString("customer_name"),
                SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(o.optLong("t"))) + " · waiting for internet",
                o.optDouble("total"), pill("Queued", 0xFFF1EFE8.toInt(), 0xFF444441.toInt()), null))
        }
        val arr = d?.optJSONArray("orders") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = when (o.optString("status")) {
                "confirmed" -> pill("Confirmed", 0xFFE1F5EE.toInt(), 0xFF085041.toInt())
                "invoiced" -> pill("Invoiced", 0xFFE6F1FB.toInt(), 0xFF0C447C.toInt())
                "cancelled" -> pill("Cancelled", 0xFFFCEBEB.toInt(), 0xFF791F1F.toInt())
                else -> pill("Waiting", 0xFFFAEEDA.toInt(), 0xFF633806.toInt())
            }
            val sub = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(o.optLong("t"))) + " · " +
                o.optInt("items") + " items" + (if (o.optString("delivery").isNotEmpty()) " · deliver " + dateText(o.optString("delivery")) else "")
            addRow(t, orderRow(o.optString("name") + " · " + o.optString("customer"), sub, o.optDouble("amount"), p) {
                detailId = o.optInt("id"); screen = "detail"; render()
            })
        }
        if (pending.length() == 0 && arr.length() == 0) addRow(t, text("No orders yet.", 14f, colorRes = R.color.off_text)
            .also { it.setPadding(dp(10), dp(12), dp(10), dp(12)) })
    }

    private fun orderRow(title: String, sub: String, amount: Double, status: View, onClick: (() -> Unit)?): View {
        val r = LinearLayout(act)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(10), dp(9), dp(10), dp(9))
        val l = LinearLayout(act)
        l.orientation = LinearLayout.VERTICAL
        l.addView(text(title, 14f, bold = true))
        l.addView(text(sub, 12f, colorRes = R.color.off_text))
        r.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rt = LinearLayout(act)
        rt.orientation = LinearLayout.VERTICAL
        rt.gravity = Gravity.END
        rt.addView(text(num(amount), 14f, bold = true).also { it.gravity = Gravity.END })
        rt.addView(status)
        r.addView(rt)
        if (onClick != null) r.setOnClickListener { onClick() }
        return r
    }

    private fun loadDetail() {
        loading()
        call("order_detail", JSONObject().put("id", detailId), { d -> renderDetail(d) }, { msg ->
            box.removeAllViews()
            backLink("My orders") { screen = "orders"; render() }
            showError(msg)
        })
    }

    private fun renderDetail(d: JSONObject) {
        box.removeAllViews()
        backLink("My orders") { screen = "orders"; render() }
        box.addView(text(d.optString("name") + " · " + d.optString("customer"), 18f, bold = true), lp(2))
        if (d.optString("delivery").isNotEmpty())
            box.addView(text("Delivery " + dateText(d.optString("delivery")), 13f, colorRes = R.color.off_text), lp(2))
        val t = lined()
        val lines = d.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            val q = if (l.optString("pack").isNotEmpty()) qtyText(l.optDouble("pack_qty")) + " " + l.optString("pack") +
                " (" + qtyText(l.optDouble("qty")) + " pcs)" else qtyText(l.optDouble("qty")) + " pcs"
            val r = LinearLayout(act)
            r.setPadding(dp(10), dp(8), dp(10), dp(8))
            val left = LinearLayout(act)
            left.orientation = LinearLayout.VERTICAL
            left.addView(text(l.optString("product"), 14f))
            left.addView(text(q + " × " + num(l.optDouble("price")), 12f, colorRes = R.color.off_text))
            r.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(text(num(l.optDouble("total")), 14f, bold = true))
            addRow(t, r)
        }
        val tot = LinearLayout(act)
        tot.setPadding(dp(10), dp(8), dp(10), dp(8))
        tot.addView(text("Total (with tax)", 14f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tot.addView(text("$currency ${num(d.optDouble("amount"))}", 15f, bold = true))
        addRow(t, tot)
        if (d.optString("note").isNotEmpty()) box.addView(text("Note: " + d.optString("note"), 13f, colorRes = R.color.off_text), lp(8))
        box.addView(text("Read only. The office confirms or changes the order in Odoo.", 11f, colorRes = R.color.off_text)
            .also { it.gravity = Gravity.CENTER }, lp(10))
    }
}
