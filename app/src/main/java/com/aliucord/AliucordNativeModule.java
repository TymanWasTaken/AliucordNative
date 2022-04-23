/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord;

import android.os.Environment;
import android.util.Base64;

import com.facebook.react.bridge.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class AliucordNativeModule extends ReactContextBaseJavaModule {
    private final File ALIUCORD_DIR = new File(Environment.getExternalStorageDirectory(), "AliucordRN");
    private final File SETTINGS_DIR = new File(ALIUCORD_DIR, "settings");
    private final File PLUGINS_DIR = new File(ALIUCORD_DIR, "plugins");
    private final File THEMES_DIR = new File(ALIUCORD_DIR, "themes");

    private final Map<String, String> manifestCache = new HashMap<>();
    private final ReactApplicationContext appContext;

    public AliucordNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        appContext = reactContext;
    }

    @Override
    public String getName() {
        return "AliucordNative";
    }

    private void rejectEnoent(Promise p, File file) {
        p.reject("ENOENT", "ENOENT: No such file or directory: " + file.getAbsolutePath());
    }

    private boolean checkFile(File file, Promise p) {
        if (!file.exists()) {
            rejectEnoent(p, file);
            return false;
        }
        return true;
    }

    private boolean checkParent(File file, Promise p) {
        var parent = file.getParentFile();
        if (parent == null) {
            p.reject("EINVAL", "Path must be absolute");
            return false;
        }
        if (!parent.exists()) {
            p.reject("ENOENT", "No such directory: " + parent.getAbsolutePath());
            return false;
        }
        return true;
    }

    private byte[] readBytes(File file) throws IOException {
        long size = file.length();
        if ((int) size != size) throw new IOException("Wow that file is kinda too big buddy...");
        else if (size == 0) return new byte[0];

        var bytes = new byte[(int) size];
        try (var is = new FileInputStream(file)) {
            //noinspection ResultOfMethodCallIgnored
            is.read(bytes);
        }

        return bytes;
    }

    private String readText(File file) throws IOException {
        return new String(readBytes(file));
    }

    private void writeText(File file, byte[] bytes) throws IOException {
        try (var os = new FileOutputStream(file)) {
            os.write(bytes);
        }
    }

    private boolean mkdir(File dir, Promise p) {
        if (!dir.exists() && !dir.mkdir()) {
            p.reject("ENOENT", "Failed to create dir " + dir.getAbsolutePath());
            return false;
        }
        return true;
    }

    @ReactMethod
    public void initAliucordDirs(Promise p) {
        for (var dir : new File[]{ALIUCORD_DIR, PLUGINS_DIR, SETTINGS_DIR, THEMES_DIR}) {
            if (!dir.exists() && !dir.mkdir()) {
                p.reject("ENOENT", "Failed to create Aliucord dirs");
                return;
            }
        }
    }

    @ReactMethod
    public void mkdir(String path, boolean recursive, Promise p) {
        var file = new File(path);

        if (file.exists()) {
            p.resolve(true);
        } else if (recursive) {
            p.resolve(file.mkdirs());
        } else {
            p.resolve(file.mkdir());
        }
    }

    @ReactMethod
    public void readFile(String path, String encoding, Promise p) {
        try {
            var file = new File(path);
            if (!checkFile(file, p)) return;

            var bytes = readBytes(file);
            p.resolve("base64".equals(encoding) ? Base64.encode(bytes, Base64.DEFAULT) : new String(bytes));
        } catch (Throwable ex) {
            ex.printStackTrace();
            p.reject(ex);
        }
    }

    @ReactMethod
    public void writeFile(String path, String newContent, String encoding, Promise p) {
        try {
            var file = new File(path);
            if (!checkParent(file, p)) return;

            writeText(file, "base64".equals(encoding) ? Base64.decode(newContent, Base64.DEFAULT) : newContent.getBytes(StandardCharsets.UTF_8));
            p.resolve(true);
        } catch (Throwable ex) {
            ex.printStackTrace();
            p.reject(ex);
        }
    }

    @ReactMethod
    public void existsFile(String path, Promise p) {
        p.resolve(new File(path).exists());
    }

    @ReactMethod
    public void deleteFile(String path, Promise p) {
        p.resolve(new File(path).delete());
    }

    @ReactMethod
    public void loadPlugins(ReadableArray disabledPlugins, Promise p) {
        var disabled = new HashSet<String>(disabledPlugins.size());
        for (int i = 0; i < disabledPlugins.size(); i++) {
            disabled.add(disabledPlugins.getString(i));
        }

        var errors = Arguments.createMap();
        try {
            var catalyst = appContext.getCatalystInstance();
            var loadScriptFromFile = CatalystInstanceImpl.class.getDeclaredMethod("jniLoadScriptFromFile", String.class, String.class, boolean.class);
            loadScriptFromFile.setAccessible(true);

            var plugins = PLUGINS_DIR.listFiles();
            if (plugins != null) {
                for (var pluginFile : plugins) {
                    var name = pluginFile.getName();
                    if (!name.endsWith(".js.bundle")) continue;
                    name = name.substring(0, name.length() - 10);

                    if (disabled.contains(name)) continue;

                    try {
                        var path = pluginFile.getAbsolutePath();
                        loadScriptFromFile.invoke(catalyst, /* fileName */ path, /* sourceURL */ path, /* loadSynchronously */ false);
                    } catch (Throwable ex) {
                        errors.putString(name, ex.getMessage());
                    }
                }
            }
        } catch (Throwable ex) {
            p.reject("ERR", "Unexpected CatalystInstance");
            return;
        }

        p.resolve(errors);
    }

    @ReactMethod
    public void getManifest(String plugin, Promise p) {
        var cached = manifestCache.get(plugin);
        if (cached != null) {
            p.resolve(cached);
            return;
        }

        var file = new File(PLUGINS_DIR, plugin + ".manifest.json");

        if (!file.exists()) {
            p.reject("ENOENT", "No such plugin: " + plugin);
        } else {
            try {
                var content = readText(file);
                manifestCache.put(plugin, content);
                p.resolve(content);
            } catch (IOException ex) {
                p.reject(ex);
            }
        }
    }

    private WritableMap manifests;

    @ReactMethod
    public void getAllManifests(Promise p) {
        if (manifests == null) {
            var map = Arguments.createMap();

            var files = PLUGINS_DIR.listFiles();
            if (files == null || files.length == 0) {
                p.resolve(map);
                return;
            }

            for (var file : files) {
                var name = file.getName();
                if (!name.endsWith(".manifest.json")) continue;
                name = name.substring(0, name.length() - 14);
                try {
                    var text = readText(file);
                    map.putString(name, text);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

            manifests = map;
        }

        p.resolve(manifests);
    }

    @ReactMethod
    public void listNativeModules(Promise p) {
        var ret = Arguments.createMap();
        for (var module : appContext.getCatalystInstance().getNativeModules()) {
            var methods = Arguments.createArray();

            for (var method : module.getClass().getDeclaredMethods()) {
                if (method.getAnnotation(ReactMethod.class) != null) {
                    methods.pushString(method.toString());
                }
            }
            ret.putArray(module.getName(), methods);
        }
        p.resolve(ret);
    }

    @ReactMethod
    public void getSettings(String plugin, Promise p) {
        var file = new File(SETTINGS_DIR, plugin + ".json");
        if (file.exists()) {
            try {
                p.resolve(readText(file));
            } catch (IOException ex) {
                p.reject(ex);
            }
        } else {
            p.resolve(null);
        }
    }

    @ReactMethod
    public void writeSettings(String plugin, String newContent, Promise p) {
        var file = new File(SETTINGS_DIR, plugin + ".json");

        try {
            writeText(file, newContent.getBytes(StandardCharsets.UTF_8));
            p.resolve(true);
        } catch (IOException ex) {
            p.reject(ex);
        }
    }
}
