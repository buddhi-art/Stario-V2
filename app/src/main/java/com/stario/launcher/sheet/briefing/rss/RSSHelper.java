/*
 * Copyright (C) 2025 Răzvan Albu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.sheet.briefing.rss;

import android.util.Log;

import com.prof18.rssparser.RssParser;
import com.prof18.rssparser.RssParserBuilder;
import com.prof18.rssparser.model.RssChannel;
import com.prof18.rssparser.model.RssItem;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RSSHelper {
    private static final String TAG = "RSSHelper";
    private static final long PARSE_TIMEOUT_SECONDS = 15;
    private static volatile RssParser reader;

    private RSSHelper() {
    }

    private static RssParser getReader() {
        RssParser instance = reader;
        if (instance == null) {
            synchronized (RSSHelper.class) {
                instance = reader;
                if (instance == null) {
                    instance = new RssParserBuilder().build();
                    reader = instance;
                }
            }
        }
        return instance;
    }

    public static @NotNull CompletableFuture<@NotNull RssChannel> futureParse(String url) {
        return RSSHelperKt.parseFeed(getReader(), url);
    }

    public static List<RssItem> parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        CompletableFuture<RssChannel> future = RSSHelperKt.parseFeed(getReader(), url);
        try {
            RssChannel channel = future.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            return channel.getItems();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            Log.e(TAG, "parse: ", exception);
        } catch (Exception exception) {
            future.cancel(true);
            Log.e(TAG, "parse: ", exception);
        }

        return null;
    }
}
