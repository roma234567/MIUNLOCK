package com.miunlock.sniper.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/** Хранилище кук в памяти процесса: на диск ничего не пишется. */
public final class MemoryCookieJar implements CookieJar {

    private final List<Cookie> cookies = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
        synchronized (cookies) {
            for (Cookie cookie : incoming) {
                cookies.removeIf(existing -> existing.name().equals(cookie.name()));
                cookies.add(cookie);
            }
        }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        synchronized (cookies) {
            final List<Cookie> matched = new ArrayList<>();
            for (Cookie cookie : cookies) {
                if (cookie.matches(url)) {
                    matched.add(cookie);
                }
            }
            return matched;
        }
    }

    public String value(String name) {
        synchronized (cookies) {
            for (Cookie cookie : cookies) {
                if (cookie.name().equals(name)) {
                    return cookie.value();
                }
            }
        }
        return "";
    }

    public boolean has(String name) {
        return !value(name).isEmpty();
    }

    public void clear() {
        cookies.clear();
    }
}
