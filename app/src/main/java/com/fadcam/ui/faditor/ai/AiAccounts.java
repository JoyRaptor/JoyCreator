package com.fadcam.ui.faditor.ai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.ai.joybot.AiProviders;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The AI accounts a person has saved — OpenRouter, an OpenAI subscription, a model on their own
 * computer — and which one Joybot is using.
 *
 * <p>Owner, 2026-10-15: power users have more than one subscription and want to switch between
 * them. The app already reads ONE connection from one place (the key in {@link AiKey}, the
 * address in {@link AiService}, the model in the "ai_model" preference), from twenty call sites.
 * So an account is a saved copy of those three, and switching swaps the saved copy into that
 * slot: nothing that reads the slot had to change. Keys are encrypted at rest with the same
 * Keystore key as {@link AiKey}.</p>
 */
public final class AiAccounts {

    private AiAccounts() {}

    private static final String PREFS = "JoyAiSecrets";
    private static final String LIST = "accounts";
    private static final String ACTIVE = "active_account";
    private static final String PREF_MODEL = "ai_model";
    private static final String DEFAULT_MODEL = "openrouter/free";

    /** One saved account. */
    public static final class Account {
        @NonNull public final String id;
        @NonNull public String name;
        /** The service address; "" = OpenRouter. */
        @NonNull public String base;
        @NonNull public String model;
        /** The key, sealed by {@link AiKey#seal}. */
        @NonNull String keyBlob;

        Account(@NonNull String id, @NonNull String name, @NonNull String base, @NonNull String model,
                @NonNull String keyBlob) {
            this.id = id; this.name = name; this.base = base; this.model = model; this.keyBlob = keyBlob;
        }

        public boolean hasKey() { return !keyBlob.isEmpty(); }
    }

    /** Every saved account, the one in use included. The first call saves the current setup as one. */
    @NonNull
    public static synchronized List<Account> list(@NonNull Context c) {
        List<Account> out = read(c);
        if (out.isEmpty()) {
            Account a = fromSlot(c, UUID.randomUUID().toString(), null);
            out.add(a);
            write(c, out, a.id);
        }
        return out;
    }

    /** The account in use. */
    @NonNull
    public static synchronized Account active(@NonNull Context c) {
        List<Account> all = list(c);
        String id = prefs(c).getString(ACTIVE, null);
        for (Account a : all) if (a.id.equals(id)) return a;
        return all.get(0);
    }

    /**
     * The slot (key, address, model) changed — Settings saved, a model picked: keep the account in
     * use in step with it. {@code name} renames it; null keeps its name, or names it from the
     * service when it has none yet.
     */
    public static synchronized void syncActive(@NonNull Context c, @Nullable String name) {
        List<Account> all = list(c);
        Account cur = active(c);
        Account now = fromSlot(c, cur.id, name != null ? name : cur.name);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(cur.id)) all.set(i, now);
        write(c, all, cur.id);
    }

    /** Use another saved account: its key, address and model go into the slot. */
    public static synchronized void switchTo(@NonNull Context c, @NonNull String id) {
        syncActive(c, null);
        for (Account a : read(c)) {
            if (!a.id.equals(id)) continue;
            AiKey.set(c, AiKey.open(a.keyBlob));
            AiService.setBase(c, a.base);
            SharedPreferencesManager.getInstance(c).sharedPreferences.edit()
                    .putString(PREF_MODEL, a.model.isEmpty() ? DEFAULT_MODEL : a.model).apply();
            prefs(c).edit().putString(ACTIVE, id).apply();
            return;
        }
    }

    /** Start a new, empty account and make it the one in use (Settings then fills it in). */
    @NonNull
    public static synchronized Account addBlank(@NonNull Context c) {
        syncActive(c, null);
        List<Account> all = read(c);
        Account a = new Account(UUID.randomUUID().toString(), "", "", DEFAULT_MODEL, "");
        all.add(a);
        write(c, all, prefs(c).getString(ACTIVE, a.id));
        switchTo(c, a.id);
        return a;
    }

    /** Forget an account. The one in use can only go when another is left to switch to. */
    public static synchronized boolean remove(@NonNull Context c, @NonNull String id) {
        List<Account> all = read(c);
        if (all.size() < 2) return false;
        if (id.equals(active(c).id)) {
            for (Account a : all) if (!a.id.equals(id)) { switchTo(c, a.id); break; }
            all = read(c);
        }
        List<Account> keep = new ArrayList<>();
        for (Account a : all) if (!a.id.equals(id)) keep.add(a);
        write(c, keep, prefs(c).getString(ACTIVE, keep.get(0).id));
        return true;
    }

    /** "OpenAI", "OpenAI 2": a name for a new account on {@code base} that no other one uses. */
    @NonNull
    public static synchronized String freshName(@NonNull Context c, @NonNull String base, @NonNull String selfId) {
        String stem = AiProviders.nameForBase(base);
        String name = stem;
        for (int n = 2; ; n++) {
            boolean taken = false;
            for (Account a : read(c)) if (!a.id.equals(selfId) && a.name.equalsIgnoreCase(name)) taken = true;
            if (!taken) return name;
            name = stem + " " + n;
        }
    }

    // ── storage ──────────────────────────────────────────────────────────────

    @NonNull
    private static Account fromSlot(@NonNull Context c, @NonNull String id, @Nullable String name) {
        String base = AiService.isCustom(c) ? AiService.base(c) : "";
        String model = SharedPreferencesManager.getInstance(c).sharedPreferences.getString(PREF_MODEL, DEFAULT_MODEL);
        String blob = AiKey.seal(AiKey.get(c));
        String n = name == null || name.isEmpty() ? AiProviders.nameForBase(base) : name;
        return new Account(id, n, base, model == null ? DEFAULT_MODEL : model, blob == null ? "" : blob);
    }

    @NonNull
    private static List<Account> read(@NonNull Context c) {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Account(o.getString("id"), o.optString("name", ""), o.optString("base", ""),
                        o.optString("model", DEFAULT_MODEL), o.optString("key", "")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(@NonNull Context c, @NonNull List<Account> all, @NonNull String activeId) {
        JSONArray a = new JSONArray();
        try {
            for (Account x : all) {
                a.put(new JSONObject().put("id", x.id).put("name", x.name).put("base", x.base)
                        .put("model", x.model).put("key", x.keyBlob));
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString(LIST, a.toString()).putString(ACTIVE, activeId).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
