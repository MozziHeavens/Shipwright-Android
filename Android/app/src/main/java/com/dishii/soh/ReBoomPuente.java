package com.dishii.soh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReBoom - Puente de IA dentro de la app.
 * Antes era bridge_universal.py en Termux; ahora corre solo al abrir el juego.
 * Hecho por Mozzi y Claude (y un Dodo revivido).
 */
public class ReBoomPuente {

    static final String TAG = "ReBoomIA";
    static final File HYRULE = new File("/sdcard/Download/Hyrule");
    static final File CHARS = new File(HYRULE, "Characters");
    static final File LOG_FILE = new File(HYRULE, "reboom_ia.log");
    static final long STORY_EVERY = 45_000L;
    static final long ACTIVE_WINDOW = 5 * 60_000L;

    static volatile String apiKey = "";
    static volatile String model = "gemini-2.5-flash-lite";
    static volatile List<Personaje> personajes = new ArrayList<>();
    static boolean iniciado = false;
    static final Random RND = new Random();
    static final Map<Integer, String> TEXTOS = new HashMap<>();

    static final String REGLAS_COMUNES =
            "Habla en espanol con TU FORMA DE HABLAR, sin acentos y sin la letra enie (si una palabra la lleva, usa un sinonimo). Usa vocabulario de epoca y fantasia, nada moderno. "
            + "Maximo 100 caracteres. 0% cuarta pared: jamas hables de juegos, pantallas, IA, internet ni cosas del futuro. "
            + "Solo conoces Hyrule: nunca nombres personajes de otros mundos. Solo sabes lo que tu personaje sabria. "
            + "No inventes familiares ni cambies los hechos de tu vida.";

    static final String[] PROHIBIDAS = {"bowser", "mario", "luigi", "koopa", "peach", "nintendo", "videojuego", "juego",
            "pantalla", "consola", "celular", "telefono", "internet", "archivo", "jugador", "boton", "control", "robot",
            "inteligencia artificial", "ocarina of time"};
    static final Pattern P_PROH = Pattern.compile("\\b(" + unir("|", PROHIBIDAS) + ")\\b", Pattern.CASE_INSENSITIVE);

    static final String[][] ENIE_FRASES = {{"te extrano", "te echo de menos"}, {"esta manana", "hoy temprano"},
            {"por la manana", "al amanecer"}, {"cumpleanos", "dia especial"}};
    static final Map<String, String> ENIE = new LinkedHashMap<>();
    static final Pattern P_ENIE;

    static {
        String[] pares = {"enseno", "mostro", "ensenaba", "mostraba", "ensenar", "mostrar", "ensenare", "mostrare",
                "ensenarte", "mostrarte", "ensename", "muestrame", "ensena", "muestra", "nino", "chico", "nina", "chica",
                "ninos", "chicos", "ninas", "chicas", "pequeno", "chiquito", "pequena", "chiquita",
                "pequenos", "chiquitos", "pequenas", "chiquitas", "ano", "invierno", "anos", "inviernos",
                "manana", "luego", "sueno", "anhelo", "suenos", "anhelos", "extrano", "raro", "extrana", "rara",
                "extranos", "raros", "extranas", "raras", "montana", "colina", "montanas", "colinas", "cabana", "casa",
                "senor", "caballero", "senora", "dama", "carino", "afecto", "dano", "golpe", "companero", "amigo",
                "companera", "amiga", "tamano", "medida", "bano", "chapuzon", "lena", "troncos", "dueno", "patron"};
        for (int i = 0; i + 1 < pares.length; i += 2) ENIE.put(pares[i], pares[i + 1]);
        List<String> claves = new ArrayList<>(ENIE.keySet());
        Collections.sort(claves, (a, b) -> b.length() - a.length());
        P_ENIE = Pattern.compile("\\b(" + unir("|", claves) + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    static final String MENU = "\u001b\u0005\u0042 Si\u0001 No\u0005\u0040";

    static final Map<Integer, String> LUGARES = new HashMap<>();
    static {
        LUGARES.put(99, "el rancho Lon Lon"); LUGARES.put(76, "la casa del rancho Lon Lon");
        LUGARES.put(81, "el campo de Hyrule"); LUGARES.put(82, "la aldea Kakariko");
        LUGARES.put(32, "el mercado de la ciudadela"); LUGARES.put(33, "el mercado de la ciudadela");
        LUGARES.put(84, "el rio Zora"); LUGARES.put(88, "el Dominio Zora");
        LUGARES.put(96, "el sendero del Monte de la Muerte"); LUGARES.put(98, "la Ciudad Goron");
        LUGARES.put(87, "el lago Hylia"); LUGARES.put(85, "el bosque Kokiri"); LUGARES.put(95, "el castillo de Hyrule");
    }

    // ================= Arranque =================

    public static synchronized void iniciar(final Activity act) {
        if (iniciado) return;
        iniciado = true;
        final SharedPreferences sp = act.getSharedPreferences("reboom_ia", Context.MODE_PRIVATE);
        apiKey = sp.getString("api_key", "");
        model = sp.getString("model", model);

        Thread principal = new Thread(() -> {
            // Esperar el permiso de archivos y tomar la clave de config_ia.json si existe
            for (int i = 0; i < 90 && !HYRULE.isDirectory(); i++) dormir(1000);
            if (apiKey.isEmpty()) {
                JSONObject cfg = rObj(new File(HYRULE, "config_ia.json"));
                String k = cfg.optString("api_key", "").trim();
                if (!k.isEmpty()) {
                    apiKey = k;
                    model = cfg.optString("model", model);
                    sp.edit().putString("api_key", k).putString("model", model).putBoolean("preguntado", true).apply();
                    log("Clave tomada de config_ia.json");
                }
            }
            if (apiKey.isEmpty() && !sp.getBoolean("preguntado", false)) {
                act.runOnUiThread(() -> pedirClave(act, sp));
            }
            cargarTextos();
            bucle();
        }, "ReBoomIA-puente");
        principal.setDaemon(true);
        principal.start();

        Thread historias = new Thread(ReBoomPuente::bucleHistorias, "ReBoomIA-historias");
        historias.setDaemon(true);
        historias.start();
    }

    static void pedirClave(final Activity act, final SharedPreferences sp) {
        try {
            final EditText in = new EditText(act);
            in.setHint("Clave de la API de Gemini");
            in.setSingleLine(true);
            in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
            LinearLayout caja = new LinearLayout(act);
            caja.setPadding(pad, pad / 2, pad, 0);
            caja.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(act)
                    .setTitle("IA de los personajes")
                    .setMessage("Pega tu clave de Gemini para que los personajes conversen contigo. "
                            + "Si la dejas vac\u00eda, usar\u00e1n su memoria sin internet. "
                            + "La clave se guarda solo en este tel\u00e9fono.")
                    .setView(caja)
                    .setCancelable(false)
                    .setPositiveButton("Guardar", (d, w) -> {
                        String k = in.getText().toString().trim();
                        apiKey = k;
                        sp.edit().putString("api_key", k).putBoolean("preguntado", true).apply();
                        log(k.isEmpty() ? "Sin clave: modo sin internet" : "Clave guardada");
                    })
                    .setNegativeButton("Sin internet", (d, w) -> {
                        sp.edit().putBoolean("preguntado", true).apply();
                        log("Modo sin internet elegido");
                    })
                    .show();
        } catch (Exception e) {
            log("No se pudo mostrar el dialogo de la clave: " + e);
        }
    }

    // ================= Bucles =================

    static void bucle() {
        long ultimaBusqueda = 0;
        while (true) {
            try {
                long ahora = System.currentTimeMillis();
                if (personajes.isEmpty() && ahora - ultimaBusqueda > 5000) {
                    ultimaBusqueda = ahora;
                    List<Personaje> ps = new ArrayList<>();
                    buscarPersonajes(CHARS, ps);
                    if (!ps.isEmpty()) {
                        personajes = ps;
                        log("Puente activo con " + ps.size() + " personajes" + (apiKey.isEmpty() ? " (sin internet)" : ""));
                    }
                }
                for (Personaje p : personajes) {
                    try {
                        revisar(p);
                    } catch (Exception e) {
                        log("[" + p.nombre + "] error: " + e);
                    }
                }
            } catch (Exception e) {
                log("error en el bucle: " + e);
            }
            dormir(350);
        }
    }

    static void revisar(Personaje p) {
        String txt = leer(p.f("prompt.txt"));
        if (txt == null) return;
        txt = txt.trim();
        if (txt.isEmpty() || txt.equals(p.lastPrompt)) return;
        boolean primero = p.lastPrompt == null;
        p.lastPrompt = txt;
        if (primero) return; // no reaccionar a prompts viejos al arrancar
        p.lastActivity = System.currentTimeMillis();

        Ctx ctx = Ctx.parse(txt);
        JSONObject prev = rObj(p.f("ultimo_ctx.json"));
        int hoy = parseIntSeguro(ctx.get("day", "0"), 0);
        if (!prev.has("dia")) p.notaDias = "Es la primera vez que hablas con Link (que tu recuerdes en esta epoca).";
        else {
            int antes = prev.optInt("dia", hoy);
            p.notaDias = (hoy - antes >= 1) ? "Hace " + (hoy - antes) + " dias que no ves a Link." : "Ya hablaste con Link hoy.";
        }
        try {
            JSONObject u = new JSONObject();
            u.put("objetos", new JSONArray(ctx.objetos));
            u.put("edad", ctx.get("edad", "nino"));
            u.put("dia", hoy);
            wjson(p.f("ultimo_ctx.json"), u);
        } catch (Exception ignorar) { }

        p.anotarId(ctx.textId);
        String clave = hex(ctx.textId);
        log("[" + p.nombre + "] textId=" + clave);

        File rep = p.f("ids_repetidos.txt");
        Set<Integer> seguros = p.idsSeguros();
        if (rep.exists()) {
            String r = leer(rep);
            if (r != null && r.contains(clave) && !seguros.contains(ctx.textId)) {
                anexar(p.f("ids_seguros.txt"), clave + "\n");
                seguros.add(ctx.textId);
                log("[" + p.nombre + "] " + clave + ": desde la proxima vez habla con IA");
            }
        }
        if (!seguros.contains(ctx.textId)) return; // dialogo de historia: no se toca
        if (p.preguntaObjeto(ctx)) return;
        String r = p.responder(txt, ctx);
        log("[" + p.nombre + "] " + (r.isEmpty() ? "(sin internet: frase del banco)" : "(online) " + r));
        p.escribirMemoria(r.isEmpty() ? null : r);
    }

    static void bucleHistorias() {
        while (true) {
            dormir(STORY_EVERY);
            try {
                if (apiKey.isEmpty()) continue;
                long ahora = System.currentTimeMillis();
                List<Personaje> activos = new ArrayList<>();
                for (Personaje p : personajes) {
                    if (ahora - p.lastActivity < ACTIVE_WINDOW && !p.idsSeguros().isEmpty()) activos.add(p);
                }
                if (!activos.isEmpty()) activos.get(RND.nextInt(activos.size())).historia();
            } catch (Exception e) {
                log("[historia error] " + e);
            }
        }
    }

    static void buscarPersonajes(File dir, List<Personaje> out) {
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        if (!dir.getAbsolutePath().equals(CHARS.getAbsolutePath()) && new File(dir, "personality.json").isFile()) {
            out.add(new Personaje(dir));
        }
        for (File h : hijos) {
            if (!h.isDirectory()) continue;
            String n = h.getName();
            if (n.equals("accesorios") || n.equals("lore_images") || n.equals("sprites") || n.equals("_apartados")
                    || n.startsWith("Backup")) continue;
            buscarPersonajes(h, out);
        }
    }

    static void cargarTextos() {
        File[] fs = HYRULE.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("traduccion_lote_") || !n.endsWith(".json")) continue;
            JSONArray a = rArr(f);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                String t = e.optString("texto", "");
                if (t.isEmpty()) t = e.optString("espanol", "");
                t = t.replaceAll("\\[[A-Z_]+\\]", " ").replaceAll("[\\x00-\\x1f]", " ").replaceAll("\\s+", " ").trim();
                if (t.length() > 90) t = t.substring(0, 90);
                int id = parseHex(e.optString("id", ""), -1);
                if (id >= 0) TEXTOS.put(id, t);
            }
        }
    }

    // ================= Personaje =================

    static class Personaje {
        final File d;
        final String nombre;
        volatile String lastPrompt = null;
        volatile long lastActivity = 0;
        volatile String notaDias = "";

        Personaje(File d) {
            this.d = d;
            String base = CHARS.getAbsolutePath();
            String abs = d.getAbsolutePath();
            this.nombre = abs.startsWith(base + "/") ? abs.substring(base.length() + 1) : d.getName();
        }

        File f(String n) { return new File(d, n); }

        Set<Integer> idsSeguros() {
            Set<Integer> ids = new HashSet<>();
            String s = leer(f("ids_seguros.txt"));
            if (s == null) return ids;
            for (String l : s.split("\n")) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int v = parseHex(l.split("\\s+")[0], -1);
                if (v >= 0) ids.add(v);
            }
            return ids;
        }

        void anotarId(int tid) {
            File p = f("ids_vistos.txt");
            String vistos = leer(p);
            if (vistos == null) vistos = "";
            String clave = hex(tid);
            if (!vistos.contains(clave)) {
                String t = TEXTOS.get(tid);
                anexar(p, clave + " | " + (t != null ? t : "(texto no encontrado)") + "\n");
            }
        }

        String lore(Ctx ctx) {
            JSONObject lo = rObj(f("lore.json"));
            List<String> objs = ctx.objetos;
            String edad = ctx.get("edad", "nino");
            List<String> hechos = strList(lo.optJSONArray("hechos_fijos"));
            JSONArray etapas = lo.optJSONArray("etapas");
            if (etapas != null) {
                for (int i = 0; i < etapas.length(); i++) {
                    JSONObject et = etapas.optJSONObject(i);
                    if (et == null) continue;
                    String ed = et.optString("edad", "");
                    if (!ed.isEmpty() && !ed.equals(edad)) continue;
                    boolean ok = true;
                    for (String o : strList(et.optJSONArray("si_tiene"))) if (!objs.contains(o)) ok = false;
                    for (String o : strList(et.optJSONArray("si_no_tiene"))) if (objs.contains(o)) ok = false;
                    if (ok) hechos.addAll(strList(et.optJSONArray("hechos")));
                }
            }
            StringBuilder t = new StringBuilder("HECHOS DE TU VIDA AHORA MISMO (jamas los contradigas): ");
            t.append(unir(" ", hechos));
            List<String> ap = strList(lo.optJSONArray("aprendidos"));
            if (!ap.isEmpty()) t.append(" COSAS QUE YA CONTASTE Y DEBES MANTENER: ").append(unir(" ", ultimos(ap, 25)));
            File padre = d.getParentFile();
            if (padre != null && !padre.getAbsolutePath().equals(CHARS.getAbsolutePath())) {
                List<String> rec = strList(rObj(new File(padre, "lore.json")).optJSONArray("aprendidos"));
                if (!rec.isEmpty()) {
                    t.append(" RECUERDOS DE HACE SIETE INVIERNOS, CUANDO ERAS MAS JOVEN: ").append(unir(" ", ultimos(rec, 12)));
                }
            }
            return t.toString();
        }

        String personalidad() {
            JSONObject c = rObj(f("personality.json"));
            return "Eres " + c.optString("character", nombre) + ". Tono: " + c.optString("tone", "natural") + ". "
                    + "TU FORMA DE HABLAR: " + c.optString("habla", "neutro y cotidiano") + ". "
                    + unir(" ", strList(c.optJSONArray("lore"))) + " " + unir(" ", strList(c.optJSONArray("rules")));
        }

        void aprender(String hecho) {
            hecho = sanitize(hecho);
            if (hecho.isEmpty() || incoherente(hecho)) return;
            try {
                JSONObject lo = rObj(f("lore.json"));
                List<String> ap = strList(lo.optJSONArray("aprendidos"));
                if (!ap.contains(hecho)) {
                    ap.add(hecho);
                    lo.put("aprendidos", new JSONArray(ultimos(ap, 40)));
                    wjson(f("lore.json"), lo);
                    log("[" + nombre + " aprendio]: " + hecho);
                }
            } catch (Exception ignorar) { }
        }

        List<String> banco() {
            Set<String> s = new LinkedHashSet<>();
            String t = leer(f("brain_bank.txt"));
            if (t == null) return new ArrayList<>();
            for (String l : t.split("\n")) {
                l = l.trim();
                if (l.endsWith(".") || l.endsWith("!") || l.endsWith("?")) s.add(l);
            }
            return new ArrayList<>(s);
        }

        void escribirMemoria(String nueva) {
            List<String> b = banco();
            Collections.shuffle(b, RND);
            List<String> pool = new ArrayList<>();
            if (nueva != null) { pool.add(nueva); pool.add(nueva); pool.add(nueva); }
            int n = 0;
            for (String l : b) {
                if (l.equals(nueva)) continue;
                if (n++ >= 77) break;
                pool.add(l);
            }
            if (!pool.isEmpty()) escribir(f("memory.txt"), unir("\n", pool) + "\n");
        }

        boolean preguntaObjeto(Ctx ctx) {
            JSONObject qs = rObj(f("preguntas_objetos.json"));
            if (qs.length() == 0) return false;
            Set<String> hechas = new TreeSet<>(strList(rArr(f("preguntas_hechas.json"))));
            for (String o : ctx.objetos) {
                if (!qs.has(o) || hechas.contains(o)) continue;
                JSONArray q = qs.optJSONArray(o);
                if (q == null || q.length() < 3) continue;
                escribirBytes(f("memory.txt"), (q.optString(0) + MENU + "\n").getBytes(StandardCharsets.ISO_8859_1));
                escribirBytes(f("memory_choice0.txt"), (q.optString(1) + "\n").getBytes(StandardCharsets.ISO_8859_1));
                escribirBytes(f("memory_choice1.txt"), (q.optString(2) + "\n").getBytes(StandardCharsets.ISO_8859_1));
                hechas.add(o);
                wjson(f("preguntas_hechas.json"), new JSONArray(new ArrayList<>(hechas)));
                log("[" + nombre + "] Pregunta preparada sobre: " + o);
                return true;
            }
            return false;
        }

        String responder(String promptTxt, Ctx ctx) {
            JSONArray hist = rArr(f("history.json"));
            List<String> prevs = new ArrayList<>();
            for (int i = Math.max(0, hist.length() - 8); i < hist.length(); i++) {
                JSONObject h = hist.optJSONObject(i);
                if (h != null) prevs.add(h.optString("respuesta", h.optString("malon", "")));
            }
            String prev = unir(" | ", prevs);
            if (prev.trim().isEmpty()) prev = "Inicio.";
            String p = personalidad() + "\n" + lore(ctx) + "\nREGLAS: " + REGLAS_COMUNES
                    + "\nTUS ULTIMAS RESPUESTAS (no repitas la idea): " + prev
                    + "\n" + notaDias + "\n" + lugarTxt(promptTxt) + "\nESTADO DE LINK: " + promptTxt
                    + "\nSi Link trae algun objeto interesante puedes comentarlo con curiosidad. Responde solo con tu frase:";
            String r = sanitize(gemini(p, 7000));
            if (r.isEmpty() || incoherente(r)) return "";
            try {
                JSONObject h = new JSONObject();
                h.put("evento", promptTxt);
                h.put("respuesta", r);
                hist.put(h);
                JSONArray ult = new JSONArray();
                for (int i = Math.max(0, hist.length() - 8); i < hist.length(); i++) ult.put(hist.get(i));
                wjson(f("history.json"), ult);
            } catch (Exception ignorar) { }
            anexar(f("brain_bank.txt"), r + "\n");
            return r;
        }

        void historia() {
            JSONObject per = rObj(f("personality.json"));
            List<String> temas = strList(per.optJSONArray("temas"));
            if (temas.isEmpty()) temas.add("algo que paso hoy cerca de donde vives");
            JSONObject u = rObj(f("ultimo_ctx.json"));
            Ctx ctx = new Ctx();
            ctx.objetos = strList(u.optJSONArray("objetos"));
            ctx.kv.put("edad", u.optString("edad", "nino"));
            String tema = temas.get(RND.nextInt(temas.size()));
            String raw = gemini(personalidad() + "\n" + lore(ctx) + "\nREGLAS: " + REGLAS_COMUNES
                    + "\nInventa una frase breve que le dirias a Link sobre: " + tema + ".\n"
                    + "Responde EXACTAMENTE en dos lineas:\nHISTORIA: <tu frase>\n"
                    + "HECHO: <el dato nuevo y fijo que esto agrega a tu vida, en tercera persona, o NINGUNO>", 9000);
            String historia = "", hecho = "";
            for (String l : raw.split("\n")) {
                l = l.trim().replace("**", "");
                String up = l.toUpperCase(Locale.ROOT);
                int dp = l.indexOf(':');
                if (dp < 0) continue;
                if (up.startsWith("HISTORIA:")) historia = l.substring(dp + 1);
                else if (up.startsWith("HECHO:")) hecho = l.substring(dp + 1).trim();
            }
            historia = sanitize(historia);
            if (historia.isEmpty() || incoherente(historia)) return;
            anexar(f("brain_bank.txt"), historia + "\n");
            log("[" + nombre + " historia]: " + historia);
            if (!hecho.isEmpty() && !hecho.toUpperCase(Locale.ROOT).contains("NINGUNO")) aprender(hecho);
        }
    }

    // ================= Contexto del juego =================

    static class Ctx {
        Map<String, String> kv = new HashMap<>();
        List<String> objetos = new ArrayList<>();
        int textId = 0;

        String get(String k, String def) {
            String v = kv.get(k);
            return v == null ? def : v;
        }

        static Ctx parse(String txt) {
            Ctx c = new Ctx();
            for (String l : txt.split("\n")) {
                int i = l.indexOf('=');
                if (i < 0) continue;
                c.kv.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
            }
            for (String o : c.get("objetos", "").split(",")) {
                o = o.trim();
                if (!o.isEmpty()) c.objetos.add(o);
            }
            c.textId = parseHex(c.get("textId", "0"), 0);
            return c;
        }
    }

    static String lugarTxt(String prompt) {
        for (String l : prompt.split("\n")) {
            if (!l.startsWith("scene=")) continue;
            int n = parseIntSeguro(l.substring(6).trim(), -1);
            String lugar = LUGARES.get(n);
            return lugar != null ? "AHORA ESTAS EN " + lugar + ". Si no es tu casa, cuenta por que viniste." : "";
        }
        return "";
    }

    // ================= Gemini =================

    static String gemini(String prompt, int timeoutMs) {
        String key = apiKey;
        if (key == null || key.isEmpty()) return "";
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            JSONObject parte = new JSONObject().put("text", prompt);
            JSONObject cont = new JSONObject().put("parts", new JSONArray().put(parte));
            JSONObject body = new JSONObject().put("contents", new JSONArray().put(cont));
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) {
                log("Gemini respondio " + code);
                return "";
            }
            String r = leerStream(c.getInputStream());
            return new JSONObject(r).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ================= Texto =================

    static String fixEnies(String t) {
        for (String[] fr : ENIE_FRASES) {
            t = Pattern.compile("\\b" + fr[0] + "\\b", Pattern.CASE_INSENSITIVE).matcher(t).replaceAll(fr[1]);
        }
        Matcher m = P_ENIE.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group(1);
            String n = ENIE.get(w.toLowerCase(Locale.ROOT));
            if (n == null) n = w;
            if (Character.isUpperCase(w.charAt(0))) n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(n));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String sanitize(String t) {
        if (t == null) return "";
        t = t.replace("\u00f1", "n").replace("\u00d1", "N").replace("\u00bf", "").replace("\u00a1", "").replace("**", "");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{Mn}", "");
        t = t.replace("\n", " ").replaceAll("[^\\x20-\\x7e]", "").trim();
        while (t.startsWith("\"")) t = t.substring(1);
        while (t.endsWith("\"")) t = t.substring(0, t.length() - 1);
        if (t.length() > 105) {
            int c = Math.max(t.lastIndexOf('.', 104), Math.max(t.lastIndexOf('!', 104), t.lastIndexOf('?', 104)));
            if (c > 40) {
                t = t.substring(0, c + 1);
            } else {
                int s = t.lastIndexOf(' ', 100);
                t = (s > 0 ? t.substring(0, s) : t.substring(0, 100)) + "...";
            }
        }
        return fixEnies(t);
    }

    static boolean incoherente(String t) { return P_PROH.matcher(t).find(); }

    // ================= Utilidades =================

    static String unir(String sep, Iterable<String> partes) {
        StringBuilder sb = new StringBuilder();
        for (String p : partes) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    static String unir(String sep, String[] partes) {
        java.util.List<String> l = new java.util.ArrayList<>();
        java.util.Collections.addAll(l, partes);
        return unir(sep, l);
    }

    static String hex(int v) { return String.format(Locale.ROOT, "0x%04X", v); }

    static int parseHex(String s, int def) {
        try {
            s = s.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
            return Integer.parseInt(s, 16);
        } catch (Exception e) {
            return def;
        }
    }

    static int parseIntSeguro(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    static List<String> ultimos(List<String> l, int n) {
        return new ArrayList<>(l.subList(Math.max(0, l.size() - n), l.size()));
    }

    static List<String> strList(JSONArray a) {
        List<String> r = new ArrayList<>();
        if (a == null) return r;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, null);
            if (s != null) r.add(s);
        }
        return r;
    }

    static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignorar) { }
    }

    static String leerStream(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    static String leer(File f) {
        if (!f.isFile()) return null;
        try {
            return leerStream(new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject rObj(File f) {
        try {
            String s = leer(f);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONArray rArr(File f) {
        try {
            String s = leer(f);
            return s == null ? new JSONArray() : new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static void escribirBytes(File f, byte[] b) {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(b);
        } catch (Exception e) {
            log("No se pudo escribir " + f.getName() + ": " + e);
        }
    }

    static void escribir(File f, String s) { escribirBytes(f, s.getBytes(StandardCharsets.UTF_8)); }

    static void anexar(File f, String s) {
        try (OutputStream o = new FileOutputStream(f, true)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log("No se pudo anexar " + f.getName() + ": " + e);
        }
    }

    static void wjson(File f, Object o) {
        try {
            String s = (o instanceof JSONObject) ? ((JSONObject) o).toString(2) : ((JSONArray) o).toString(2);
            escribir(f, s);
        } catch (Exception ignorar) { }
    }

    static synchronized void log(String msg) {
        Log.i(TAG, msg);
        try {
            if (!HYRULE.isDirectory()) return;
            if (LOG_FILE.length() > 300_000) LOG_FILE.delete();
            String hora = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            try (OutputStream o = new FileOutputStream(LOG_FILE, true)) {
                o.write(("[" + hora + "] " + msg + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignorar) { }
    }
}
