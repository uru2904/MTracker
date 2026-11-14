package com.uru2904.mtracker.data

/** Alimentos propios iniciales (los que ya estaban en la planilla). Se cargan si la tabla está vacía. */
object FoodSeed {
    private val items = listOf(
        FoodItem(name = "Proteína en polvo (whey)", portion = "1 medida (30 g)", kcal = 127.0, protein = 25.0, carbs = 2.6, fat = 1.8, source = "propio"),
        FoodItem(name = "Leche Protein", portion = "1 vaso grande (280 ml)", kcal = 118.0, protein = 14.6, carbs = 12.9, fat = 0.0, source = "propio"),
        FoodItem(name = "Leche Protein", portion = "1 taza (250 ml)", kcal = 105.0, protein = 13.0, carbs = 11.5, fat = 0.0, source = "propio"),
        FoodItem(name = "Barrita Mervick Lab", portion = "1 barrita (65 g)", kcal = 249.0, protein = 21.0, carbs = 24.0, fat = 9.6, source = "propio"),
        FoodItem(name = "Pan lactal", portion = "1 rodaja (25 g)", kcal = 65.0, protein = 2.0, carbs = 12.0, fat = 1.0, source = "propio"),
        FoodItem(name = "Huevo", portion = "1 unidad", kcal = 72.0, protein = 6.3, carbs = 0.4, fat = 4.8, source = "propio"),
    )

    /**
     * Carga la tabla ARGENFOOD (assets/argenfood.json, generada al compilar) cuando cambia.
     * Valores cada 100 g. Se conservan los alimentos de ARGENFOOD que fijaste.
     */
    suspend fun loadArgenfood(db: AppDatabase, context: android.content.Context) {
        val text = runCatching { context.assets.open("argenfood.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return
        val prefs = context.getSharedPreferences("foods", android.content.Context.MODE_PRIVATE)
        val version = text.hashCode()
        if (prefs.getInt("argenfood_version", 0) == version) return
        val arr = org.json.JSONArray(text)
        db.foods().deleteUnpinnedBySource("argenfood")
        val kept = db.foods().namesBySource("argenfood").toSet()
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.getString("name") !in kept }
            .map {
                FoodItem(
                    name = it.getString("name"), portion = "100 g",
                    kcal = it.getDouble("kcal"), protein = it.getDouble("p"),
                    carbs = it.getDouble("c"), fat = it.getDouble("f"), source = "argenfood",
                )
            }
        db.foods().insertAll(items)
        prefs.edit().putInt("argenfood_version", version).apply()
    }

    suspend fun ensure(db: AppDatabase) {
        if (db.foods().count() == 0) {
            db.foods().insertAll(items)
            return
        }
        // Corrección: la Leche Protein se carga en vasos y tazas, no en ml.
        db.foods().find("Leche Protein", "100 ml")?.let { old ->
            db.foods().delete(old)
            items.filter { it.name == "Leche Protein" }.forEach { db.foods().insert(it) }
        }
    }
}
